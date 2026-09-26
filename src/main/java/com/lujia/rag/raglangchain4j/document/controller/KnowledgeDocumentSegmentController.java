package com.lujia.rag.raglangchain4j.document.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.service.KnowledgeDocumentSegmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识片段 Controller
 */
@RestController
@RequestMapping("/api/segments")
public class KnowledgeDocumentSegmentController {

    @Autowired
    private KnowledgeDocumentSegmentService segmentService;

    /**
     * 新增知识片段
     */
    @PostMapping
    public KnowledgeDocumentSegment add(@RequestBody KnowledgeDocumentSegment segment) {
        segmentService.save(segment);
        return segment;
    }

    /**
     * 根据ID删除知识片段（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return segmentService.removeById(id);
    }

    /**
     * 批量删除知识片段
     */
    @DeleteMapping("/batch")
    public boolean deleteBatch(@RequestBody List<Long> ids) {
        return segmentService.removeByIds(ids);
    }

    /**
     * 更新知识片段
     */
    @PutMapping
    public boolean update(@RequestBody KnowledgeDocumentSegment segment) {
        return segmentService.updateById(segment);
    }

    /**
     * 根据ID查询知识片段
     */
    @GetMapping("/{id}")
    public KnowledgeDocumentSegment getById(@PathVariable Long id) {
        return segmentService.getById(id);
    }

    /**
     * 查询指定文档的所有知识片段
     */
    @GetMapping("/list")
    public List<KnowledgeDocumentSegment> listByDocument(@RequestParam Long documentId) {
        LambdaQueryWrapper<KnowledgeDocumentSegment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentSegment::getDocumentId, documentId);
        wrapper.orderByAsc(KnowledgeDocumentSegment::getChunkOrder);
        return segmentService.list(wrapper);
    }

    /**
     * 分页查询知识片段
     *
     * @param pageNum    页码（默认1）
     * @param pageSize   每页数量（默认10）
     * @param documentId 文档ID（可选）
     * @param status     状态（可选）
     */
    @GetMapping("/page")
    public Page<KnowledgeDocumentSegment> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String status) {

        LambdaQueryWrapper<KnowledgeDocumentSegment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(documentId != null, KnowledgeDocumentSegment::getDocumentId, documentId);
        wrapper.eq(StringUtils.hasText(status), KnowledgeDocumentSegment::getStatus, status);
        wrapper.orderByAsc(KnowledgeDocumentSegment::getDocumentId);
        wrapper.orderByAsc(KnowledgeDocumentSegment::getChunkOrder);

        return segmentService.page(new Page<>(pageNum, pageSize), wrapper);
    }
}
