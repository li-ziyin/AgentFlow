package com.agentflow.rag;

/**
 * 分块器的输出：一个文档切片。
 * offset 是块首字符在整个文档文本里的下标——带它可以回溯原文位置（SSE 溯源、高亮原文都用得上）。
 * heading 记录该块所属的最近一级标题：检索命中后，来源卡片能显示"出自哪个小节"。
 */
public record Chunk(int index, int offset, String heading, String text) {
}
