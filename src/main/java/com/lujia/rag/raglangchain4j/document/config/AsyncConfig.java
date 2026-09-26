package com.lujia.rag.raglangchain4j.document.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异步事件线程池配置
 * <p>为 Spring Event 监听器提供独立的线程池，避免阻塞主线程</p>
 * <p>配合 @Async 注解使用，监听方法将在 documentTaskExecutor 线程池中异步执行</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 100;

    /**
     * 文档处理专用线程池
     * <p>用于异步执行文档事件监听器中的耗时操作（MinerU 调用、图片处理、文档切分等）</p>
     * <p>线程池参数：</p>
     * <ul>
     *   <li>核心线程数: 4</li>
     *   <li>最大线程数: 8</li>
     *   <li>队列容量: 100</li>
     *   <li>拒绝策略: AbortPolicy（队列满时抛出 RejectedExecutionException，由发布方捕获并标记文档失败）</li>
     * </ul>
     */
    @Bean("documentTaskExecutor")
    public Executor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("doc-event-");
        // 拒绝策略：队列已满时记录错误日志并抛出异常，由发布方捕获后标记文档失败
        executor.setRejectedExecutionHandler((runnable, rejectedExecutor) -> {
            log.error("文档事件线程池已满载，拒绝执行任务: poolSize={}, queueSize={}, activeCount={}",
                    rejectedExecutor.getPoolSize(), rejectedExecutor.getQueue().size(),
                    rejectedExecutor.getActiveCount());
            throw new RejectedExecutionException("文档事件线程池队列已满，无法提交任务");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("文档事件线程池初始化完成: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), QUEUE_CAPACITY);
        return executor;
    }
}
