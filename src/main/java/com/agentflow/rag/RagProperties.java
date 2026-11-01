package com.agentflow.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 管线配置，绑定 application.yml 里的 rag: 前缀。
 * 嵌套 record 对应 yml 的嵌套结构，缺省值收拢在紧凑构造器里——配置文件少写一段也能跑。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Chunking chunking, Embedding embedding, int topK,
                            String vectorStore, String dataDir) {

    public RagProperties {
        if (chunking == null) {
            chunking = new Chunking(null, 0, 0);
        }
        if (embedding == null) {
            embedding = new Embedding(null, 0, 0);
        }
        if (topK <= 0) {
            topK = 4;
        }
        if (vectorStore == null || vectorStore.isBlank()) {
            vectorStore = "json";
        }
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "./data";
        }
    }

    /** 分块策略参数。overlap 用百分比表达，改块长时重叠跟着缩放，不用改两处 */
    public record Chunking(String strategy, int chunkSize, int overlapPercent) {

        public Chunking {
            if (strategy == null || strategy.isBlank()) {
                strategy = "structural";
            }
            if (chunkSize <= 0) {
                chunkSize = 400;
            }
            if (overlapPercent < 0) {
                overlapPercent = 15;
            }
            if (overlapPercent > 50) {
                overlapPercent = 50;   // 超过一半就整体倒灌了，钳住
            }
        }

        public int overlapChars() {
            return chunkSize * overlapPercent / 100;
        }
    }

    public record Embedding(String model, int dimensions, int batchSize) {

        public Embedding {
            if (model == null || model.isBlank()) {
                model = "embedding-3";
            }
            if (dimensions <= 0) {
                dimensions = 1024;
            }
            if (batchSize <= 0) {
                batchSize = 16;
            }
        }
    }
}
