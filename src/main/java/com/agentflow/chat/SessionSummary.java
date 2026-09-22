package com.agentflow.chat;

import java.time.Instant;

/**
 * 会话列表的摘要行（Spring Data 的接口投影：JPQL 查询结果按别名填进这些 getter）。
 * 一个会话一行：id、首条消息预览、条数、最后活跃时间。
 */
public interface SessionSummary {

    String getSessionId();

    Instant getLastAt();

    Long getMessageCount();
}
