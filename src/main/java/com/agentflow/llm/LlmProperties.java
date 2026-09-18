package com.agentflow.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入配置，绑定 application.yml 里的 llm: 前缀。
 *
 * 知识点：record + @ConfigurationProperties 是 Spring Boot 3 之后的推荐写法——
 * 构造器绑定（不可变对象，没有 setter），框架自动把 yml 字段按名字注入构造器。
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String baseUrl, String apiKey, String model, int timeoutSeconds) {

    public LlmProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
    }
}
