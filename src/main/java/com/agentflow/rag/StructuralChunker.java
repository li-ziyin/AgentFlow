package com.agentflow.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知分块：按标题/段落边界切，块长上限 + 相邻块重叠。
 *
 * 为什么不照搬固定滑窗（cx-ai 的 200/50）：滑窗会在句子和段落中间硬切，
 * 同一个小节被切成两半后，两块的语义都不完整——"问题→块"的匹配质量直接受损。
 * 结构感知把块边界尽量落在语义边界上，metadata 里还带上所属标题。
 *
 * 算法三步：
 *   1. 行扫描识别标题（Markdown #、编号 1.2/第X章，以及"短行无句末标点且后跟正文"启发式）；
 *   2. 空行/标题切出段落块，每块带字符偏移和所属标题；
 *   3. 贪心装块：装不下就收块，新块从上一块尾部按句边界回借 overlap 字符——
 *      借的是完整句子而不是裸字符，重叠区照样语义完整。
 *
 * offset 语义：块首行在原文中的起始下标。段落内部逐行 strip 过，
 * 重叠借入区的起点按 lastIndexOf 回溯——都是行级精确、字符级近似，溯源够用。
 */
@Component
public class StructuralChunker implements Chunker {

    /** Markdown 标题：# ~ ###### */
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+.+");
    /** 编号标题：第X章 / 1.2 / 1、 / 一、 / （三）——编号后要有实际内容，防止误伤列表项 */
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(第[0-9一二三四五六七八九十百]+[章节篇部分回]|[0-9]+(\\.[0-9]+)+\\s*\\S|[0-9]+[、．.]\\s*\\S|[（(]?[0-9一二三四五六七八九十]+[)）．]\\s*\\S)");
    /** 纯数字/标点（如单独一行的页码"42"）不是标题 */
    private static final Pattern NOISE_LINE = Pattern.compile("^[0-9\\s\\p{Punct}\\u3000]+$");
    private static final String SENTENCE_BREAK = "。！？!?；;\n…";

    private final RagProperties props;

    public StructuralChunker(RagProperties props) {
        this.props = props;
    }

    /** 中间产物：一个段落/标题块。offset 是在全文里的字符下标 */
    private record Block(String heading, int offset, String text) {
        int length() {
            return text.length();
        }
    }

