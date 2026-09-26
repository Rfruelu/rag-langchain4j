package com.lujia.rag.raglangchain4j.chat.config;

import com.lujia.rag.raglangchain4j.common.config.BailianModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI服务配置类
 * <p>注册各 AI Service 专用的 ChatModel Bean</p>
 */
@Slf4j
@Configuration
public class AiServiceConfig {

    private final BailianModelFactory modelFactory;

    /** 标题生成模型名称 */
    @Value("${bailian.title-model:qwen-turbo}")
    private String titleModelName;

    /** 通用聊天模型名称（用于非业务相关的闲聊对话） */
    @Value("${bailian.general-model:qwen-plus}")
    private String generalModelName;

    /** RAG 业务模型名称（用于知识库检索增强生成） */
    @Value("${bailian.rag-model:qwen-plus}")
    private String ragModelName;

    public AiServiceConfig(BailianModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 创建标题生成专用的 ChatModel
     * <p>由 TitleGeneratorService 通过 @AiService(chatModel = "titleChatModel") 引用</p>
     */
    @Bean
    public ChatModel titleChatModel() {
        log.info("初始化 titleChatModel, model={}", titleModelName);
        return modelFactory.chatModel(titleModelName, Duration.ofSeconds(30));
    }

    /**
     * 创建通用聊天专用的流式 ChatModel
     * <p>用于非业务相关对话的流式回复，支持逐 token 输出</p>
     */
    @Bean
    public StreamingChatModel generalChatModel() {
        log.info("初始化 generalChatModel, model={}", generalModelName);
        return modelFactory.streamingChatModel(generalModelName, Duration.ofSeconds(60));
    }

    /**
     * 创建 RAG 业务流式 ChatModel
     * <p>用于知识库检索增强生成的流式回复，配合 KnowEngineChatAiService 使用</p>
     */
    @Bean
    public StreamingChatModel ragStreamingChatModel() {
        log.info("初始化 ragStreamingChatModel, model={}", ragModelName);
        return modelFactory.streamingChatModel(ragModelName, Duration.ofSeconds(120));
    }
}
