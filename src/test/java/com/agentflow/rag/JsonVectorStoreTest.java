package com.agentflow.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 向量库 MVP 的三条底线：检索排序正确、跨进程重启不丢、原子替换不留脏数据 */
class JsonVectorStoreTest {

    @TempDir
    Path tmp;

    private JsonVectorStore newStore() {
        RagProperties props = new RagProperties(new RagProperties.Chunking("structural", 100, 10),
                new RagProperties.Embedding("embedding-3", 4, 16), 4, "json",
                tmp.toString());
        return new JsonVectorStore(new ObjectMapper(), props);
    }

    private static float[] vec(float... xs) {
        return xs;
    }

    @Test
    void searchReturnsNearestByCosine() {
        JsonVectorStore store = newStore();
        List<Chunk> chunks = List.of(new Chunk(0, 0, "h1", "alpha"), new Chunk(1, 10, "h2", "beta"));
        store.replaceDocument("doc1", "a.txt", chunks, List.of(vec(1, 0, 0, 0), vec(0, 1, 0, 0)));
        List<SearchHit> hits = store.search(vec(1, 0, 0, 0), 2);
        assertEquals(2, hits.size());
        assertEquals("alpha", hits.get(0).text());
        assertEquals("beta", hits.get(1).text());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertEquals(1.0, hits.get(0).score(), 1e-6);
    }

    @Test
    void dataSurvivesReinstantiation() throws IOException {
        JsonVectorStore store = newStore();
        store.replaceDocument("doc1", "a.txt",
                List.of(new Chunk(0, 0, "", "persist me")), List.of(vec(1, 0, 0, 0)));
        assertTrue(Files.exists(tmp.resolve("vector_db.json")), "落盘文件应存在");
        // "重启"：新实例从文件读回
        JsonVectorStore store2 = newStore();
        assertEquals(1, store2.chunkCount());
        List<SearchHit> hits = store2.search(vec(1, 0, 0, 0), 1);
        assertEquals("persist me", hits.get(0).text());
    }

    @Test
    void replaceDocumentRemovesOldChunksOfSameDoc() {
        JsonVectorStore store = newStore();
        store.replaceDocument("doc1", "a.txt", List.of(new Chunk(0, 0, "", "v1")), List.of(vec(1, 0, 0, 0)));
        store.replaceDocument("doc1", "a.txt", List.of(new Chunk(0, 0, "", "v1-new"), new Chunk(1, 5, "", "v2")),
                List.of(vec(1, 0, 0, 0), vec(0, 1, 0, 0)));
        assertEquals(2, store.chunkCount(), "重传同 docId 应整体替换而不是追加");
        store.deleteDocument("doc1");
        assertEquals(0, store.chunkCount());
    }

    @Test
    void dimMismatchIsRejected() {
        JsonVectorStore store = newStore();   // 配置维度 = 4
        store.replaceDocument("doc1", "a.txt", List.of(new Chunk(0, 0, "", "x")), List.of(vec(1, 0, 0, 0)));
        JsonVectorStore store2 = new JsonVectorStore(new ObjectMapper(),
                new RagProperties(new RagProperties.Chunking("structural", 100, 10),
                        new RagProperties.Embedding("embedding-3", 8, 16), 4, "json", tmp.toString()));
        // 维度改成 8 后读旧库：库里有数据，必须报错而不是跨维度混检
        org.junit.jupiter.api.Assertions.assertThrows(com.agentflow.common.BizException.class,
                store2::chunkCount);
    }
}
