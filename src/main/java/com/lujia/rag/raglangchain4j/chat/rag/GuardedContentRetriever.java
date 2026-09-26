package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.common.metrics.RagMetrics;
import com.lujia.rag.raglangchain4j.common.resilience.BailianResilience;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 带防护的检索源装饰器
 * <p>为底层检索源叠加三层防护：</p>
 * <ul>
 *   <li>Bulkhead 并发隔离：并发满快速失败，防止慢检索源拖垮问答线程</li>
 *   <li>超时控制：阻塞检索放到虚拟线程执行，超时取消并中断底层 IO</li>
 *   <li>CircuitBreaker 熔断：持续失败自动断开，半开试探恢复</li>
 * </ul>
 * <p>任何异常都降级为空结果，单个检索源故障不影响整体问答链路。
 * 检索延迟与命中率通过 {@link RagMetrics} 上报。</p>
 */
@Slf4j
public class GuardedContentRetriever implements ContentRetriever {

    /** 虚拟线程执行器：每个检索一个虚拟线程，超时后 orTimeout 自动取消中断底层 IO */
    private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().name("retrieval-", 0).factory();
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            Executors.newThreadPerTaskExecutor(VIRTUAL_THREAD_FACTORY);

    private final String name;
    private final ContentRetriever delegate;
    private final BailianResilience resilience;
    private final RagMetrics metrics;
    private final int timeoutSeconds;

    public GuardedContentRetriever(String name,
                                   ContentRetriever delegate,
                                   BailianResilience resilience,
                                   RagMetrics metrics,
                                   int timeoutSeconds) {
        this.name = name;
        this.delegate = delegate;
        this.resilience = resilience;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public List<Content> retrieve(Query query) {
        long startNanos = System.nanoTime();
        int hits = 0;
        try {
            List<Content> result = CircuitBreaker.decorateSupplier(
                    resilience.retrievalCircuitBreaker(name),
                    () -> doRetrieve(query)).get();
            hits = result.size();
            return result;
        } catch (BulkheadFullException e) {
            log.warn("检索源 {} 并发已满，快速降级", name);
            return List.of();
        } catch (Exception e) {
            log.warn("检索源 {} 降级为空结果: {}", name, e.getMessage());
            return List.of();
        } finally {
            metrics.recordRetrieval(name, Duration.ofNanos(System.nanoTime() - startNanos), hits);
        }
    }

    /**
     * Bulkhead + 超时两层防护执行检索，异常向上抛出供熔断器统计
     */
    private List<Content> doRetrieve(Query query) {
        Bulkhead bulkhead = resilience.bulkhead(name);
        if (!bulkhead.tryAcquirePermission()) {
            throw BulkheadFullException.createBulkheadFullException(bulkhead);
        }
        try {
            return CompletableFuture
                    .supplyAsync(() -> delegate.retrieve(query), EXECUTOR)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("检索被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new RuntimeException("检索超时（" + timeoutSeconds + "s）", cause);
            }
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        } finally {
            bulkhead.releasePermission();
        }
    }
}
