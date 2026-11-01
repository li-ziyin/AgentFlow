package com.agentflow.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * rag_document 表 = 文档登记册：一份上传文档的元信息（不含正文和向量）。
 *
 * 职责划分：MySQL 管登记（可审计、可列表）；向量+块文本在向量库（检索主链路
 * 一次查询拿齐，不用跨库拼装）；上传原件落盘 data/uploads/（Redis/向量库损坏时
 * 可以用原件重新索引，这是"可重建"的底气）。
 *
 * 惯例与 ChatMessageEntity 一致：DTO 用 record，实体用普通类（JPA 需要无参构造 + 可变）。
 */
@Entity
@Table(name = "rag_document")
public class DocumentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** 分块策略：structural / fixed（评测对比用的记录字段） */
    @Column(name = "chunk_strategy", nullable = false, length = 20)
    private String strategy;

    @Column(nullable = false)
    private int dimensions;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DocumentEntity() {
    }

    public DocumentEntity(String id, String filename, String contentType, long sizeBytes,
                          int chunkCount, String strategy, int dimensions, String status) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.chunkCount = chunkCount;
        this.strategy = strategy;
        this.dimensions = dimensions;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getStrategy() {
        return strategy;
    }

    public int getDimensions() {
        return dimensions;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
