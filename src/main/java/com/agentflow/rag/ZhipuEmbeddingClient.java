package com.agentflow.rag;

import com.agentflow.common.BizException;
import com.agentflow.llm.ZhipuProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 智谱 embedding 客户端（OpenAI 兼容协议）：批量向量化 + 内容 hash 缓存。
 *
 * 两个容易踩的点：
 * 1. 维度（dimensions）是 embedding-3 的可选参数——它决定向量库索引参数，必须先定维度再建索引，
 *    所以缓存 key 里拼进了维度：改配置不会拿到错维度的旧向量；
 * 2. 批量接口有条数上限，分批发送、按返回的 index 归位，保证输出顺序 = 入参顺序。
 */
@Component
public class ZhipuEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(ZhipuEmbeddingClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient http;
    private final ZhipuProperties zhipu;
    private final RagProperties props;
    private final EmbeddingCache cache;

    public ZhipuEmbeddingClient(WebClient.Builder builder, ZhipuProperties zhipu,
                                RagProperties props, EmbeddingCache cache) {
        this.zhipu = zhipu;
        this.props = props;
        this.cache = cache;
        this.http = builder
                .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + zhipu.apiKey())
                .build();
    }

    /** 批量向量化：返回顺序与入参严格一致（缓存命中直接回填，未命中的分批调 API） */
    public List<float[]> embedAll(List<String> texts) {
        requireApiKey();
        float[][] out = new float[texts.size()][];
        List<Integer> misses = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] hit = cache.get(cacheKey(texts.get(i)));
            if (hit != null && hit.length == dims()) {
                out[i] = hit;
            } else {
                misses.add(i);
            }
        }
        log.info("embedding: {} 条，缓存命中 {}，待嵌入 {}", texts.size(), texts.size() - misses.size(), misses.size());
        int batch = props.embedding().batchSize();
        for (int from = 0; from < misses.size(); from += batch) {
            List<Integer> slice = misses.subList(from, Math.min(from + batch, misses.size()));
            embedBatch(texts, slice, out);
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (float[] v : out) {
            result.add(v);
        }
        return result;
    }

    public float[] embedOne(String text) {
        return embedAll(List.of(text)).get(0);
    }

    private void embedBatch(List<String> texts, List<Integer> slice, float[][] out) {
        List<String> inputs = slice.stream().map(texts::get).toList();
        Response resp;
        try {
            resp = http.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new Request(props.embedding().model(), inputs, dims()))
                    .retrieve()
                    .bodyToMono(Response.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            throw new BizException("智谱 embedding 返回 " + e.getStatusCode().value()
                    + "：key 无效、欠费或参数错误（" + snippet(e.getResponseBodyAsString()) + "）");
        }
        if (resp == null || resp.data() == null || resp.data().size() != inputs.size()) {
            throw new BizException("智谱 embedding 返回条数与请求不符，已中止入库");
        }
        for (EmbData d : resp.data()) {
            // 不假设返回顺序：按协议里的 index 归位（index 对应批内第几个输入）
            if (d.index() < 0 || d.index() >= slice.size()) {
                throw new BizException("智谱 embedding 返回了越界的 index=" + d.index());
            }
            if (d.embedding() == null || d.embedding().length != dims()) {
                throw new BizException("智谱 embedding 返回维度 " + (d.embedding() == null ? 0 : d.embedding().length)
                        + " 与配置 " + dims() + " 不符：请检查 rag.embedding.dimensions");
            }
            float[] v = new float[d.embedding().length];
            for (int j = 0; j < v.length; j++) {
                v[j] = d.embedding()[j];
            }
            out[slice.get(d.index())] = v;
            cache.put(cacheKey(inputs.get(d.index())), v);
        }
    }

    private int dims() {
        return props.embedding().dimensions();
    }

    /** 缓存 key：模型+维度+内容 的 SHA-256。内容或配置任一变化都视为新向量 */
    private String cacheKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String material = props.embedding().model() + ":" + dims() + ":" + text;
            return HexFormat.of().formatHex(md.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void requireApiKey() {
        if (zhipu.apiKey() == null || zhipu.apiKey().isBlank()) {
            throw new BizException("ZHIPU_API_KEY 未配置：请在 application-local.yml 填入智谱开放平台的 key（embedding 用）");
        }
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "无响应体";
        }
        String raw = body.strip();
        return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
    }

    // ==== OpenAI 兼容协议的请求/响应 DTO ====

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Request(String model, List<String> input, Integer dimensions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<EmbData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbData(int index, float[] embedding) {
    }
}
