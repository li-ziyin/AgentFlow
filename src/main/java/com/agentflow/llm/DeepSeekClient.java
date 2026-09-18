package com.agentflow.llm;

import com.agentflow.common.BizException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * DeepSeek（OpenAI 兼容协议）聊天客户端：阻塞式（chat）+ 流式（streamChat）。
 *
 * 为什么用 WebClient 而不是 RestTemplate：RestTemplate 已进入维护模式且流式支持差；
 * WebClient 平时可以 block() 当同步客户端用，流式时返回 Flux 逐段消费，一个客户端覆盖两种模式。
 *
 * 注意 Jackson 版本：Boot 4 已升级到 Jackson 3（包名 tools.jackson），
 * com.fasterxml 的只有注解包（Jackson 3 沿用它做兼容）。解析 JSON 时别 import 错包。
 */
@Component
public class DeepSeekClient {

    /** OpenAI 兼容协议的流结束标记 */
    private static final String SSE_DONE = "[DONE]";

    private final WebClient http;
    private final LlmProperties props;
    private final ObjectMapper json;

    public DeepSeekClient(WebClient.Builder builder, LlmProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = builder
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }

    /** 阻塞式对话：发一条用户消息，等模型写完，返回全文。上下文（多轮）在 1.4 引入。 */
    public String chat(String userMessage) {
        requireApiKey();
        Request req = new Request(props.model(), List.of(new Message("user", userMessage)), false);
        Response resp = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(Response.class)
                .block(Duration.ofSeconds(props.timeoutSeconds()));
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            throw new BizException("LLM 返回为空");
        }
        return resp.choices().get(0).message().content();
    }

    /**
     * 流式对话（1.3）：返回逐段文本增量，由调用方决定推给谁（SSE / WebSocket / ...）。
     *
     * 关键：消费 SSE 必须声明 ServerSentEvent 泛型，由框架拆帧（"data:" 前缀、事件边界、
     * 多行 data），不要用 bodyToFlux(String.class) 手写按行解析——text/event-stream
     * 下 String 解码器行为不对，会静默产出空流。
     * DeepSeek 的每个事件 data 是一段 JSON（增量在 choices[0].delta.content），结束标记是 [DONE]。
     */
    public Flux<String> streamChat(String userMessage) {
        requireApiKey();
        Request req = new Request(props.model(), List.of(new Message("user", userMessage)), true);
        return http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .map(event -> event.data())
                .filter(data -> data != null && !data.isEmpty())
                .takeUntil(SSE_DONE::equals)
                .map(this::extractDelta)
                .filter(s -> !s.isEmpty());
    }

    /**
     * 前置校验故意写成同步抛出（而不是 Flux.error）：
     * key 没配这类问题发生在"流开始之前"，此时还能正常返回 HTTP 错误码；
     * 一旦流已开始，HTTP 200 和响应头都发出去了，错误只能走流内事件——
     * 这是流式接口错误处理的分界线，也是 1.5 的考点。
     */
    private void requireApiKey() {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new BizException(
                    "LLM_API_KEY 未配置：请先执行 setx LLM_API_KEY \"你的key\"，然后重开终端/IDE 再启动应用");
        }
    }

    /** 从一行 SSE data 里抠出增量文本；心跳行、role 行、无法解析的行一律返回空串交给上游过滤。 */
    private String extractDelta(String data) {
        if (SSE_DONE.equals(data)) {
            return "";
        }
        try {
            JsonNode node = json.readTree(data);
            return node.path("choices").path(0).path("delta").path("content").asString("");
        } catch (Exception e) {
            return "";
        }
    }

    // ==== 请求/响应 DTO：只声明我们关心的字段，未知字段（如 usage、id）直接忽略 ====
    // DeepSeek 是 OpenAI 兼容协议，换厂商时通常只需改 base-url 和 model

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Request(String model, List<Message> messages, Boolean stream) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }
}
