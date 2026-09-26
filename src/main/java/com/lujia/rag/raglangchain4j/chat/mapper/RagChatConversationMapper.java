package com.lujia.rag.raglangchain4j.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话会话 Mapper
 */
@Mapper
public interface RagChatConversationMapper extends BaseMapper<RagChatConversation> {

}
