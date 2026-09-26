package com.lujia.rag.raglangchain4j.document.controller;

import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.service.MinioService;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档处理测试接口
 * <p>用于测试完整的文档处理流程：上传 → 切分 → 向量化</p>
 * <p>注意：此接口仅用于测试，生产环境请使用标准的 /api/documents/upload 接口</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class DocumentTestController {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    @Autowired
    private MinioService minioService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private VectorStoreService vectorStoreService;

    /**
     * 测试接口：上传文件并直接触发完整处理流程
     * <p>流程：上传文件 → 保存文档 → 发布 DocumentEvent → 触发切分和向量化</p>
     * <p>跳过 MinerU 转换步骤，直接将文件标记为 CONVERTED 并触发后续处理</p>
     *
     * @param file        上传的文件（支持 txt、md 等文本文件）
     * @param title       文档标题（可选）
     * @param description 文档描述（可选）
     * @return 处理结果，包含文档ID和初始状态
     */
    @PostMapping("/upload-and-process")
    public Map<String, Object> uploadAndProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description) {

        log.info("测试接口：开始上传并处理文件: {}", file.getOriginalFilename());

        // 1. 上传文件到 MinIO
        String fileUrl = minioService.uploadFile(file);

        // 2. 提取文件类型
        String originalFilename = file.getOriginalFilename();
        String fileType = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            fileType = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        }

        // 3. 构建文档对象
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setTitle(title != null ? title : file.getOriginalFilename());
        document.setFileUrl(fileUrl);
        document.setFileType(fileType);
        document.setDescription(description);
        document.setStatus("CONVERTED"); // 直接设置为 CONVERTED，跳过 MinerU 转换

        // 4. 保存文档
        documentService.save(document);
        log.info("测试接口：文档已保存, id={}", document.getId());

        // 5. 发布 DocumentEvent，触发后续处理（切分 + 向量化）
        // 注意：事件监听器是异步执行的，这里会立即返回
        eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CONVERTED, document));
        log.info("测试接口：已发布 DocumentEvent(CONVERTED)，后续处理将在异步线程中执行");

        // 6. 返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("documentId", document.getId());
        result.put("status", "CONVERTED");
        result.put("message", "文档已上传，处理流程已触发。请通过 /api/test/progress/{id} 查询处理进度。" +
                "预期状态流转：CONVERTED → CHUNKED → VECTOR_STORED");

        return result;
    }

    /**
     * 测试接口：手动触发向量化
     * <p>用于测试已切分文档的向量化流程</p>
     *
     * @param documentId 文档ID
     * @return 处理结果
     */
    @PostMapping("/vectorize/{documentId}")
    public Map<String, Object> manualVectorize(@PathVariable Long documentId) {
        log.info("测试接口：手动触发文档向量化, documentId={}", documentId);

        RagKnowledgeDocument document = documentService.getById(documentId);
        if (document == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "文档不存在: " + documentId);
            return result;
        }

        // 发布 DocumentEvent 触发处理流程
        eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.CONVERTED, document));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("documentId", documentId);
        result.put("message", "向量化流程已触发，请通过 /api/test/progress/{id} 查询进度");

        return result;
    }

    /**
     * 测试接口：查询文档处理进度
     *
     * @param documentId 文档ID
     * @return 文档状态和处理进度
     */
    @GetMapping("/progress/{documentId}")
    public Map<String, Object> getProgress(@PathVariable Long documentId) {
        RagKnowledgeDocument document = documentService.getById(documentId);
        if (document == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "文档不存在: " + documentId);
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("documentId", document.getId());
        result.put("title", document.getTitle());
        result.put("status", document.getStatus());
        result.put("fileUrl", document.getFileUrl());
        result.put("processedMdUrl", document.getProcessedMdUrl());

        // 根据状态提供进度说明
        String progressDesc = switch (document.getStatus()) {
            case "UPLOADED" -> "已上传，等待处理";
            case "CONVERTING" -> "正在转换中（MinerU 解析）";
            case "CONVERTED" -> "已转换，等待切分和向量化";
            case "CHUNKED" -> "已切分，正在向量化";
            case "VECTOR_STORED" -> "处理完成，已向量化存储";
            case "FAILED" -> "处理失败";
            default -> "未知状态";
        };
        result.put("progress", progressDesc);

        return result;
    }

    /**
     * 测试接口：向量检索
     * <p>根据查询文本进行语义相似度搜索，返回最相关的文档分片</p>
     *
     * @param query      查询文本
     * @param maxResults 最大返回结果数（默认 10）
     * @param minScore   最小相似度分数（默认 0.0）
     * @return 检索结果列表
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam("query") String query,
            @RequestParam(value = "maxResults", defaultValue = "10") int maxResults,
            @RequestParam(value = "minScore", defaultValue = "0.0") double minScore) {

        log.info("测试接口：向量检索, query={}, maxResults={}, minScore={}", query, maxResults, minScore);

        try {
            List<VectorStoreService.SearchResult> results = vectorStoreService.vectorSearch(query, maxResults, minScore, null);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("query", query);
            result.put("total", results.size());
            result.put("results", results);

            return result;
        } catch (Exception e) {
            log.error("向量检索失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "检索失败: " + e.getMessage());
            return result;
        }
    }
}
