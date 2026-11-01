package com.agentflow.rag;

import java.util.List;

/**
 * 分块策略接口。做成策略而不是写死一种，是因为 C 块评测要跑
 * "固定滑窗 vs 结构感知" 的前后对比——两个实现都留着，评测脚本切参数就能对比。
 */
public interface Chunker {

    List<Chunk> chunk(String text);
}
