package com.lujia.rag.raglangchain4j.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话消息 Mapper
 */
@Mapper
public interface RagChatMessageMapper extends BaseMapper<RagChatMessage> {

}
