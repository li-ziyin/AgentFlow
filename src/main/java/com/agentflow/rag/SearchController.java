package com.agentflow.rag;

import com.agentflow.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 纯检索端点：不带 LLM，评测脚本算 Recall@5 / MRR@5 就打这里 */
@RestController
@RequestMapping("/api/rag")
public class SearchController {

    public record SearchRequest(@NotBlank String query, Integer topK) {
    }

    private final RagService rag;

    public SearchController(RagService rag) {
        this.rag = rag;
    }

    @PostMapping("/search")
    public Result<List<SearchHit>> search(@Valid @RequestBody SearchRequest req) {
        return Result.ok(rag.search(req.query(), req.topK()));
    }
}
