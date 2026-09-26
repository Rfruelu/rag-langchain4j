package com.lujia.rag.raglangchain4j.document.listener;

import com.lujia.rag.raglangchain4j.common.metrics.RagMetrics;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.service.*;
import com.lujia.rag.raglangchain4j.document.util.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 文档处理事件监听器
 * <p>监听文档生命周期中的各类事件，驱动状态流转和后续处理流程</p>
 * <p>所有监听方法通过 @Async 注解在 documentTaskExecutor 线程池中异步执行，不阻塞事件发布线程</p>
 *
 * <p>事件流转链路：</p>
 * <pre>
 * UPLOADED (PDF)
 *   → 创建 MinerU 任务 → 状态变为 CONVERTING
 *   → MineruParseTask 轮询完成 → 发布 CONVERTED
 *
 * UPLOADED (非 PDF)
 *   → 状态变为 CONVERTED → 发布 CONVERTED
 *
 * CONVERTED
 *   → 图片处理 + 文档切分 → 发布 CHUNKED
 *
 * CHUNKED
 *   → 状态变为 CHUNKED → 触发向量化存储 → 发布 VECTOR_STORED
 *
 * VECTOR_STORED
 *   → 状态变为 VECTOR_STORED
 *
 * FAILED
 *   → 状态变为 FAILED
 * </pre>
 */
@Slf4j
@Component
public class DocumentEventListener {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    @Autowired
    private MinioService minioService;

    @Autowired
    private MineruService mineruService;

    @Autowired
    private ConvertedDocumentProcessService processService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private RagMetrics ragMetrics;

    /**
     * 处理文档生命周期事件
     * <p>根据事件类型分发到对应的处理逻辑</p>
     */
    @Async("documentTaskExecutor")
    @EventListener
    public void onDocumentEvent(DocumentEvent event) {
        switch (event.getType()) {
            case UPLOADED -> handleDocumentUploaded(event);
            case CONVERTED -> handleDocumentConverted(event);
            case CHUNKED -> handleDocumentChunked(event);
            case VECTOR_STORED -> handleDocumentVectorStored(event);
            case FAILED -> handleDocumentFailed(event);
        }
    }

    /**
     * 处理文档上传完成事件
     * <p>根据文件类型决定后续流程：</p>
     * <ul>
     *   <li>EXCEL/CSV 文件：状态直接变为 CONVERTED，并发布 CONVERTED 事件触发后续处理</li>
     *   <li>其他文件（PDF 等）：创建 MinerU 解析任务，状态变为 CONVERTING</li>
     * </ul>
     */
    private void handleDocumentUploaded(DocumentEvent event) {
        RagKnowledgeDocument document = event.getDocument();
        FileType fileType = FileType.getFileType(document.getFileType());
        log.info("收到文档上传事件 UPLOADED: id={}, fileType={}", document.getId(), fileType);

        try {
            if (fileType.equals(FileType.EXCEL) || fileType.equals(FileType.CSV)) {
                // 非 PDF 文件：直接标记为 CONVERTED 并触发后续处理
                document.setStatus("CONVERTED");
                documentService.updateById(document);

                eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CONVERTED, document));
            } else {
                // PDF 文件：创建 MinerU 解析任务
                String externalFileUrl = document.getFileUrl()
                        .replace(minioService.getInternalEndpoint(), minioService.getExternalEndpoint());
                String taskId = mineruService.createExtractTask(externalFileUrl);
                document.setMineruTaskId(taskId);
                document.setStatus("CONVERTING");
                documentService.updateById(document);
                log.info("MinerU 任务创建成功: docId={}, taskId={}", document.getId(), taskId);
            }
            ragMetrics.recordPipelineStage("uploaded", true);
        } catch (Exception e) {
            log.error("处理文档上传事件失败: docId={}", document.getId(), e);
            ragMetrics.recordPipelineStage("uploaded", false);
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, e.getMessage()));
        }
    }

    /**
     * 处理文档转换完成事件
     * <p>调用 ConvertedDocumentProcessService 执行图片处理、MD 替换、文档切分等后处理流程</p>
     * <p>处理成功后发布 CHUNKED 事件；失败则发布 FAILED 事件</p>
     */
    private void handleDocumentConverted(DocumentEvent event) {
        RagKnowledgeDocument document = event.getDocument();
        log.info("收到文档转换完成事件 CONVERTED: id={}", document.getId());

        try {
            processService.processDocument(document);
            // 重新查询文档（processDocument 可能已更新字段）
            RagKnowledgeDocument updated = documentService.getById(document.getId());
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CHUNKED, updated));
            ragMetrics.recordPipelineStage("converted", true);
        } catch (Exception e) {
            log.error("处理文档转换完成事件失败: docId={}", document.getId(), e);
            ragMetrics.recordPipelineStage("converted", false);
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, e.getMessage()));
        }
    }

    /**
     * 处理文档切分完成事件
     * <p>将文档状态更新为 CHUNKED，并触发向量化存储</p>
     * <p>向量化完成后发布 VECTOR_STORED 事件</p>
     */
    private void handleDocumentChunked(DocumentEvent event) {
        RagKnowledgeDocument document = event.getDocument();
        log.info("收到文档切分完成事件 CHUNKED: id={}", document.getId());

        try {
            // 更新状态为 CHUNKED
            documentService.updateStatus(document.getId(), "CHUNKED");
            log.info("文档状态已更新为 CHUNKED: id={}", document.getId());

            // 触发向量化存储
            vectorStoreService.vectorizeAndStore(document.getId());

            // 重新查询文档并发布向量化完成事件
            RagKnowledgeDocument updated = documentService.getById(document.getId());
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.VECTOR_STORED, updated));
            ragMetrics.recordPipelineStage("chunked", true);
        } catch (Exception e) {
            log.error("处理文档切分完成事件失败: docId={}", document.getId(), e);
            ragMetrics.recordPipelineStage("chunked", false);
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, e.getMessage()));
        }
    }

    /**
     * 处理文档向量化存储完成事件
     * <p>将文档状态更新为 VECTOR_STORED</p>
     */
    private void handleDocumentVectorStored(DocumentEvent event) {
        RagKnowledgeDocument document = event.getDocument();
        log.info("收到向量化存储完成事件 VECTOR_STORED: id={}", document.getId());

        try {
            documentService.updateStatus(document.getId(), "VECTOR_STORED");
            log.info("文档状态已更新为 VECTOR_STORED: id={}", document.getId());
            ragMetrics.recordPipelineStage("vector_stored", true);
        } catch (Exception e) {
            log.error("处理向量化存储完成事件失败: docId={}", document.getId(), e);
            ragMetrics.recordPipelineStage("vector_stored", false);
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, e.getMessage()));
        }
    }

    /**
     * 处理文档失败事件
     * <p>将文档状态更新为 FAILED</p>
     */
    private void handleDocumentFailed(DocumentEvent event) {
        RagKnowledgeDocument document = event.getDocument();
        log.info("收到文档失败事件 FAILED: id={}, error={}", document.getId(), event.getErrorMessage());

        try {
            documentService.updateStatus(document.getId(), "FAILED");
            log.info("文档状态已更新为 FAILED: id={}", document.getId());
        } catch (Exception e) {
            log.error("处理文档失败事件失败: docId={}", document.getId(), e);
        }
    }
}
