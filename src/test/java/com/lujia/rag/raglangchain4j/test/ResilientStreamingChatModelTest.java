package com.lujia.rag.raglangchain4j.test;

import com.lujia.rag.raglangchain4j.common.resilience.BailianResilience;
import com.lujia.rag.raglangchain4j.common.resilience.ResilientStreamingChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式模型熔断行为单元测试
 * <p>验证点：流式响应中途失败必须计入断路器失败率（旧实现只统计"请求是否发出"，
 * 断流永远不会触发熔断）</p>
 */
class ResilientStreamingChatModelTest {

    private static final String MODEL_NAME = "test-stream-model";

    private BailianResilience resilience;
    private CircuitBreaker circuitBreaker;
    private ChatRequest request;

    @BeforeEach
    void setUp() {
        resilience = new BailianResilience(new SimpleMeterRegistry());
        // 与 BailianResilience 配置对应：最少 5 次调用、失败率 50% 即断开
        circuitBreaker = resilience.circuitBreaker(MODEL_NAME);
        request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("你好")))
                .build();
    }

    @Test
    @DisplayName("流式正常完成：token 透传，断路器保持 CLOSED")
    void shouldForwardTokensAndKeepBreakerClosedOnSuccess() {
        RecordingDelegate delegate = new RecordingDelegate(DelegateMode.SUCCESS);
        CapturingHandler handler = new CapturingHandler();

        new ResilientStreamingChatModel(delegate, resilience, MODEL_NAME).chat(request, handler);

        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(handler.tokens).containsExactly("片段一", "片段二");
        assertThat(handler.error).isNull();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("流中途失败：计入失败率，累计后断路器打开（核心修正点）")
    void shouldCountMidStreamFailureIntoBreaker() {
        RecordingDelegate delegate = new RecordingDelegate(DelegateMode.FAIL_MID_STREAM);
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(delegate, resilience, MODEL_NAME);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 5; i++) {
            CapturingHandler handler = new CapturingHandler();
            model.chat(request, handler);
            assertThat(handler.error).isInstanceOf(RuntimeException.class);
        }

        assertThat(circuitBreaker.getState())
                .as("5 次流中途失败应使失败率达到 100%%，超过 50%% 阈值")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("断路器打开后：快速失败且不再触达底层模型")
    void shouldRejectFastWhenBreakerOpen() {
        RecordingDelegate failing = new RecordingDelegate(DelegateMode.FAIL_MID_STREAM);
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(failing, resilience, MODEL_NAME);
        for (int i = 0; i < 5; i++) {
            model.chat(request, new CapturingHandler());
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        RecordingDelegate idleDelegate = new RecordingDelegate(DelegateMode.SUCCESS);
        CapturingHandler handler = new CapturingHandler();
        new ResilientStreamingChatModel(idleDelegate, resilience, MODEL_NAME).chat(request, handler);

        assertThat(idleDelegate.calls.get()).as("熔断期间不应发起真实调用").isZero();
        assertThat(handler.error).isInstanceOf(CallNotPermittedException.class);
        assertThat(handler.tokens).isEmpty();
    }

    @Test
    @DisplayName("底层同步抛异常：上报失败并通过 handler 转发，不向上抛出")
    void shouldReportAndForwardSynchronousFailure() {
        RecordingDelegate delegate = new RecordingDelegate(DelegateMode.THROW_SYNC);
        CapturingHandler handler = new CapturingHandler();

        new ResilientStreamingChatModel(delegate, resilience, MODEL_NAME).chat(request, handler);

        assertThat(handler.error).isInstanceOf(RuntimeException.class).hasMessage("连接失败");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private enum DelegateMode {
        SUCCESS,
        /** 先推送 token 再失败，模拟流中途断开 */
        FAIL_MID_STREAM,
        /** 调用方法时直接抛异常 */
        THROW_SYNC
    }

    private static final class RecordingDelegate implements StreamingChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final DelegateMode mode;

        RecordingDelegate(DelegateMode mode) {
            this.mode = mode;
        }

        @Override
        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            calls.incrementAndGet();
            switch (mode) {
                case SUCCESS -> {
                    handler.onPartialResponse("片段一");
                    handler.onPartialResponse("片段二");
                    handler.onCompleteResponse(null);
                }
                case FAIL_MID_STREAM -> {
                    handler.onPartialResponse("片段一");
                    handler.onError(new RuntimeException("流中途断开"));
                }
                case THROW_SYNC -> throw new RuntimeException("连接失败");
            }
        }
    }

    private static final class CapturingHandler implements StreamingChatResponseHandler {

        private final List<String> tokens = new ArrayList<>();
        private ChatResponse completed;
        private Throwable error;

        @Override
        public void onPartialResponse(String partialResponse) {
            tokens.add(partialResponse);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            completed = completeResponse;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }
    }

    @Test
    @DisplayName("成功调用逐次计入统计窗口，不重复计数")
    void shouldRecordExactlyOneResultPerCall() {
        RecordingDelegate delegate = new RecordingDelegate(DelegateMode.SUCCESS);
        CapturingHandler handler = new CapturingHandler();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(delegate, resilience, MODEL_NAME);

        model.chat(request, new CapturingHandler());
        model.chat(request, handler);

        assertThat(handler.completed).isNull();
        assertThat(handler.tokens).hasSize(2);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
