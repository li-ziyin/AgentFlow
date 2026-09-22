package com.agentflow.chat;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA 的"命名即查询"：只声明接口，不写实现。
 * findTop20BySessionIdOrderByIdDesc 会被框架解析成
 * SELECT * FROM chat_message WHERE session_id = ? ORDER BY id DESC LIMIT 20
 *
 * 想改上下文窗口大小？改方法名里的数字（或以后换成 Pageable 传参）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findTop20BySessionIdOrderByIdDesc(String sessionId);

    /** 某会话的全部消息（旧到新），历史回放用 */
    List<ChatMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);

    /** 某会话的第一条消息，列表页做预览用 */
    ChatMessageEntity findFirstBySessionIdOrderByIdAsc(String sessionId);

    /** 会话列表：按会话分组，最近活跃的在前（接口投影见 SessionSummary） */
    @Query("""
            select m.sessionId as sessionId,
                   max(m.createdAt) as lastAt,
                   count(m) as messageCount
            from ChatMessageEntity m
            group by m.sessionId
            order by max(m.id) desc
            """)
    List<SessionSummary> summarizeSessions();
}
