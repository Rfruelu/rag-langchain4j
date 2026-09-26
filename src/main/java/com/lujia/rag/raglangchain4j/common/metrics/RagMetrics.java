package com.lujia.rag.raglangchain4j.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RAG 业务指标统一埋点
 * <p>暴露的核心指标：</p>
 * <ul>
 *   <li>{@code rag.retrieval}（Timer, tag: source）— 各检索源检索延迟</li>
 *   <li>{@code rag.retrieval.hit}（Counter, tag: source/hit）— 各检索源命中率</li>
 *   <li>{@code rag.retrieval.fallback}（Counter, tag: source）— 主检索源失败改走兜底的次数</li>
 *   <li>{@code rag.chat.intent}（Counter, tag: intent）— 意图分布</li>
 *   <li>{@code rag.pipeline.stage}（Counter, tag: stage/result）— 文档流水线各环节成功/失败次数</li>
 * </ul>
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次检索：延迟 + 是否命中
     *
     * @param source  检索源（es / neo4j / sql）
     * @param latency 检索耗时
     * @param hits    检索到的内容条数
     */
    public void recordRetrieval(String source, Duration latency, int hits) {
        Timer.builder("rag.retrieval")
                .tag("source", source)
                .register(registry)
                .record(latency);
        Counter.builder("rag.retrieval.hit")
                .tag("source", source)
                .tag("hit", hits > 0 ? "true" : "false")
                .register(registry)
                .increment();
    }

    /**
     * 记录一次主检索源失败后改走兜底检索源
     *
     * @param source 失败的主检索源（neo4j）
     */
    public void recordFallback(String source) {
        Counter.builder("rag.retrieval.fallback")
                .tag("source", source)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次意图识别结果
     */
    public void recordIntent(String intent) {
        Counter.builder("rag.chat.intent")
                .tag("intent", intent)
                .register(registry)
                .increment();
    }

    /**
     * 记录文档流水线某环节的处理结果
     *
     * @param stage   环节（uploaded / converted / chunked / vector_stored）
     * @param success 是否处理成功
     */
    public void recordPipelineStage(String stage, boolean success) {
        Counter.builder("rag.pipeline.stage")
                .tag("stage", stage)
                .tag("result", success ? "success" : "failure")
                .register(registry)
                .increment();
    }
}
