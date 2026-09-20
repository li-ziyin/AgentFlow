package com.agentflow.chat;

import com.agentflow.llm.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 会话存储——Phase 2.2 起由内存 Map 改为 MySQL 落库。
 *
 * 关键设计：Controller 和 DeepSeekClient 调用的方法签名一个字没改，
 * 内部实现却从 Map 换成了数据库——这就是"面向接口编程"的红利：
 * 换实现，不动调用方。
 *
 * "存什么"和"带什么进上下文"分离：数据库保存全部历史（可审计、可回放），
 * 发给 LLM 时只取最近 N 条（token 成本可控）。将来改截断策略或做摘要压缩，只动这个类。
 *
 * （旧内存版的三个缺陷：重启丢 / 单机 / 会话数只增不减——前两个已解决，
 *   第三个等 Phase 2 后面用 Redis TTL 补上。）
 */
@Component
public class ConversationStore {

    private final ChatMessageRepository repo;

    public ConversationStore(ChatMessageRepository repo) {
        this.repo = repo;
    }

    /** 追加消息：落库，永久保存 */
    public void append(String sessionId, LlmMessage... messages) {
        for (LlmMessage m : messages) {
            repo.save(new ChatMessageEntity(sessionId, m.role(), m.content()));
        }
    }

    /**
     * 取上下文窗口：全部历史中最近的 20 条，按时间正序返回。
     * 上限数字在 ChatMessageRepository 的方法名里（Top20）。
     */
    public List<LlmMessage> history(String sessionId) {
        List<ChatMessageEntity> recent = repo.findTop20BySessionIdOrderByIdDesc(sessionId);
        Collections.reverse(recent);
        return recent.stream()
                .map(e -> new LlmMessage(e.getRole(), e.getContent()))
                .toList();
    }
}
