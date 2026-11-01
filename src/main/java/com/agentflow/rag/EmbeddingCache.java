package com.agentflow.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * embedding 缓存（Redis）：key = 内容 hash，value = 向量 JSON。
 * 同一段文本重复上传/重复评测不再重复计费重嵌入。
 *
 * 降级设计：缓存是优化不是依赖——Redis 挂了只损失缓存命中率，管线照跑。
 * 所以 get/put 全部 try-catch 吞掉异常记 warn，绝不向上抛。
 */
@Component
public class EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCache.class);
    private static final String PREFIX = "agentflow:embed:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public EmbeddingCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** 未命中或 Redis 不可用一律返回 null，调用方按"要嵌入"处理 */
    public float[] get(String hashKey) {
        try {
            String v = redis.opsForValue().get(PREFIX + hashKey);
            return v == null ? null : json.readValue(v, float[].class);
        } catch (Exception e) {
            log.warn("embedding 缓存读取失败（Redis 不可用？）按未命中处理: {}", e.getMessage());
            return null;
        }
    }

    public void put(String hashKey, float[] vector) {
        try {
            redis.opsForValue().set(PREFIX + hashKey, json.writeValueAsString(vector));
        } catch (Exception e) {
            log.warn("embedding 缓存写入失败: {}", e.getMessage());
        }
    }
}
