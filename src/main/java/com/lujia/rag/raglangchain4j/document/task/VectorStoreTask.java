package com.lujia.rag.raglangchain4j.document.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化存储兜底定时任务
 * <p>作为事件驱动机制的兜底方案，定时扫描可能因系统重启、事件丢失等原因
 * 而停留在 CHUNKED 状态的文档，确保所有文档都能完成向量化存储</p>
 *
 * <p>正常情况下，文档切分完成后会通过 DocumentEvent(CHUNKED) 事件触发向量化。
 * 本任务仅处理异常情况下遗留的文档。</p>
 *
 * @see VectorStoreService
 */
@Slf4j
@Component
public class VectorStoreTask {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 兜底扫描 CHUNKED 状态的文档并执行向量化存储
     * <p>每 5 分钟执行一次，固定延迟。主要处理因异常情况遗留的文档</p>
     */
    @Scheduled(fixedDelay = 300000)
    public void processVectorStore() {
        // 查询所有 CHUNKED 状态的文档（已切分但未向量化）
        LambdaQueryWrapper<RagKnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagKnowledgeDocument::getStatus, "CHUNKED");
        List<RagKnowledgeDocument> documents = documentService.list(wrapper);

        if (documents.isEmpty()) {
            return;
        }

        log.info("【兜底任务】发现 {} 个 CHUNKED 状态文档需要向量化", documents.size());

        for (RagKnowledgeDocument document : documents) {
            try {
                log.info("【兜底任务】向量化文档: id={}", document.getId());
                vectorStoreService.vectorizeAndStore(document.getId());
                // 更新文档状态为 VECTOR_STORED
                documentService.updateStatus(document.getId(), "VECTOR_STORED");
                // 发布 VECTOR_STORED 事件
                RagKnowledgeDocument updated = documentService.getById(document.getId());
                eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.VECTOR_STORED, updated));
                log.info("【兜底任务】文档向量化完成，已发布 VECTOR_STORED 事件: id={}", document.getId());
            } catch (Exception e) {
                log.error("【兜底任务】向量化文档 {} 失败", document.getId(), e);
                // 不更新状态，下次定时任务会重试
            }
        }
    }
}
