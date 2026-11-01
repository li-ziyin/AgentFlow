package com.agentflow.rag;

import com.agentflow.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * RAG 编排：上传 = 存原件 → Tika 解析 → 分块 → 批量 embedding → 入向量库 → 落登记册。
 * 每一步的失败都以 BizException 收场并中止（半成品不入库）：
 * 向量库与登记册在同一方法内先后写入，失败路径上最坏情况是
 * "原件已存但未入库"——不产生检索脏数据，重传一次即可。
 */
@Component
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_TOP_K = 20;

    private final DocumentParser parser;
    private final Chunker structural;
    private final Chunker fixed;
    private final ZhipuEmbeddingClient embedding;
    private final VectorStore vectorStore;
    private final DocumentRepository repo;
    private final RagProperties props;

    public RagService(DocumentParser parser, StructuralChunker structural, FixedWindowChunker fixed,
                      ZhipuEmbeddingClient embedding, VectorStore vectorStore,
                      DocumentRepository repo, RagProperties props) {
        this.parser = parser;
        this.structural = structural;
        this.fixed = fixed;
        this.embedding = embedding;
        this.vectorStore = vectorStore;
        this.repo = repo;
        this.props = props;
    }

    public record UploadView(String id, String filename, String contentType, long sizeBytes, int chunkCount,
                             String strategy, int dimensions, java.time.Instant createdAt) {
    }

    /** 上传 → 索引。strategy 缺省用配置值；显式传 structural/fixed 供评测切换 */
    public UploadView ingest(MultipartFile file, String strategy) {
        String filename = cleanFilename(file);
        if (file.getSize() <= 0) {
            throw new BizException("收到空文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException("文件超过 20MB 上限");
        }
        String s = resolveStrategy(strategy);
        String docId = UUID.randomUUID().toString();

        Path saved = saveOriginal(file, docId, filename);
        DocumentParser.ParsedDocument parsed;
        try (InputStream in = Files.newInputStream(saved)) {
            parsed = parser.parse(in, filename);
        } catch (IOException e) {
            throw new BizException("读取已保存的原件失败：" + e.getMessage());
        }

        List<Chunk> chunks = chunker(s).chunk(parsed.text());
        if (chunks.isEmpty()) {
            throw new BizException("分块结果为空，文档没有有效内容");
        }
        log.info("ingest docId=[{}] file=[{}] parsed {} chars -> {} chunks (strategy={})",
                docId, filename, parsed.text().length(), chunks.size(), s);

        List<float[]> vectors = embedding.embedAll(chunks.stream().map(Chunk::text).toList());
        vectorStore.replaceDocument(docId, filename, chunks, vectors);

        DocumentEntity e = new DocumentEntity(docId, filename, parsed.contentType(),
                file.getSize(), chunks.size(), s, props.embedding().dimensions(), "INDEXED");
        repo.save(e);
        return new UploadView(e.getId(), e.getFilename(), e.getContentType(), e.getSizeBytes(),
                e.getChunkCount(), e.getStrategy(), e.getDimensions(), e.getCreatedAt());
    }

    /** 检索：问题向量化 → top-K。评测脚本（Recall@5/MRR@5）直接消费这个方法 */
    public List<SearchHit> search(String query, Integer topKParam) {
        if (query == null || query.isBlank()) {
            throw new BizException("query 不能为空");
        }
        int topK = topKParam == null ? props.topK() : Math.min(Math.max(topKParam, 1), MAX_TOP_K);
        List<SearchHit> hits = vectorStore.search(embedding.embedOne(query), topK);
        // 展示给用户的列表按得分降序；同分时先来后到，保证幂等
        return hits.stream().sorted(Comparator.comparingDouble(SearchHit::score).reversed()).toList();
    }

    public void delete(String docId) {
        DocumentEntity e = repo.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在：" + docId));
        vectorStore.deleteDocument(docId);
        repo.delete(e);
        deleteQuietly(props.dataDir() + "/uploads/" + docId);
    }

    public List<DocumentEntity> listDocuments() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    private Chunker chunker(String strategy) {
        return "fixed".equalsIgnoreCase(strategy) ? fixed : structural;
    }

    private String resolveStrategy(String param) {
        String s = param == null || param.isBlank() ? props.chunking().strategy() : param;
        return switch (s.toLowerCase()) {
            case "structural", "fixed" -> s.toLowerCase();
            default -> throw new BizException("未知分块策略 " + s + "（可选 structural / fixed）");
        };
    }

    private String cleanFilename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        // 浏览器可能带路径（旧版 IE），取最后一段
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).strip();
        return name.isEmpty() ? "unnamed" : name;
    }

    private Path saveOriginal(MultipartFile file, String docId, String filename) {
        try {
            Path dir = Path.of(props.dataDir(), "uploads", docId);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new BizException("保存上传原件失败：" + e.getMessage());
        }
    }

    private void deleteQuietly(String dir) {
        try (var paths = Files.walk(Path.of(dir))) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程，下次删除同 docId 时再试
                }
            });
        } catch (IOException ignored) {
            // 目录本来就不存在等情形
        }
    }
}
