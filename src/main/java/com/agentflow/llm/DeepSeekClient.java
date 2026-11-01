package com.agentflow.llm;

import com.agentflow.common.BizException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * DeepSeek（OpenAI 兼容协议）聊天客户端：阻塞式（chat）+ 流式（streamChat）。
 * 1.4 起入参是完整消息列表（系统提示 + 历史 + 本轮用户消息），
 * "带什么上下文"这个决定权在调用方，客户端只管忠实发送。
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

    /** 阻塞式对话：等模型写完，返回回答全文。 */
    public String chat(List<LlmMessage> messages) {
        requireApiKey();
        Response resp = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new Request(props.model(), messages, false))
                .retrieve()
                .bodyToMono(Response.class)
                .onErrorMap(WebClientResponseException.class, this::translateUpstreamError)
                .block(Duration.ofSeconds(props.timeoutSeconds()));
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            throw new BizException("LLM 返回为空");
        }
        return resp.choices().get(0).message().content();
    }

    /**
     * 流式对话：返回逐段文本增量，由调用方决定推给谁（SSE / WebSocket / ...）。
     *
     * 关键：消费 SSE 必须声明 ServerSentEvent 泛型，由框架拆帧（"data:" 前缀、事件边界、
     * 多行 data），不要用 bodyToFlux(String.class) 手写按行解析——text/event-stream
     * 下 String 解码器行为不对，会静默产出空流。
     * DeepSeek 的每个事件 data 是一段 JSON（增量在 choices[0].delta.content），结束标记是 [DONE]。
     */
    public Flux<String> streamChat(List<LlmMessage> messages) {
        requireApiKey();
        return http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new Request(props.model(), messages, true))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .onErrorMap(WebClientResponseException.class, this::translateUpstreamError)
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

    /** 从一段 SSE data 里抠出增量文本；[DONE]、role 行、无法解析的行一律返回空串交给上游过滤。 */
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

    /**
     * 上游 4xx/5xx 统一翻译：WebClient 对非 2xx 抛 WebClientResponseException，
     * 若不处理会落进兜底处理器变成笼统 500——用户看不出是 key 错了还是服务挂了。
     * 在客户端这一层翻译成带指引的 BizException：阻塞路径走 GlobalExceptionHandler，
     * 流式路径的错误事件直接拿到 message——一处翻译，两条路径受益。
     */
    private RuntimeException translateUpstreamError(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String hint = switch (status) {
            case 401 -> "API key 缺失或无效，请检查 application-local.yml 中的 LLM_API_KEY";
            case 402 -> "账户余额不足，请前往服务商平台充值";
            case 403 -> "key 无权限或已被禁用";
            case 404 -> "接口路径不存在，请检查 llm.base-url 配置";
            case 429 -> "请求过于频繁触发限流，请稍后重试";
            default -> status >= 500 ? "服务商服务端异常，请稍后重试" : "请求被服务商拒绝";
        };
        String detail = upstreamErrorDetail(e.getResponseBodyAsString());
        return new BizException("第三方 LLM 服务返回 " + status + "：" + hint + detail);
    }

    /** 抠出上游错误响应里的 message 给人看（截断，防止超长响应刷屏）；抠不出就返回空串。 */
    private String upstreamErrorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            String msg = json.readTree(body).path("error").path("message").asString("");
            if (!msg.isBlank()) {
                return "（" + (msg.length() > 200 ? msg.substring(0, 200) + "…" : msg) + "）";
            }
        } catch (Exception ignored) {
            // 响应体不是 JSON，走下面的原文截断
        }
        String raw = body.strip();
        return "（" + (raw.length() > 200 ? raw.substring(0, 200) + "…" : raw) + "）";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Request(String model, List<LlmMessage> messages, Boolean stream) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(LlmMessage message) {
    }
}
