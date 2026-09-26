package com.lujia.rag.raglangchain4j.common.resilience;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 具备弹性能力的 StreamingChatModel 装饰器
 * <p>只加熔断不重试：流式响应一旦开始，中途失败重试会造成重复内容推送给客户端</p>
 * <p>不使用 {@code CircuitBreaker.decorateRunnable}：被装饰的调用会在首个 token 前立即返回，
 * 那样只能统计"请求是否成功发出"，断流、超时等真实失败永远不会计入失败率。
 * 这里手动持有断路器额度，在 {@code onCompleteResponse} / {@code onError} 回调时按整段流的
 * 实际耗时和结果上报</p>
 */
public class ResilientStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final BailianResilience resilience;
    private final String modelName;

    public ResilientStreamingChatModel(StreamingChatModel delegate, BailianResilience resilience, String modelName) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.modelName = modelName;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        CircuitBreaker circuitBreaker = resilience.circuitBreaker(modelName);
        if (!circuitBreaker.tryAcquirePermission()) {
            // 熔断开启或半开额度已满：保持 langchain4j 错误契约，通过 handler 快速失败
            handler.onError(CallNotPermittedException.createCallNotPermittedException(circuitBreaker));
            return;
        }

        long startNanos = System.nanoTime();
        // 成功与失败只会有一方被上报，防止一次调用污染统计窗口
        AtomicBoolean reported = new AtomicBoolean(false);

        try {
            delegate.chat(chatRequest, new StreamingChatResponseHandler() {

                @Override
                public void onPartialResponse(String partialResponse) {
                    handler.onPartialResponse(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    if (reported.compareAndSet(false, true)) {
                        circuitBreaker.onSuccess(elapsedMillis(startNanos), TimeUnit.MILLISECONDS);
                    }
                    handler.onCompleteResponse(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    if (reported.compareAndSet(false, true)) {
                        circuitBreaker.onError(elapsedMillis(startNanos), TimeUnit.MILLISECONDS, error);
                    }
                    handler.onError(error);
                }
            });
        } catch (Exception e) {
            if (reported.compareAndSet(false, true)) {
                circuitBreaker.onError(elapsedMillis(startNanos), TimeUnit.MILLISECONDS, e);
            }
            handler.onError(e);
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
