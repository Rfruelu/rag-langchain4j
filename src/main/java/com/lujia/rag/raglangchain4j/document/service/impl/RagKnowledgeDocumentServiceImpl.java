package com.lujia.rag.raglangchain4j.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.event.DocumentEvent;
import com.lujia.rag.raglangchain4j.document.mapper.RagKnowledgeDocumentMapper;
import com.lujia.rag.raglangchain4j.document.service.KnowledgeDocumentSegmentService;
import com.lujia.rag.raglangchain4j.document.service.MineruService;
import com.lujia.rag.raglangchain4j.document.service.MinioService;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import com.lujia.rag.raglangchain4j.document.util.FileTypeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * rag知识文档 ServiceImpl
 * <p>封装文档上传、删除、分页查询等核心业务逻辑，并通过 Spring Event 驱动后续异步处理流程</p>
 */
@Slf4j
@Service
public class RagKnowledgeDocumentServiceImpl extends ServiceImpl<RagKnowledgeDocumentMapper, RagKnowledgeDocument>
        implements RagKnowledgeDocumentService {

    @Autowired
    private MinioService minioService;

    @Autowired
    private MineruService mineruService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private KnowledgeDocumentSegmentService segmentService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 上传文档
     * <p>处理流程：上传文件到 MinIO → 构建文档对象 → 保存记录 → 发布 DocumentEvent(UPLOADED)</p>
     * <p>PDF 文件在事件监听器中创建 MinerU 解析任务；非 PDF 文件直接触发后续处理</p>
     */
    @Override
    public RagKnowledgeDocument uploadDocument(MultipartFile file, String title, String description,
                                               String expireDate, String extension,
                                               Integer chunkSize, Integer overlap, String accessibleBy) {
        // 上传文件到 MinIO
        String fileUrl = minioService.uploadFile(file);

        // 提取文件类型
        String originalFilename = file.getOriginalFilename();
        String fileType  = FileTypeUtil.getFileType(originalFilename, file).getType();

        // 构建文档对象
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setTitle(title != null ? title : file.getOriginalFilename());
        document.setFileUrl(fileUrl);
        document.setFileType(fileType);
        document.setDescription(description);
        document.setExtension(extension);
        document.setChunkSize(chunkSize != null ? chunkSize : 500);
        document.setOverlap(overlap != null ? overlap : 50);
        // 设置文档访问权限，默认 STAFF（仅客服可访问）
        document.setAccessibleBy(accessibleBy != null ? accessibleBy : "STAFF");
        if (StringUtils.hasText(expireDate)) {
            document.setExpireDate(java.time.LocalDate.parse(expireDate));
        }

        // 初始状态为 UPLOADED，由事件监听器驱动后续流程
        document.setStatus("UPLOADED");
        save(document);

        // 发布上传完成事件；线程池满载拒绝时记录错误并将文档标记为失败
        log.info("文档上传完成，发布 DocumentEvent(UPLOADED): id={}, fileType={}", document.getId(), fileType);
        try {
            eventPublisher.publishEvent(new DocumentEvent(this, DocumentEvent.Type.UPLOADED, document));
        } catch (RejectedExecutionException e) {
            log.error("文档事件线程池已满，文档 {} 上传事件被拒绝，标记为 FAILED", document.getId(), e);
            updateStatus(document.getId(), "FAILED");
        }

        return document;
    }

    /**
     * 删除文档
     * <p>级联清理 MinIO 文件、ES 向量数据、分片记录</p>
     */
    @Override
    public void deleteDocument(Long id) {
        RagKnowledgeDocument doc = getById(id);
        if (doc == null) {
            return;
        }

        // 1. 删除 MinIO 文件
        if (doc.getFileUrl() != null) {
            try {
                minioService.deleteFile(doc.getFileUrl());
            } catch (Exception e) {
                log.warn("删除 MinIO 文件失败: docId={}, url={}", id, doc.getFileUrl(), e);
            }
        }

        // 2. 删除 ES 向量数据
        try {
            vectorStoreService.deleteByDocumentId(id);
        } catch (Exception e) {
            log.warn("删除 ES 向量数据失败: docId={}", id, e);
        }

        // 3. 删除分片记录
        segmentService.remove(new LambdaQueryWrapper<KnowledgeDocumentSegment>()
                .eq(KnowledgeDocumentSegment::getDocumentId, id));

        // 4. 删除文档记录
        removeById(id);
        log.info("文档删除完成: docId={}", id);
    }

    /**
     * 批量删除文档
     * <p>级联清理 MinIO 文件、ES 向量数据、分片记录</p>
     */
    @Override
    public void deleteBatchDocuments(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        List<RagKnowledgeDocument> docs = listByIds(ids);
        if (docs.isEmpty()) {
            return;
        }

        // 1. 批量删除 MinIO 文件（MinioService.deleteFiles 内部自行记录失败日志）
        List<String> fileUrls = docs.stream()
                .map(RagKnowledgeDocument::getFileUrl)
                .filter(Objects::nonNull)
                .toList();
        try {
            minioService.deleteFiles(fileUrls);
        } catch (Exception e) {
            log.warn("批量删除 - MinIO 文件失败: docIds={}", ids, e);
        }

        // 2. 一次性删除所有文档的 ES 向量数据
        try {
            vectorStoreService.deleteByDocumentIds(ids);
        } catch (Exception e) {
            log.warn("批量删除 - ES 向量数据失败: docIds={}", ids, e);
        }

        // 3. 批量删除分片记录
        segmentService.remove(new LambdaQueryWrapper<KnowledgeDocumentSegment>()
                .in(KnowledgeDocumentSegment::getDocumentId, ids));

        // 4. 批量删除文档记录
        removeByIds(ids);
        log.info("批量删除文档完成: count={}", ids.size());
    }

    /**
     * 分页查询文档
     */
    @Override
    public Page<RagKnowledgeDocument> pageDocuments(Integer pageNum, Integer pageSize,
                                                    String title, String status) {
        LambdaQueryWrapper<RagKnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(title), RagKnowledgeDocument::getTitle, title);
        wrapper.eq(StringUtils.hasText(status), RagKnowledgeDocument::getStatus, status);
        wrapper.orderByDesc(RagKnowledgeDocument::getCreatedTime);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 更新文档状态
     */
    @Override
    public void updateStatus(Long documentId, String status) {
        RagKnowledgeDocument document = getById(documentId);
        if (document != null) {
            document.setStatus(status);
            updateById(document);
        }
    }
}
