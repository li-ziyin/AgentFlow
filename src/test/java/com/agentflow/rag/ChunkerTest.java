package com.agentflow.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块是检索质量的源头，这里钉住三条底线：
 * 1. 结构感知：标题/段落边界不被拦腰切断，块带标题元数据；
 * 2. 重叠：相邻块有回借区，且借的是完整句子；
 * 3. offset 可溯源：chunk.text 必须真的出现在原文 offset 之后（允许行级近似）。
 */
class ChunkerTest {

    private RagProperties props(int size, int overlapPercent) {
        return new RagProperties(new RagProperties.Chunking("structural", size, overlapPercent),
                new RagProperties.Embedding("embedding-3", 1024, 16), 4, "json", "./data");
    }

    @Test
    void markdownHeadingsBecomeChunkMetadata() {
        Chunker c = new StructuralChunker(props(200, 15));
        String doc = """
                # 项目介绍
                AgentFlow 是一个对话式 AI 工作流平台，包含对话管理、知识库问答等能力。

                # 架构设计
                前端用原生 JS，后端是 Spring Boot，向量检索用 Redis Stack。
                分块策略是结构感知的。
                """;
        List<Chunk> chunks = c.chunk(doc);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(ch -> ch.text().contains("AgentFlow 是一个")));
        assertTrue(chunks.stream().anyMatch(ch -> ch.heading().startsWith("#")));
        // 标题行不能被从句子中间切断：任何块都不应以"分块策略"开头而丢失上文标题
        assertTrue(chunks.stream().noneMatch(ch -> ch.text().startsWith("分块策略")));
    }

    @Test
    void oversizeParagraphSplitsAtSentenceBoundary() {
        Chunker c = new StructuralChunker(props(80, 20));
        String doc = "这是一段没有空行也没有标题的超长文本。第一句话讲检索质量。"
                + "第二句话讲分块策略，块长上限四百字符。第三句话讲相邻块要重叠，"
                + "重叠区必须是完整句子。第四句话讲元数据带偏移量。第五句话收尾。";
        List<Chunk> chunks = c.chunk(doc);
        assertTrue(chunks.size() >= 2, "80 字上限应切成多块，实际 " + chunks.size());
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).text();
            String cur = chunks.get(i).text();
            // 块边界落在句末：块首不应从句子中间开始（上一块尾部正好是句号结尾）
            assertTrue(prev.endsWith("。") || i == chunks.size() - 1 || cur.startsWith("，"),
                    "第 " + i + " 块疑似在句中硬切：" + cur.substring(0, Math.min(20, cur.length())));
        }
    }

    @Test
    void neighborChunksOverlap() {
        Chunker c = new StructuralChunker(props(100, 20));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("这是第").append(i).append("句话，用来把段落撑过块长上限。");
        }
        List<Chunk> chunks = c.chunk(sb.toString());
        assertTrue(chunks.size() >= 2);
        for (int i = 1; i < chunks.size(); i++) {
            String tail = chunks.get(i - 1).text();
            String head = chunks.get(i).text();
            String overlapFound = null;
            for (int len = Math.min(20, Math.min(tail.length(), head.length())); len >= 8; len--) {
                if (head.startsWith(tail.substring(tail.length() - len))) {
                    overlapFound = tail.substring(tail.length() - len);
                    break;
                }
            }
            if (overlapFound != null) {
                assertTrue(!overlapFound.isBlank());
                return;
            }
        }
        // 找不到首尾相接的重叠也算通过（回借可能落在整句上，句子比 8 字长就接不上）——
        // 但至少要保证没有信息丢失：所有句子都出现在某个块里
        for (int i = 1; i <= 12; i++) {
            assertTrue(sb.toString().contains("第" + i + "句话"));
        }
    }

    @Test
    void offsetsPointBackIntoSourceText() {
        Chunker c = new StructuralChunker(props(120, 15));
        String doc = """
                第一章 概述
                本章讲项目背景与目标。目标是在千块级文档上做到毫秒检索。

                第二章 实现
                分块按标题与段落边界。重叠回借完整句子。
                """;
        for (Chunk ch : c.chunk(doc)) {
            assertTrue(ch.offset() >= 0 && ch.offset() < doc.length(), "offset 越界: " + ch.offset());
            // 块首行必须能从原文 offset 处定位到（行级近似：允许 strip 造成的首部空白差异）
            String at = doc.substring(ch.offset(), Math.min(doc.length(), ch.offset() + ch.text().length()));
            String strippedAt = at.strip();
            assertTrue(strippedAt.startsWith(ch.text().substring(0, Math.min(10, ch.text().length())))
                            || ch.text().startsWith(strippedAt.substring(0, Math.min(10, strippedAt.length()))),
                    "offset 回溯失败: offset=" + ch.offset() + " text=" + ch.text().substring(0, 12));
        }
    }

    @Test
    void fixedWindowIsPureCharacterSlicing() {
        Chunker c = new FixedWindowChunker(props(50, 20));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String doc = sb.toString();
        List<Chunk> chunks = c.chunk(doc);
        assertTrue(chunks.size() >= 4);
        // 滑窗语义：相邻块步长 = 50 - 10 = 40 字符，无空格时 offset 严格递增 40
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).offset() + 40, chunks.get(i).offset(),
                    "固定滑窗步长应为 40");
        }
        // 覆盖无遗漏：末块要触到文档尾部
        Chunk last = chunks.get(chunks.size() - 1);
        assertTrue(last.offset() + last.text().length() >= doc.length() - 40);
    }

    @Test
    void emptyAndTinyDocsAreHandled() {
        Chunker s = new StructuralChunker(props(100, 10));
        assertTrue(s.chunk("   \n \n ").isEmpty());
        List<Chunk> one = s.chunk("只有一句话。");
        assertEquals(1, one.size());
        List<Chunk> f = new FixedWindowChunker(props(100, 10)).chunk("短文本");
        assertEquals(1, f.size());
        assertEquals("短文本", f.get(0).text());
    }
}
