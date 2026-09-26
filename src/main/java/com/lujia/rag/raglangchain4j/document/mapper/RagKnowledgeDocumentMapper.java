package com.lujia.rag.raglangchain4j.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag知识文档 Mapper
 */
@Mapper
public interface RagKnowledgeDocumentMapper extends BaseMapper<RagKnowledgeDocument> {

}
