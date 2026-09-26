package com.lujia.rag.raglangchain4j.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.service.RagKnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * rag知识文档 Controller
 * <p>仅负责接收请求参数和调用 Service 层，不包含业务逻辑</p>
 */
@RestController
@RequestMapping("/api/documents")
public class RagKnowledgeDocumentController {

    @Autowired
    private RagKnowledgeDocumentService documentService;

    /**
     * 上传文件并新增文档
     */
    @PostMapping("/upload")
    public RagKnowledgeDocument upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "expireDate", required = false) String expireDate,
            @RequestParam(value = "extension", required = false) String extension,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap,
            @RequestParam(value = "accessibleBy", required = false, defaultValue = "STAFF") String accessibleBy) {
        return documentService.uploadDocument(file, title, description, expireDate, extension, chunkSize, overlap, accessibleBy);
    }

    /**
     * 新增文档
     */
    @PostMapping
    public RagKnowledgeDocument add(@RequestBody RagKnowledgeDocument document) {
        documentService.save(document);
        return document;
    }

    /**
     * 根据ID删除文档（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return true;
    }

    /**
     * 批量删除文档
     */
    @DeleteMapping("/batch")
    public boolean deleteBatch(@RequestBody List<Long> ids) {
        documentService.deleteBatchDocuments(ids);
        return true;
    }

    /**
     * 更新文档
     */
    @PutMapping
    public boolean update(@RequestBody RagKnowledgeDocument document) {
        return documentService.updateById(document);
    }

    /**
     * 根据ID查询文档
     */
    @GetMapping("/{id}")
    public RagKnowledgeDocument getById(@PathVariable Long id) {
        return documentService.getById(id);
    }

    /**
     * 查询所有文档
     */
    @GetMapping("/list")
    public List<RagKnowledgeDocument> list() {
        return documentService.list();
    }

    /**
     * 分页查询文档
     *
     * @param pageNum  页码（默认1）
     * @param pageSize 每页数量（默认10）
     * @param title    文档标题（模糊查询，可选）
     * @param status   文档状态（精确查询，可选）
     */
    @GetMapping("/page")
    public Page<RagKnowledgeDocument> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String status) {
        return documentService.pageDocuments(pageNum, pageSize, title, status);
    }
}
