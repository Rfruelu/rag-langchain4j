package com.lujia.rag.raglangchain4j.common.config;

import com.lujia.rag.raglangchain4j.common.resilience.BailianResilience;
import com.lujia.rag.raglangchain4j.common.resilience.ResilientChatModel;
import com.lujia.rag.raglangchain4j.common.resilience.ResilientStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 百炼大模型工厂
 * <p>统一创建 ChatModel / StreamingChatModel，并自动包装重试 + 熔断（按模型名隔离统计）</p>
 */
@Component
public class BailianModelFactory {

    private final BailianProperties properties;
    private final BailianResilience resilience;

    public BailianModelFactory(BailianProperties properties, BailianResilience resilience) {
        this.properties = properties;
        this.resilience = resilience;
    }

    public ChatModel chatModel(String modelName, Duration timeout) {
        ChatModel delegate = OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(modelName)
                .timeout(timeout)
                .build();
        return new ResilientChatModel(delegate, resilience, modelName);
    }

    public StreamingChatModel streamingChatModel(String modelName, Duration timeout) {
        StreamingChatModel delegate = OpenAiStreamingChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(modelName)
                .timeout(timeout)
                .build();
        return new ResilientStreamingChatModel(delegate, resilience, modelName);
    }
}
