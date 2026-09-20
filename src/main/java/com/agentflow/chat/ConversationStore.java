package com.agentflow.chat;

import com.agentflow.llm.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版会话存储（1.4）：sessionId → 最近几轮对话消息。
 * HTTP 无状态，会话状态由服务器自己保管——这是"应用层补状态"最朴素的形态。
 *
 * 已知的三个缺陷（Phase 2 会逐一解决，每一条都是面试题）：
 * 1. 应用重启全丢          → 换 Redis / MySQL 持久化
 * 2. 只在单机有效          → 多实例部署时需要集中存储
 * 3. 会话数只增不减        → Redis 的 TTL 自动过期清理
 */
@Component
public class ConversationStore {

    /**
     * 每个会话最多保留多少条消息。
     * 不是随便定的：模型上下文窗口有限，且 token 按量计费——历史越长越贵。
     * 以后可以升级成"按 token 数截断"或"把老对话摘要压缩"。
     */
    private static final int MAX_MESSAGES_PER_SESSION = 20;

    private final Map<String, List<LlmMessage>> sessions = new ConcurrentHashMap<>();

    /** 读某个会话的历史。返回不可变副本，防止调用方绕过存储直接改内部状态。 */
    public List<LlmMessage> history(String sessionId) {
        List<LlmMessage> list = sessions.get(sessionId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 追加消息，超过上限时丢掉最老的（简单截断；更精细的按轮截断/摘要以后再做）。 */
    public void append(String sessionId, LlmMessage... messages) {
        List<LlmMessage> list = sessions.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.addAll(List.of(messages));
            if (list.size() > MAX_MESSAGES_PER_SESSION) {
                list.subList(0, list.size() - MAX_MESSAGES_PER_SESSION).clear();
            }
        }
    }
}
