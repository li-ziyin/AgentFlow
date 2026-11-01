package com.agentflow.rag;

import com.agentflow.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量存储真库版：Redis Stack（RediSearch 模块）+ HNSW 索引。
 *
 * 与 A 块 JSON 暴力扫的本质区别：暴力扫每次查询 O(N×dim)，千块级毫秒、十万块级秒级；
 * HNSW 建一张分层近邻图，查询沿图从顶层贪心下行，近似 O(logN)——代价是索引结构本身
 * 占内存、结果只是近似最近邻（recall < 100%，可用 EF_RUNTIME 换）。
 *
 * 为什么 Spring Data Redis 不够用：它不封装向量检索，FT.CREATE/FT.SEARCH 只能走
 * 原生命令——Jedis 是 RediSearch 官方推荐的 Java 客户端。embedding 缓存仍走
 * Spring Data Redis（普通 KV），两个客户端各干各的。
 *
 * 每个块 = 一个 HASH（agentflow:chunk:{uuid}），字段是元数据 + FLOAT32 向量 blob；
 * 索引按前缀 agentflow:chunk: 建在 HASH 上。score 语义对齐 JSON 实现：
 * RediSearch 返回的是距离（COSINE 下 = 1 - 相似度），这里换算回相似度，越大越近。
 */
