package com.lujia.rag.raglangchain4j.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * rag知识文档 Service
 */
public interface RagKnowledgeDocumentService extends IService<RagKnowledgeDocument> {

    /**
     * 上传文档
     * <p>上传文件到 MinIO，保存文档记录，发布 DocumentEvent(UPLOADED) 驱动后续流程</p>
     *
     * @param file         上传的文件
     * @param title        文档标题（可选，默认使用文件名）
     * @param description  文档描述（可选）
     * @param expireDate   过期日期（可选，格式 yyyy-MM-dd）
     * @param extension    文件扩展名（可选）
     * @param chunkSize    文档分片大小（可选，默认500）
     * @param overlap      相邻分块重叠字符数（可选，默认50）
     * @param accessibleBy 文档访问权限（可选，默认STAFF）：VISITOR-所有人, CUSTOMER-外部客户及以上, STAFF-仅客服
     * @return 保存后的文档记录
     */
    RagKnowledgeDocument uploadDocument(MultipartFile file, String title, String description,
                                        String expireDate, String extension,
                                        Integer chunkSize, Integer overlap, String accessibleBy);

    /**
     * 删除文档
     * <p>同时清理 MinIO 文件、ES 向量数据、分片记录</p>
     *
     * @param id 文档ID
     */
    void deleteDocument(Long id);

    /**
     * 批量删除文档
     * <p>同时清理 MinIO 文件、ES 向量数据、分片记录</p>
     *
     * @param ids 文档ID列表
     */
    void deleteBatchDocuments(List<Long> ids);

    /**
     * 分页查询文档
     *
     * @param pageNum  页码（默认1）
     * @param pageSize 每页数量（默认10）
     * @param title    文档标题（模糊查询，可选）
     * @param status   文档状态（精确查询，可选）
     * @return 分页结果
     */
    Page<RagKnowledgeDocument> pageDocuments(Integer pageNum, Integer pageSize,
                                             String title, String status);

    /**
     * 更新文档状态
     *
     * @param documentId 文档ID
     * @param status     新状态
     */
    void updateStatus(Long documentId, String status);
}
