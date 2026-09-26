package com.lujia.rag.raglangchain4j.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 知识库问答 AI 服务
 * <p>基于 RetrievalAugmentor 实现检索增强生成（RAG），支持：</p>
 * <ul>
 *   <li>查询路由：根据意图识别决定是否走知识库检索</li>
 *   <li>问题改写：使用 LLM 将口语化查询改写为专业检索文本</li>
 *   <li>ES 混合检索：向量语义检索 + BM25 关键词检索 + RRF 融合排序</li>
 *   <li>动态提示词注入：根据意图识别结果加载对应的提示词模板</li>
 * </ul>
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "ragChatModel",
        streamingChatModel = "ragStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        retrievalAugmentor = "retrievalAugmentor"
)
public interface KnowEngineChatAiService {

    /**
     * 流式聊天
     * <p>提示词由 IntentContentInjector 根据意图动态注入，无需在此定义 @SystemMessage</p>
     *
     * @param conversationId 会话ID
     * @param message        用户消息
     * @return 流式响应
     */
    Flux<String> streamChat(@MemoryId String conversationId, @UserMessage String message);

    /**
     * 同步聊天
     * <p>提示词由 IntentContentInjector 根据意图动态注入，无需在此定义 @SystemMessage</p>
     *
     * @param conversationId 会话ID
     * @param message        用户消息
     * @return AI回复内容
     */
    String chat(@MemoryId String conversationId, @UserMessage String message);
}
