package com.lujia.rag.raglangchain4j.chat.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 会话标题生成 AI 服务
 * <p>基于 langchain4j @AiService 声明式接口，通过 EXPLICIT 模式指定使用 titleChatModel</p>
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "titleChatModel")
public interface TitleGeneratorService {

    /**
     * 根据用户消息和AI回复生成会话标题
     *
     * @param userContent      用户消息内容
     * @param assistantContent AI回复内容
     * @return 简洁的会话标题（不超过20个字）
     */
    @SystemMessage("""
            你是一个会话标题生成助手。根据用户的第一条消息和AI的回复，生成一个简洁的会话标题。要求：1. 标题不超过20个字；2. 准确概括对话主题；3. 只输出标题文本，不要添加任何前缀、引号或解释。""")
    @UserMessage("用户消息：{{userContent}}\n\nAI回复：{{assistantContent}}")
    String generateTitle(@V("userContent") String userContent,
                         @V("assistantContent") String assistantContent);
}
