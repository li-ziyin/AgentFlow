package com.agentflow.chat;

import com.agentflow.common.Result;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话历史接口：给聊天页侧边栏用。
 * GET /api/sessions           会话列表（预览 + 条数 + 最后活跃时间，最近活跃在前）
 * GET /api/sessions/{id}/messages   某会话的全部消息（回放用）
 *
 * 预览 = 每个会话首条消息的前 24 个字符。这里对每个会话多查一次首条消息，
 * 属于教科书式的 N+1 查询——个人项目规模下可接受，数据量大时要合并成一条 SQL（面试可聊）。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final int PREVIEW_LENGTH = 24;

    private final ChatMessageRepository repo;

    public SessionController(ChatMessageRepository repo) {
        this.repo = repo;
    }

    public record SessionView(String sessionId, String preview, long messageCount, Instant lastAt) {
    }

    public record MessageView(String role, String content) {
    }

    @GetMapping
    public Result<List<SessionView>> list() {
        List<SessionView> views = repo.summarizeSessions().stream()
                .map(s -> new SessionView(
                        s.getSessionId(),
                        preview(s.getSessionId()),
                        s.getMessageCount(),
                        s.getLastAt()))
                .toList();
        return Result.ok(views);
    }

    @GetMapping("/{sessionId}/messages")
    public Result<List<MessageView>> messages(@PathVariable String sessionId) {
        List<MessageView> views = repo.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(m -> new MessageView(m.getRole(), m.getContent()))
                .toList();
        return Result.ok(views);
    }

    private String preview(String sessionId) {
        ChatMessageEntity first = repo.findFirstBySessionIdOrderByIdAsc(sessionId);
        if (first == null) {
            return "（空会话）";
        }
        String text = first.getContent().replaceAll("\\s+", " ").trim();
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH) + "…";
    }
}
