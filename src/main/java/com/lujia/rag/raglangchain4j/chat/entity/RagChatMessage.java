package com.lujia.rag.raglangchain4j.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI对话消息实体
 * <p>对应数据库表 {@code rag_chat_message}，存储对话中的每条消息</p>
 */
@Data
@TableName("rag_chat_message")
public class RagChatMessage {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一标识
     */
    private String messageId;

    /**
     * 所属会话ID
     */
    private String conversationId;

    /**
     * 消息类型：USER-用户消息，ASSISTANT-助手消息
     */
    private String type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 改写后的内容（用于 RAG 检索优化）
     */
    private String transformContent;

    /**
     * Token 数量
     */
    private Integer tokenCount;

    /**
     * 使用的模型名称
     */
    private String modelName;

    /**
     * RAG 引用内容 JSON 数组
     */
    private String ragReferences;

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

    /**
     * 扩展元数据 JSON 格式
     */
    private String metadata;
}
