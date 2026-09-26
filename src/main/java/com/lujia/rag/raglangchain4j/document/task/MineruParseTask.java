package com.lujia.rag.raglangchain4j.document.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.service.MineruService;
import com.lujia.rag.raglangchain4j.document.service.MinioService;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MinerU 解析任务定时轮询
 * <p>定时查询 CONVERTING 状态的文档，轮询 MinerU 解析结果</p>
 * <p>解析完成后发布 DocumentEvent(CONVERTED) 驱动后续处理流程</p>
 */
@Slf4j
@Component
public class MineruParseTask {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    @Autowired
    private MineruService mineruService;

    @Autowired
    private MinioService minioService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 定时查询 CONVERTING 状态的文档，轮询 MinerU 解析结果
     * <p>每 30 秒执行一次</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void pollMineruParseResult() {
        // 查询所有正在转换中的文档
        LambdaQueryWrapper<RagKnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagKnowledgeDocument::getStatus, "CONVERTING");
        List<RagKnowledgeDocument> documents = documentService.list(wrapper);

        if (documents.isEmpty()) {
            return;
        }

        log.info("MinerU 定时任务: 发现 {} 个待解析文档", documents.size());

        for (RagKnowledgeDocument document : documents) {
            try {
                pollSingleDocument(document);
            } catch (Exception e) {
                log.error("MinerU 定时任务处理文档 {} 失败", document.getId(), e);
            }
        }
    }

    private void pollSingleDocument(RagKnowledgeDocument document) {
        String taskId = document.getMineruTaskId();
        if (taskId == null || taskId.isEmpty()) {
            log.warn("文档 {} 没有 MinerU 任务ID", document.getId());
            return;
        }

        Map<String, String> result = mineruService.queryTaskResult(taskId);
        String state = result.get("state");
        log.info("文档 {} 的 MinerU 任务 {} 状态: {}", document.getId(), taskId, state);

        switch (state) {
            case "done" -> handleTaskDone(document, result);
            case "failed" -> handleTaskFailed(document, result);
            // pending / running / converting - 继续等待
            default -> log.info("文档 {} 的 MinerU 任务仍在处理中: {}", document.getId(), state);
        }
    }

    private void handleTaskDone(RagKnowledgeDocument document, Map<String, String> result) {
        String zipUrl = result.get("full_zip_url");
        if (zipUrl == null || zipUrl.isEmpty()) {
            log.error("文档 {} 解析完成但没有 full_zip_url", document.getId());
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, "解析完成但没有 full_zip_url"));
            return;
        }

        // 下载 zip 文件并上传到 MinIO
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = datePath + "/" + UUID.randomUUID().toString().replace("-", "") + ".zip";
        String parseResultUrl;
        try (InputStream zipInputStream = mineruService.downloadZipFile(zipUrl)) {
            parseResultUrl = minioService.uploadFile(zipInputStream, objectName, "application/zip");
        } catch (IOException e) {
            log.error("文档 {} 下载或上传 MinerU 解析结果失败", document.getId(), e);
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, "解析结果下载失败: " + e.getMessage()));
            return;
        }

        // 更新文档
        document.setParseResultUrl(parseResultUrl);
        document.setStatus("CONVERTED");
        documentService.updateById(document);
        log.info("文档 {} 解析完成, 解析结果: {}", document.getId(), parseResultUrl);

        // 发布转换完成事件，驱动后续图片处理和文档切分
        eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CONVERTED, document));
    }

    private void handleTaskFailed(RagKnowledgeDocument document, Map<String, String> result) {
        String errMsg = result.getOrDefault("err_msg", "未知错误");
        log.error("文档 {} 的 MinerU 任务失败: {}", document.getId(), errMsg);
        eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.FAILED, document, errMsg));
    }
}
