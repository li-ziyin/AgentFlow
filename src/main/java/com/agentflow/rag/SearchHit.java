package com.agentflow.rag;

/**
 * 一次检索命中：向量库里的块元数据 + 相似度得分。
 * 引用编号 [n] = 在一次检索结果里的序号（从 1 起），不落库——编号只属于单次问答。
 */
public record SearchHit(String docId, String filename, int chunkIdx, int offset,
                        String heading, String text, double score) {
}
