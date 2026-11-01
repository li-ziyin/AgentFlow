package com.agentflow.chat;

import com.agentflow.common.Result;
import com.agentflow.llm.DeepSeekClient;
import com.agentflow.llm.LlmMessage;
import com.agentflow.llm.LlmProperties;
import com.agentflow.rag.RagProperties;
import com.agentflow.rag.RagService;
import com.agentflow.rag.SearchHit;
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

    /**
     * 知识库模式的系统提示：只依据资料回答 + 编号引用，逼模型把"依据"显式写出来。
     * 引用编号对应检索命中的块序号（1 起），与阻塞性响应里的 sources 字段一一对应。
     */
    private static final String RAG_SYSTEM_PROMPT = """
            你是知识库问答助手。请只依据下面的参考资料回答用户问题，并在用到资料的句子末尾 \
            用 [1][2] 这样的编号标注引用了哪条资料。如果资料不足以回答，就明确说 \
            "参考资料中没有相关内容"，不要编造。

            参考资料：
            %s""";

    private final DeepSeekClient llm;
    private final LlmProperties props;
    private final ConversationStore store;
    private final RagService rag;
    private final RagProperties ragProps;
    private final ObjectMapper json;

    public ChatController(DeepSeekClient llm, LlmProperties props, ConversationStore store,
                          RagService rag, RagProperties ragProps, ObjectMapper json) {
        this.llm = llm;
        this.props = props;
        this.store = store;
        this.rag = rag;
        this.ragProps = ragProps;
        this.json = json;
    }

    /**
     * sessionId 可以不传：服务器会生成一个新的并随响应返回，
     * 客户端之后每一轮带上它，就是"同一个对话"。服务器不认识"用户"，只认识会话 id。
     * kbEnabled=true 时走知识库：先检索再回答（RAG），响应带 sources。
     */
    public record ChatRequest(String sessionId, @NotBlank String message, Boolean kbEnabled) {
    }

    public record ChatResponse(String sessionId, String answer, List<SearchHit> sources) {
    }

    /** 1.2 阻塞版 + 1.4 多轮上下文 + 2.6 知识库检索注入。 */
    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest req) {
        String sessionId = resolveSessionId(req);
        List<SearchHit> hits = retrieveIfKb(req);
        String answer = llm.chat(buildMessages(sessionId, req.message(), hits));
        store.append(sessionId,
                new LlmMessage("user", req.message()),
                new LlmMessage("assistant", answer));
        return Result.ok(new ChatResponse(sessionId, answer, hits));
    }

    /**
     * 1.3 流式版 + 1.4 多轮上下文 + 2.6 知识库检索注入。
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

        List<SearchHit> hits = retrieveIfKb(req);
        StringBuilder answer = new StringBuilder();
        List<LlmMessage> messages = buildMessages(sessionId, req.message(), hits);

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

    /** 知识库模式：先检索（无命中则退化为普通对话），命中的块供 prompt 注入与 sources 返回 */
    private List<SearchHit> retrieveIfKb(ChatRequest req) {
        if (!Boolean.TRUE.equals(req.kbEnabled())) {
            return List.of();
        }
        List<SearchHit> hits = rag.search(req.message(), ragProps.topK());
        if (hits.isEmpty()) {
            log.info("KB mode but no vector content, degrade to plain chat");
        }
        return hits;
    }

    /**
     * 组装消息：[系统提示] + 历史 + 本轮用户消息。
     * 普通对话无系统提示；知识库模式把检索块编成 [n] 参考资料，要求模型引用编号。
     * LLM 无状态，"记忆"就是每次把历史全量带上。
     */
    private List<LlmMessage> buildMessages(String sessionId, String userMessage, List<SearchHit> hits) {
        List<LlmMessage> messages = new ArrayList<>();
        if (!hits.isEmpty()) {
            messages.add(new LlmMessage("system", RAG_SYSTEM_PROMPT.formatted(renderContext(hits))));
        }
        messages.addAll(store.history(sessionId));
        messages.add(new LlmMessage("user", userMessage));
        return messages;
    }

    /** 检索块 → "（文件名 第 n 块）正文" 的编号清单，[n] 与 sources 顺序一致 */
    private String renderContext(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append('[').append(i + 1).append("] （").append(h.filename());
            if (h.heading() != null && !h.heading().isBlank()) {
                sb.append(" · ").append(h.heading());
            }
            sb.append("）").append(h.text()).append("\n\n");
        }
        return sb.toString();
    }

    private String resolveSessionId(ChatRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return UUID.randomUUID().toString();
        }
        return req.sessionId().trim();
    }
}
