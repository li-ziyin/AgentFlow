package com.agentflow.rag;

import java.util.List;

/**
 * 向量存储抽象：入库（按文档整体替换）/ 检索 / 删档 / 计数。
 *
 * 为什么要接口：A 块先用 JSON 文件 + 内存暴力余弦（MVP，也是 C 块评测的对比基线），
 * B 块切到 Redis Stack HNSW——实现换了，RagService 一行不改，这正是"面向接口编程"的红利；
 * 评测时两个实现还能来回切，跑出"暴力扫 vs HNSW"的延迟对比。
 */
public interface VectorStore {

    /** 一个文档的入库是原子的：先清掉同 docId 旧块再写入新块（重传/重索引语义） */
    void replaceDocument(String docId, String filename, List<Chunk> chunks, List<float[]> vectors);

    /** top-K 相似检索，score = 余弦相似度（已归一化即点积），降序 */
    List<SearchHit> search(float[] queryVector, int topK);

    void deleteDocument(String docId);

    long chunkCount();
}