    @Override
    public List<Chunk> chunk(String text) {
        int target = props.chunking().chunkSize();
        int overlap = props.chunking().overlapChars();
        List<Block> blocks = toBlocks(text);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curStart = -1;
        String curHeading = "";
        for (Block b : blocks) {
            if (b.length() > target) {
                // 单段超长（常见于无空行的 PDF 抽取文本）：先收掉手上攒的，再把大段按句切开
                flush(chunks, cur, curStart, curHeading);
                cur = new StringBuilder();
                curStart = -1;
                curHeading = "";
                chunkOversized(b, target, overlap, chunks);
                continue;
            }
            if (cur.length() > 0 && cur.length() + b.length() > target) {
                String prevText = cur.toString();
                int prevStart = curStart;
                flush(chunks, cur, curStart, curHeading);
                // 重叠：从上一块尾部按句边界回借 overlap 字符（完整句子），标题随上一块
                String carried = carryOver(prevText, overlap);
                if (carried.isEmpty()) {
                    cur = new StringBuilder();
                    curStart = -1;
                } else {
                    cur = new StringBuilder(carried);
                    curStart = curStartOfCarried(prevStart, prevText, carried);
                }
            }
            if (cur.isEmpty()) {
                curStart = b.offset();
                curHeading = b.heading();
            } else {
                cur.append('\n');
            }
            cur.append(b.text());
        }
        flush(chunks, cur, curStart, curHeading);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            chunks.set(i, new Chunk(i, c.offset(), c.heading(), c.text()));
        }
        return chunks;
    }

    /** 攒满的当前块收编成 Chunk；全空白则丢弃 */
    private void flush(List<Chunk> out, StringBuilder cur, int start, String heading) {
        String t = cur.toString().strip();
        if (t.isEmpty() || start < 0) {
            return;
        }
        out.add(new Chunk(out.size(), start, heading, t));
    }

    /** 按句边界从块尾回借 ≤overlap 字符；不足半程就放弃（避免借出碎片） */
    private String carryOver(String chunkText, int overlap) {
        if (overlap <= 0) {
            return "";
        }
        String tail = chunkText.length() > overlap * 2
                ? chunkText.substring(chunkText.length() - overlap * 2)
                : chunkText;
        if (tail.length() <= overlap) {
            return tail.strip();
        }
        // 从 tail 中部向后找句边界，让留下的部分落在句首、长度尽量接近 overlap
        int from = tail.length() - overlap;
        for (int i = from; i < tail.length(); i++) {
            if (SENTENCE_BREAK.indexOf(tail.charAt(i)) >= 0) {
                if (i + 1 >= tail.length()) {
                    return "";   // 边界正好在末尾，没有可借内容
                }
                return tail.substring(i + 1).strip();
            }
        }
        return tail.substring(from).strip();
    }

    /** 借走文本在全文里的真实起点：上一块起点 + 借走文本在上一块里的相对位置 */
    private int curStartOfCarried(int prevStart, String prevText, String carried) {
        int local = prevText.lastIndexOf(carried);
        if (local < 0) {
            local = Math.max(0, prevText.length() - carried.length());
        }
        return prevStart + local;
    }

    /** 单段超长：按句打包成 ≤target 的块，块间同样回借 overlap（借入区与原文连续，不加分隔符） */
    private void chunkOversized(Block b, int target, int overlap, List<Chunk> out) {
        List<int[]> sentences = splitSentences(b.text());
        StringBuilder cur = new StringBuilder();
        int curStart = b.offset();
        for (int[] s : sentences) {
            if (cur.length() > 0 && cur.length() + (s[1] - s[0]) > target) {
                String prevText = cur.toString();
                out.add(new Chunk(out.size(), curStart, b.heading(), prevText.strip()));
                String carried = carryOver(prevText, overlap);
                cur = carried.isEmpty() ? new StringBuilder() : new StringBuilder(carried);
                curStart = carried.isEmpty()
                        ? b.offset() + s[0]
                        : curStartOfCarried(curStart, prevText, carried);
                if (curStart < b.offset()) {
                    curStart = b.offset() + s[0];
                    cur = new StringBuilder();
                }
            }
            if (cur.isEmpty()) {
                curStart = b.offset() + s[0];
            }
            cur.append(b.text(), s[0], s[1]);
        }
        String t = cur.toString().strip();
        if (!t.isEmpty()) {
            out.add(new Chunk(out.size(), curStart, b.heading(), t));
        }
    }

    /** 切句：返回 [start, end) 下标对，句末标点保留 */
    private List<int[]> splitSentences(String text) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_BREAK.indexOf(text.charAt(i)) >= 0) {
                out.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(new int[]{start, text.length()});
        }
        return out;
    }

    /**
     * 行扫描 → 段落块。标题行独立成块（也参与打包——"标题+首段"常常同块，
     * 这正是检索"某章节内容"问题时最想要的形态）。
     * 空行收段落；"短行无标点"启发式要求后一行非空（后跟空行的短行是普通段落）。
     */
    private List<Block> toBlocks(String text) {
        String[] lines = text.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        String heading = "";
        StringBuilder para = new StringBuilder();
        int paraStart = -1;
        int lineStart = 0;   // 当前行在全文中的起始下标：累进计算，避免 O(n²) 回溯
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            int thisStart = lineStart;
            lineStart += lines[i].length() + 1;   // +1 是被 split 吃掉的换行
            if (line.isEmpty()) {
                blocks.add(flushPara(para, paraStart, heading));
                para = new StringBuilder();
                paraStart = -1;
                continue;
            }
            if (isHeading(line, i + 1 < lines.length && !lines[i + 1].strip().isEmpty())) {
                blocks.add(flushPara(para, paraStart, heading));
                para = new StringBuilder();
                paraStart = -1;
                heading = line;
                blocks.add(new Block(heading, thisStart, line));
                continue;
            }
            if (para.isEmpty()) {
                paraStart = thisStart;
            } else {
                para.append('\n');
            }
            para.append(line);
        }
        blocks.add(flushPara(para, paraStart, heading));
        return blocks.stream().filter(b -> !b.text().isEmpty()).toList();
    }

    private Block flushPara(StringBuilder para, int start, String heading) {
        return new Block(heading, start, para.toString().strip());
    }

    private boolean isHeading(String line, boolean followedByContent) {
        if (MD_HEADING.matcher(line).matches() || NUMBERED_HEADING.matcher(line).matches()) {
            return true;
        }
        // 启发式：短、无句末/逗号标点、不是纯数字页码、且后面紧跟正文——像目录/标题的样子
        if (!followedByContent || line.length() > 30 || NOISE_LINE.matcher(line).matches()) {
            return false;
        }
        char last = line.charAt(line.length() - 1);
        return SENTENCE_BREAK.indexOf(last) < 0
                && last != '，' && last != ',' && last != '、' && last != ':' && last != '：';
    }
}