@Component
@ConditionalOnProperty(name = "rag.vector-store", havingValue = "redis")
public class RedisVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorStore.class);
    static final String KEY_PREFIX = "agentflow:chunk:";
    private static final String INDEX_NAME = "idx:chunk";

    private final RedisClient jedis;
    private final RagProperties props;
    private final int dims;

    public RedisVectorStore(@Value("${spring.data.redis.host:localhost}") String host,
                            @Value("${spring.data.redis.port:6379}") int port,
                            RagProperties props) {
        // Jedis 8 起用 RedisClient（连接池版统一客户端）；JedisPooled 已移除。
        // testOnBorrow：借出前先 PING，容器重启/网络抖动后不会拿到死连接（代价是每次借出多一次往返）
        ConnectionPoolConfig pool = new ConnectionPoolConfig();
        pool.setTestOnBorrow(true);
        this.jedis = RedisClient.builder()
                .hostAndPort(host, port)
                .poolConfig(pool)
                .build();
        this.props = props;
        this.dims = props.embedding().dimensions();
    }

    /** HNSW 参数：M = 每节点出边数（越大图越密、召回越高、内存越大）；EF_CONSTRUCTION = 建图时候选队列长度 */
    private static Map<String, Object> hnswAttrs(int dims) {
        return new HashMap<>(Map.of(
                "TYPE", "FLOAT32",
                "DIM", dims,                 // 与 rag.embedding.dimensions 一致——维度先定再建索引
                "DISTANCE_METRIC", "COSINE",
                "M", 16,
                "EF_CONSTRUCTION", 200));
    }

    /** 启动即建索引（幂等）：Redis Stack 镜像没换、模块缺失这类环境问题在启动时立刻暴露 */
    @PostConstruct
    void ensureIndex() {
        try {
            List<SchemaField> fields = List.of(
                    TagField.of("docId"),
                    TextField.of("filename"),
                    TextField.of("heading"),
                    TextField.of("text"),
                    NumericField.of("chunkIdx"),
                    NumericField.of("offset"),
                    VectorField.builder().fieldName("embedding")
                            .algorithm(VectorField.VectorAlgorithm.HNSW)
                            .attributes(hnswAttrs(dims))
                            .build());
            FTCreateParams params = FTCreateParams.createParams()
                    .on(IndexDataType.HASH)
                    .prefix(KEY_PREFIX);
            jedis.ftCreate(INDEX_NAME, params, fields);
            log.info("RediSearch 索引已创建：{}（HNSW, DIM={}, COSINE）", INDEX_NAME, dims);
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("Index already exists")) {
                log.info("RediSearch 索引已存在：{}", INDEX_NAME);
            } else {
                throw new BizException("创建 RediSearch 索引失败：" + e.getMessage()
                        + "（确认 redis-stack-server 镜像已启动、RediSearch 模块可用）");
            }
        }
    }

    @Override
    public void replaceDocument(String docId, String filename, List<Chunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalStateException("分块数与向量数不一致");
        }
        // 删旧再写新，语义与 JSON 实现的"按文档整体替换"一致
        deleteDocument(docId);
        String pipelineDocId = docId;
        try (var pipeline = jedis.pipelined()) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                Map<String, Object> hash = new HashMap<>();
                hash.put("docId", pipelineDocId);
                hash.put("filename", filename);
                hash.put("heading", c.heading() == null ? "" : c.heading());
                hash.put("text", c.text());
                hash.put("chunkIdx", (long) c.index());
                hash.put("offset", (long) c.offset());
                hash.put("embedding", toFloat32Blob(vectors.get(i)));
                pipeline.hset((KEY_PREFIX + UUID.randomUUID()).getBytes(), toByteMap(hash));
            }
            pipeline.sync();
        }
        log.info("docId=[{}] {} 块写入 Redis Stack（HNSW）", docId, chunks.size());
    }

    @Override
    public List<SearchHit> search(float[] queryVector, int topK) {
        long t0 = System.nanoTime();
        byte[] vec = toFloat32Blob(queryVector);
        // KNN 查询：全库过滤 * => 近邻图搜 K 个；dialect 2 支持 $param 参数化
        Query q = new Query("*=>[KNN $K @embedding $vec AS vector_score]")
                .addParam("K", topK)
                .addParam("vec", vec)
                .setSortBy("vector_score", true)
                .returnFields("docId", "filename", "chunkIdx", "offset", "heading", "text", "vector_score")
                .dialect(2);
        SearchResult r = jedis.ftSearch(INDEX_NAME, q);
        List<SearchHit> hits = new ArrayList<>(r.getDocuments().size());
        for (var doc : r.getDocuments()) {
            Map<String, Object> f = new HashMap<>();
            for (var e : doc.getProperties()) {
                f.put(e.getKey(), e.getValue());
            }
            hits.add(new SearchHit(
                    str(f, "docId"), str(f, "filename"),
                    (int) asLong(f.get("chunkIdx")),
                    (int) asLong(f.get("offset")),
                    str(f, "heading"), str(f, "text"),
                    similarity(f)));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("vector search (redis/hnsw): K={}, took {} ms", topK, ms);
        return hits;
    }

    @Override
    public void deleteDocument(String docId) {
        // TAG 查询的值里连字符等是查询语法特殊字符，必须转义（UUID 全是 hex + 连字符）
        String escaped = docId.replace("-", "\\-");
        SearchResult r = jedis.ftSearch(INDEX_NAME,
                new Query("@docId:{" + escaped + "}").setNoContent().limit(0, 10000).dialect(2));
        List<String> keys = r.getDocuments().stream().map(d -> d.getId()).toList();
        if (!keys.isEmpty()) {
            jedis.del(keys.toArray(new String[0]));
        }
    }

    @Override
    public long chunkCount() {
        SearchResult r = jedis.ftSearch(INDEX_NAME, new Query("*").setNoContent().limit(0, 0).dialect(2));
        return r.getTotalResults();
    }

    /** RediSearch 的 COSINE score 是距离（1 - 相似度），换算回相似度对齐 JSON 实现 */
    private double similarity(Map<String, Object> fields) {
        Object v = fields.get("vector_score");
        return v == null ? 0.0 : 1.0 - asDouble(v);
    }

    /**
     * RediSearch 返回的文档属性一律是 String（NUMERIC 字段也是），
     * 解析要按"可能是字符串"做，不能直接强转 Number。
     */
    private long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    private String str(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** float[] → RediSearch 要求的 FLOAT32 小端字节流 */
    private static byte[] toFloat32Blob(float[] vector) {
        ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    private static Map<byte[], byte[]> toByteMap(Map<String, Object> hash) {
        Map<byte[], byte[]> out = new HashMap<>();
        for (var e : hash.entrySet()) {
            out.put(e.getKey().getBytes(), e.getValue() instanceof byte[] b ? b
                    : String.valueOf(e.getValue()).getBytes());
        }
        return out;
    }
}
