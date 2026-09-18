package com.agentflow.chat;

import com.agentflow.common.Result;
import com.agentflow.llm.DeepSeekClient;
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

import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final DeepSeekClient llm;
    private final LlmProperties props;

    public ChatController(DeepSeekClient llm, LlmProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public record ChatRequest(@NotBlank String message) {
    }

    /** 1.2 阻塞版：等模型写完一次性返回。体验和流式版对比后你就明白 LLM 为什么必须流式。 */
    @PostMapping
    public Result<String> chat(@Valid @RequestBody ChatRequest req) {
        return Result.ok(llm.chat(req.message()));
    }

    /**
     * 1.3 流式版（SSE）：每段增量以名为 delta 的事件推送，出错发 error 事件，然后正常收尾。
     *
     * 三个值得记住的点：
     * 1. SseEmitter 是 Servlet 异步请求的封装：HTTP 响应不关闭，服务端随时往里 write；
     * 2. 数据在 Reactor 的 IO 线程产生。客户端断开后 send() 会抛 IOException，
     *    这里把它转成异常抛回 Reactor，让它自动 cancel 上游（响应链断开就没人消费了）；
     * 3. onTimeout/onError/onCompletion 里 dispose()：人走了就立刻停掉 LLM 调用，
     *    否则模型在给一个已经离开的连接继续烧 token——流式接口的成本意识。
     */
    @PostMapping("/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(props.timeoutSeconds() * 1000L);

        Disposable subscription = llm.streamChat(req.message()).subscribe(
                delta -> {
                    try {
                        // data() 指定 TEXT_PLAIN：避免字符串被 JSON 序列化器包上一层引号
                        emitter.send(SseEmitter.event().name("delta").data(delta, MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                err -> {
                    // 流已开始，HTTP 状态码改不了了，只能把错误作为事件发进流里。
                    // 同时必须记日志：只发事件不留日志，服务端就零痕迹可查
                    log.error("SSE stream failed, message=[{}]", req.message(), err);
                    try {
                        String msg = err.getMessage() == null ? "unknown error" : err.getMessage();
                        emitter.send(SseEmitter.event().name("error").data(msg, MediaType.TEXT_PLAIN));
                    } catch (IOException ignored) {
                        // 连接已不可用，无需再发
                    }
                    emitter.complete();
                },
                emitter::complete
        );

        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        emitter.onCompletion(subscription::dispose);
        return emitter;
    }
}
