package com.lujia.rag.raglangchain4j.common.resilience;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 具备弹性能力的 ChatModel 装饰器
 * <p>在底层模型调用外加 重试（最多3次） + 熔断（失败率50%熔断30秒），
 * 所有 default 聊天方法最终都会收敛到 {@link #chat(ChatRequest)}，因此只需装饰这一个方法</p>
 */
@Slf4j
public class ResilientChatModel implements ChatModel {

    private final ChatModel delegate;
    private final BailianResilience resilience;
    private final String modelName;

    public ResilientChatModel(ChatModel delegate, BailianResilience resilience, String modelName) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.modelName = modelName;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return resilience.decorateSupplier(modelName, () -> delegate.chat(chatRequest)).get();
    }
}
