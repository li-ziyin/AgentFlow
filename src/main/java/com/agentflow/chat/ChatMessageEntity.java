package com.agentflow.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * chat_message 表的一行 = 一条对话消息。
 *
 * 为什么不用 record：JPA 实体要求无参构造 + 可变字段（框架靠反射填充），
 * record 的不可变模型与之冲突。所以本项目的惯例是——DTO 用 record，实体用普通类。
 */
@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_chat_message_session", columnList = "session_id"))
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id：客户端生成的 UUID。查询都按它分组，所以建了索引 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** system / user / assistant */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息内容。用 TEXT 类型：模型的长回答也不会被截断 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** JPA 必需的无参构造。protected：只给框架用，业务代码请走带参构造 */
    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String sessionId, String role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
