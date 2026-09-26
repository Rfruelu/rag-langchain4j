package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.common.metrics.RagMetrics;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 带降级兜底的内容检索器装饰器
 * <p>包装主检索器（如 Neo4j Text2Cypher），当主检索器抛出异常（如查询结果为空、Cypher 生成错误等）时，
 * 自动降级到备用检索器（如 ES 混合检索），保证 RAG 流程不因单一数据源失败而中断。</p>
 * <p>每次降级通过 {@link RagMetrics#recordFallback(String)} 计数：兜底成功会被外层熔断器记作成功，
 * 该指标是观察主检索源真实健康度的唯一入口。</p>
 */
@Slf4j
public class FallbackContentRetriever implements ContentRetriever {

    private final ContentRetriever primary;
    private final ContentRetriever fallback;
    private final String primaryName;
    private final RagMetrics metrics;

    /**
     * @param primary     主检索器（如 Neo4j）
     * @param fallback    降级备用检索器（如受防护的 ES 混合检索）
     * @param primaryName 主检索器名称，用于日志与指标标识
     * @param metrics     指标埋点，记录降级次数
     */
    public FallbackContentRetriever(ContentRetriever primary,
                                    ContentRetriever fallback,
                                    String primaryName,
                                    RagMetrics metrics) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
        this.metrics = metrics;
    }

    @Override
    public List<Content> retrieve(Query query) {
        try {
            List<Content> results = primary.retrieve(query);
            log.debug("{} 检索成功，返回 {} 条结果", primaryName, results.size());
            return results;
        } catch (Exception e) {
            metrics.recordFallback(primaryName);
            log.warn("{} 检索失败，降级到备用检索源。原因: {}", primaryName, e.getMessage());
            return fallback.retrieve(query);
        }
    }
}
