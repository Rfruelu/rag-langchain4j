package com.lujia.rag.raglangchain4j.document.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.service.ConvertedDocumentProcessService;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 已转换文档后处理兜底定时任务
 * <p>作为事件驱动机制的兜底方案，定时扫描可能因系统重启、事件丢失等原因
 * 而停留在 CONVERTED 状态的文档，确保所有文档都能被处理</p>
 *
 * <p>正常情况下，文档转换完成后会通过 DocumentEvent(CONVERTED) 事件驱动后续处理。
 * 本任务仅处理异常情况下遗留的文档。</p>
 *
 * @see ConvertedDocumentProcessService
 */
@Slf4j
@Component
public class ConvertedDocumentProcessTask {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    @Autowired
    private ConvertedDocumentProcessService processService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 兜底扫描 CONVERTED 状态的文档并处理后置流程
     * <p>每 5 分钟执行一次，固定延迟。主要处理因异常情况遗留的文档</p>
     */
    @Scheduled(fixedDelay = 300000)
    public void processConvertedDocuments() {
        // 查询所有 CONVERTED 状态的文档
        LambdaQueryWrapper<RagKnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagKnowledgeDocument::getStatus, "CONVERTED");
        List<RagKnowledgeDocument> documents = documentService.list(wrapper);

        if (documents.isEmpty()) {
            return;
        }

        log.info("【兜底任务】发现 {} 个 CONVERTED 状态文档需要处理", documents.size());

        for (RagKnowledgeDocument document : documents) {
            try {
                log.info("【兜底任务】处理文档: id={}", document.getId());
                processService.processDocument(document);
                // 发布 CHUNKED 事件触发后续向量化流程
                RagKnowledgeDocument updated = documentService.getById(document.getId());
                eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CHUNKED, updated));
                log.info("【兜底任务】文档处理完成，已发布 CHUNKED 事件: id={}", document.getId());
            } catch (Exception e) {
                log.error("【兜底任务】处理文档 {} 失败", document.getId(), e);
                // 不更新状态，下次定时任务会重试
            }
        }
    }
}
