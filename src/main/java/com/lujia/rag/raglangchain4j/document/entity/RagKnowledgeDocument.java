package com.lujia.rag.raglangchain4j.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * RAG 知识文档实体
 * <p>对应数据库表 {@code rag_knowledge_document}，存储上传的知识文档元数据</p>
 * <p>文档状态流转：</p>
 * <pre>
 * INIT → UPLOADED → CONVERTING → CONVERTED → CHUNKED → VECTOR_STORED
 * </pre>
 * <ul>
 *   <li>INIT：初始状态，文档记录已创建</li>
 *   <li>UPLOADED：文件已上传至 MinIO</li>
 *   <li>CONVERTING：MinerU 正在解析文档</li>
 *   <li>CONVERTED：解析完成，生成 Markdown 结果</li>
 *   <li>CHUNKED：文档已完成分片</li>
 *   <li>VECTOR_STORED：分片已向量化存储</li>
 * </ul>
 */
@Data
@TableName("rag_knowledge_document")
public class RagKnowledgeDocument {

    /**
     * 文档ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文件URL地址（MinIO存储路径）
     */
    private String fileUrl;

    /**
     * 文件类型（如：pdf、docx、pptx、xlsx、png、jpg 等）
     */
    private String fileType;

    /**
     * 文档失效日期
     */
    private LocalDate expireDate;

    /**
     * 状态枚举值：
     * <ul>
     *   <li>INIT - 初始状态</li>
     *   <li>UPLOADED - 已上传</li>
     *   <li>CONVERTING - 转换中</li>
     *   <li>CONVERTED - 已转换</li>
     *   <li>CHUNKED - 已分片</li>
     *   <li>VECTOR_STORED - 已向量化</li>
     * </ul>
     */
    private String status;

    /**
     * 文档访问权限：VISITOR-所有人, CUSTOMER-外部客户及以上, STAFF-仅客服
     */
    private String accessibleBy;

    /**
     * 文档描述
     */
    private String description;

    /**
     * 扩展字段，保存JSON字符串
     */
    private String extension;

    /**
     * MinerU解析任务ID
     */
    private String mineruTaskId;

    /**
     * 解析结果文件地址（MinIO存储路径）
     */
    private String parseResultUrl;

    /**
     * 图片处理后的 Markdown 文件地址（MinIO存储路径）
     * <p>图片已上传至 MinIO，图片描述已由百炼大模型识别并替换</p>
     */
    private String processedMdUrl;

    /**
     * 文档分片大小（每个分块的最大字符数），默认 500
     */
    private Integer chunkSize;

    /**
     * 相邻分块之间的重叠字符数，用于保留上下文连贯性，默认 50
     */
    private Integer overlap;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    /**
     * 修改时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    /**
     * 是否删除：0-未删除，1-已删除
     */
    @TableLogic
    private Integer deleted;
}
