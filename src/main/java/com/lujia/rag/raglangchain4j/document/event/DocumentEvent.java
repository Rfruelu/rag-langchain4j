package com.lujia.rag.raglangchain4j.document.event;

import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import org.springframework.context.ApplicationEvent;

/**
 * 文档生命周期事件
 * <p>统一承载文档处理流程中的各类事件，由 {@link Type} 区分事件种类</p>
 * <p>事件流转链路：</p>
 * <pre>
 * UPLOADED  → 上传完成：PDF 创建 MinerU 任务（CONVERTING）；非 PDF 直接转 CONVERTED 并触发 CONVERTED
 * CONVERTED → 转换完成：图片处理 + 文档切分，完成后触发 CHUNKED
 * CHUNKED   → 切分完成：触发向量化存储，完成后触发 VECTOR_STORED
 * VECTOR_STORED → 向量化完成：状态流转结束
 * FAILED    → 任意环节失败：状态更新为 FAILED
 * </pre>
 */
public class DocumentEvent extends ApplicationEvent {

    public enum Type {
        /** 文档上传完成 */
        UPLOADED,
        /** 文档转换完成 */
        CONVERTED,
        /** 文档切分完成 */
        CHUNKED,
        /** 文档向量化存储完成 */
        VECTOR_STORED,
        /** 文档处理失败 */
        FAILED
    }

    private final Type type;
    private final RagKnowledgeDocument document;
    private final String errorMessage;

    public DocumentEvent(Object source, Type type, RagKnowledgeDocument document) {
        this(source, type, document, null);
    }

    public DocumentEvent(Object source, Type type, RagKnowledgeDocument document, String errorMessage) {
        super(source);
        this.type = type;
        this.document = document;
        this.errorMessage = errorMessage;
    }

    public Type getType() {
        return type;
    }

    public RagKnowledgeDocument getDocument() {
        return document;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
