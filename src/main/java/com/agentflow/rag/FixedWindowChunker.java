package com.agentflow.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定滑窗分块：cx-ai-knowledge-base 的同款做法（纯字符窗口 + 固定步长）。
 * 刻意保留为对比基线——C 块评测要拿它跑出"结构感知分块提升召回"的前后对比数字。
 * 它的缺点是对照面：窗口边界落在句子/段落中间，语义被拦腰切断。
 */
@Component
public class FixedWindowChunker implements Chunker {

    private final RagProperties props;

    public FixedWindowChunker(RagProperties props) {
        this.props = props;
    }

    @Override
    public List<Chunk> chunk(String text) {
        int size = props.chunking().chunkSize();
        int step = size - props.chunking().overlapChars();
        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + size, text.length());
            // 首尾去空白：offset 跟着前移，保证 offset 指向块首真实字符
            int from = start;
            while (from < end && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            int to = end;
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }
            if (to > from) {
                chunks.add(new Chunk(chunks.size(), from, "", text.substring(from, to)));
            }
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}
