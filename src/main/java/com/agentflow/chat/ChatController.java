package com.agentflow.chat;

import com.agentflow.common.Result;
import com.agentflow.llm.DeepSeekClient;
import com.agentflow.llm.LlmMessage;
import com.agentflow.llm.LlmProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final DeepSeekClient llm;
    private final LlmProperties props;
    private final ConversationStore store;
    private final ObjectMapper json;

    public ChatController(DeepSeekClient llm, LlmProperties props, ConversationStore store, ObjectMapper json) {
        this.llm = llm;
        this.props = props;
        this.store = store;
        this.json = json;
    }

    /**
     * sessionId 可以不传：服务器会生成一个新的并随响应返回，
     * 客户端之后每一轮带上它，就是"同一个对话"。服务器不认识"用户"，只认识会话 id。
     */
    public record ChatRequest(String sessionId, @NotBlank String message) {
    }

    public record ChatResponse(String sessionId, String answer) {
    }

    /** 1.2 阻塞版 + 1.4 多轮上下文。 */
    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest req) {
        String sessionId = resolveSessionId(req);
        String answer = llm.chat(buildMessages(sessionId, req.message()));
        store.append(sessionId,
                new LlmMessage("user", req.message()),
                new LlmMessage("assistant", answer));
        return Result.ok(new ChatResponse(sessionId, answer));
    }

    /**
     * 1.3 流式版 + 1.4 多轮上下文。
     *
     * 流式版协议：先发一个 session 事件（客户端立刻拿到会话 id，不必等流结束），
     * 然后若干 delta 事件（文本增量），出错发 error 事件，正常结束由服务端收尾。
     * 完整回答只在流成功结束后才写入历史——半截回答不污染上下文。
     */
    @PostMapping("/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
        String sessionId = resolveSessionId(req);
        SseEmitter emitter = new SseEmitter(props.timeoutSeconds() * 1000L);
        sendSessionId(emitter, sessionId);

        StringBuilder answer = new StringBuilder();
        List<LlmMessage> messages = buildMessages(sessionId, req.message());

        Disposable subscription = llm.streamChat(messages).subscribe(
                delta -> {
                    answer.append(delta);
                    try {
                        // 增量 JSON 编码后发送：SSE 规范会吃掉 "data:" 后的一个空格，
                        // 而 DeepSeek 的增量自带前导空格（如 " How"），直接发会被客户端吞掉。
                        // JSON 字符串的引号把内容原样包住，空格/换行都不会丢。
                        emitter.send(SseEmitter.event().name("delta")
                                .data(json.writeValueAsString(delta), MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                err -> {
                    // 流已开始，HTTP 状态码改不了了，只能把错误作为事件发进流里。
                    // 同时必须记日志：只发事件不留日志，服务端就零痕迹可查
                    log.error("SSE stream failed, sessionId=[{}]", sessionId, err);
                    try {
                        String msg = err.getMessage() == null ? "unknown error" : err.getMessage();
                        emitter.send(SseEmitter.event().name("error").data(msg, MediaType.TEXT_PLAIN));
                    } catch (IOException ignored) {
                        // 连接已不可用，无需再发
                    }
                    emitter.complete();
                },
                () -> {
                    store.append(sessionId,
                            new LlmMessage("user", req.message()),
                            new LlmMessage("assistant", answer.toString()));
                    emitter.complete();
                }
        );

        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        emitter.onCompletion(subscription::dispose);
        return emitter;
    }

    private void sendSessionId(SseEmitter emitter, String sessionId) {
        try {
            emitter.send(SseEmitter.event().name("session").data(sessionId, MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            log.warn("Failed to send session event", e);
        }
    }

    /** 历史 + 本轮用户消息。LLM 无状态，"记忆"就是每次把历史全量带上。 */
    private List<LlmMessage> buildMessages(String sessionId, String userMessage) {
        List<LlmMessage> messages = new ArrayList<>(store.history(sessionId));
        messages.add(new LlmMessage("user", userMessage));
        return messages;
    }

    private String resolveSessionId(ChatRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return UUID.randomUUID().toString();
        }
        return req.sessionId().trim();
    }
}
