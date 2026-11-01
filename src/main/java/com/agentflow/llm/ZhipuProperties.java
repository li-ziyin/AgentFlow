package com.agentflow.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 智谱开放平台配置。key 取值来源同 LLM_API_KEY：环境变量 > application-local.yml > 空默认。 */
@ConfigurationProperties(prefix = "zhipu")
public record ZhipuProperties(String apiKey) {
}
