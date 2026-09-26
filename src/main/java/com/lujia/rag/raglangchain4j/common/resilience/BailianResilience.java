package com.lujia.rag.raglangchain4j.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 百炼大模型调用的弹性组件管理器
 * <p>按模型名创建和管理 CircuitBreaker / Retry 实例（编程式，不走 AOP，项目未引入 spring-aop），
 * 并将断路器与重试指标自动注册到 Micrometer</p>
 */
@Slf4j
@Component
public class BailianResilience {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final CircuitBreakerRegistry retrievalCircuitBreakerRegistry;

    public BailianResilience(MeterRegistry meterRegistry) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                // 10 次调用进入统计窗口
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                // 50% 失败率熔断
                .failureRateThreshold(50)
                // 慢调用（>60s）占比超 50% 也熔断
                .slowCallDurationThreshold(Duration.ofSeconds(60))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryExceptions(RuntimeException.class)
                // 熔断开启时不再重试，快速失败
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        this.retryRegistry = RetryRegistry.of(retryConfig);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);

        // 检索隔离：并发上限 20，满了快速失败（检索是延迟敏感操作，不做等待）
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ZERO)
                .build();
        this.bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);

        // 检索熔断：与大模型调用分离的命名空间，慢调用阈值更短（检索超时通常 5s 内）
        CircuitBreakerConfig retrievalCbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.retrievalCircuitBreakerRegistry = CircuitBreakerRegistry.of(retrievalCbConfig);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(retrievalCircuitBreakerRegistry)
                .bindTo(meterRegistry);
    }

    /**
     * 获取（或创建）指定名称的隔离舱（并发限制）
     */
    public Bulkhead bulkhead(String name) {
        return bulkheadRegistry.bulkhead("retrieval-" + name);
    }

    /**
     * 获取（或创建）指定名称的检索熔断器（与百炼调用分离的命名空间）
     */
    public CircuitBreaker retrievalCircuitBreaker(String name) {
        return retrievalCircuitBreakerRegistry.circuitBreaker("retrieval-" + name);
    }

    /**
     * 获取（或创建）指定名称的断路器
     */
    public CircuitBreaker circuitBreaker(String name) {
        return circuitBreakerRegistry.circuitBreaker("bailian-" + name);
    }

    /**
     * 获取（或创建）指定名称的重试器
     */
    public Retry retry(String name) {
        return retryRegistry.retry("bailian-" + name);
    }

    /**
     * 装饰同步调用：重试 + 熔断
     */
    public <T> Supplier<T> decorateSupplier(String name, Supplier<T> supplier) {
        Supplier<T> withRetry = Retry.decorateSupplier(retry(name), supplier);
        return CircuitBreaker.decorateSupplier(circuitBreaker(name), withRetry);
    }
}
