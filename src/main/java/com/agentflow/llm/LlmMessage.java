package com.agentflow.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条对话消息：role 取 system / user / assistant。
 * 请求 DTO、响应解析、会话存储共用这一个结构——
 * LLM 是无状态的，"多轮记忆"的本质就是每次把历史 messages 全量发给模型。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmMessage(String role, String content) {
}
