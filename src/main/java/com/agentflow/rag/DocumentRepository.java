package com.agentflow.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 查询全部走命名查询/参数绑定，不拼 SQL 字符串——元数据查询的安全底线 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();
}
