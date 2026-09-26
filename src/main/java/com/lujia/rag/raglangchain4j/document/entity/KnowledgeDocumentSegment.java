package com.lujia.rag.raglangchain4j.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档分片实体
 * <p>对应数据库表 {@code knowledge_document_segment}，存储文档分割后的每个文本片段</p>
 * <p>每个分片包含原文内容、元数据、向量信息等，用于后续的检索和问答</p>
 * <p>分片状态流转：</p>
 * <pre>
 * STORED → VECTOR_STORED
 * </pre>
 * <ul>
 *   <li>STORED：分片已存储，等待向量化</li>
 *   <li>VECTOR_STORED：已完成向量化存储</li>
 * </ul>
 */
@Data
@TableName("knowledge_document_segment")
public class KnowledgeDocumentSegment {

    /**
     * 片段ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文本内容
     */
    private String text;

    /**
     * 分片唯一标识，由雪花算法生成
     */
    private String chunkId;

    /**
     * 元数据，JSON格式存储，包含标题层级、父子关系等信息
     *
     * @see com.lujia.rag.raglangchain4j.document.constant.DocumentConstant
     */
    private String metadata;

    /**
     * 所属文档ID
     */
    private Long documentId;

    /**
     * 分片在文档中的顺序号，从1开始
     */
    private Integer chunkOrder;

    /**
     * 向量存储ID，用于关联向量数据库中的记录
     */
    private String embeddingId;

    /**
     * 分片状态：
     * <ul>
     *   <li>STORED - 已存储，等待向量化</li>
     *   <li>VECTOR_STORED - 已完成向量化</li>
     * </ul>
     */
    private String status;

    /**
     * 是否跳过向量化处理：0-需要向量化，1-跳过
     */
    private Integer skipEmbedding;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 修改时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 是否删除：0-未删除，1-已删除
     */
    @TableLogic
    private Integer deleted;
}
