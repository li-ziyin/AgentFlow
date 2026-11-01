package com.agentflow.rag;

import com.agentflow.common.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 向量存储 MVP：JSON 文件 + 内存暴力余弦。
 * 故意先这样做（roadmap 2.4）：把管线复杂度集中在分块/embedding/检索链路上，
 * 存储留一个最朴素但能用的实现——B 块换 Redis Stack 时才有"升级故事"可讲。
 *
 * 数据规模预期千块级：暴力扫 = 每次查询 O(N×dim) 次浮点乘加，千块 × 1024 维
 * 在本机是毫秒级——够用，但要心里有数：这是线性复杂度，不是索引。
 *
 * 一致性设计：内存全量 + 写穿落盘，保存走"临时文件 + 原子替换"——
 * 进程中途被杀也不会留下半截 JSON（下次启动还能完整读回）。
 */
@Component
@ConditionalOnProperty(name = "rag.vector-store", havingValue = "json", matchIfMissing = true)
public class JsonVectorStore implements VectorStore {

    private final ObjectMapper json;
    private final Path file;
    private final int dimensions;

    /** 落盘结构：一行块记录 = 元数据 + 向量 */
    record StoredChunk(String id, String docId, String filename, int chunkIdx,
                       int offset, String heading, String text, float[] vector) {
    }

    record Db(int dim, List<StoredChunk> chunks) {
    }

    private Db db;

    public JsonVectorStore(ObjectMapper json, RagProperties props) {
        this.json = json;
        this.file = Path.of(props.dataDir(), "vector_db.json");
        this.dimensions = props.embedding().dimensions();
    }

    @Override
    public synchronized void replaceDocument(String docId, String filename,
                                             List<Chunk> chunks, List<float[]> vectors) {
        loadIfNeeded();
        if (chunks.size() != vectors.size()) {
            throw new IllegalStateException("分块数与向量数不一致");
        }
        List<StoredChunk> kept = new ArrayList<>(db.chunks().stream()
                .filter(c -> !c.docId().equals(docId))
                .toList());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            kept.add(new StoredChunk(UUID.randomUUID().toString(), docId, filename,
                    c.index(), c.offset(), c.heading(), c.text(), normalize(vectors.get(i))));
        }
        db = new Db(dimensions, kept);
        save();
    }

    @Override
    public synchronized List<SearchHit> search(float[] queryVector, int topK) {
        loadIfNeeded();
        float[] q = normalize(queryVector);
        // 暴力余弦：向量入库时已归一化，score = 点积。千块级毫秒完成，纯线性扫描无索引
        List<Scored> scored = new ArrayList<>();
        for (StoredChunk c : db.chunks()) {
            scored.add(new Scored(dot(q, c.vector()), c));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<SearchHit> hits = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            StoredChunk c = scored.get(i).chunk;
            hits.add(new SearchHit(c.docId(), c.filename(), c.chunkIdx(), c.offset(),
                    c.heading(), c.text(), scored.get(i).score));
        }
        return hits;
    }

    @Override
    public synchronized void deleteDocument(String docId) {
        loadIfNeeded();
        db = new Db(db.dim(), db.chunks().stream().filter(c -> !c.docId().equals(docId)).toList());
        save();
    }

    @Override
    public synchronized long chunkCount() {
        loadIfNeeded();
        return db.chunks().size();
    }

    private record Scored(double score, StoredChunk chunk) {
    }

    private float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private float[] normalize(float[] v) {
        double sq = 0;
        for (float x : v) {
            sq += x * x;
        }
        float norm = (float) Math.sqrt(sq);
        if (norm == 0f) {
            return v.clone();   // 零向量原样存，检索时点积恒 0，不会污染结果
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / norm;
        }
        return out;
    }

    private void loadIfNeeded() {
        if (db != null) {
            checkDim();
            return;
        }
        if (!Files.exists(file)) {
            db = new Db(dimensions, new ArrayList<>());
            return;
        }
        try {
            db = json.readValue(file.toFile(), Db.class);
        } catch (tools.jackson.core.JacksonException e) {
            // Jackson 3 的解析异常是非受检的 JacksonException
            throw new BizException("向量库文件损坏或格式不认识：" + file
                    + "（确认无需保留后可删除重建，代价是重新上传文档）");
        }
        checkDim();
    }

    /** 维度变了意味着旧向量全部作废：宁可报错也不做静默的跨维度混检 */
    private void checkDim() {
        if (db.dim() != dimensions && !db.chunks().isEmpty()) {
            throw new BizException("向量维度配置为 " + dimensions + "，但库中已有 " + db.dim()
                    + " 维向量：两者不兼容。改回维度，或删除 " + file + " 后重新上传全部文档");
        }
    }

    /** 写穿落盘：临时文件 + 原子替换，进程中途被杀也不会留下半截 JSON */
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json.writeValueAsString(db));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BizException("向量库落盘失败：" + e.getMessage());
        }
    }
}
