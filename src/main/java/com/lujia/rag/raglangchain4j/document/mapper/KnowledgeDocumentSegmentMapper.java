package com.lujia.rag.raglangchain4j.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段 Mapper
 */
@Mapper
public interface KnowledgeDocumentSegmentMapper extends BaseMapper<KnowledgeDocumentSegment> {

}
