package com.agentflow.rag;

import com.agentflow.common.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/** 文档管理：上传即索引、列表、删除（删登记册 + 向量块 + 原件） */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 对外视图：实体不放出去，字段集跟着前端需要走 */
    public record DocumentView(String id, String filename, String contentType, long sizeBytes,
                               int chunkCount, String strategy, int dimensions,
                               String status, Instant createdAt) {
        static DocumentView from(DocumentEntity e) {
            return new DocumentView(e.getId(), e.getFilename(), e.getContentType(), e.getSizeBytes(),
                    e.getChunkCount(), e.getStrategy(), e.getDimensions(), e.getStatus(), e.getCreatedAt());
        }
    }

    private final RagService rag;

    public DocumentController(RagService rag) {
        this.rag = rag;
    }

    /**
     * 上传并索引。curl 示例：
     * curl -F "file=@demo.pdf" -F "strategy=structural" http://localhost:8080/api/documents
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DocumentView> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "strategy", required = false) String strategy) {
        RagService.UploadView v = rag.ingest(file, strategy);
        return Result.ok(new DocumentView(v.id(), v.filename(), v.contentType(), v.sizeBytes(),
                v.chunkCount(), v.strategy(), v.dimensions(), "INDEXED", v.createdAt()));
    }

    @GetMapping
    public Result<List<DocumentView>> list() {
        return Result.ok(rag.listDocuments().stream().map(DocumentView::from).toList());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        rag.delete(id);
        return Result.ok();
    }
}
