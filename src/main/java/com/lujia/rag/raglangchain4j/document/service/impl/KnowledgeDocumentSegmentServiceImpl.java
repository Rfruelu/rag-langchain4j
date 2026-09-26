package com.lujia.rag.raglangchain4j.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.mapper.KnowledgeDocumentSegmentMapper;
import com.lujia.rag.raglangchain4j.document.service.KnowledgeDocumentSegmentService;
import org.springframework.stereotype.Service;

/**
 * 知识片段 ServiceImpl
 */
@Service
public class KnowledgeDocumentSegmentServiceImpl extends ServiceImpl<KnowledgeDocumentSegmentMapper, KnowledgeDocumentSegment>
        implements KnowledgeDocumentSegmentService {

}
