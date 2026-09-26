import os

content = r"""package com.lujia.rag.raglangchain4j.chat.config;

import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import com.lujia.rag.raglangchain4j.chat.rag.FallbackContentRetriever;
import com.lujia.rag.raglangchain4j.chat.rag.HybridSearchContentRetriever;
import com.lujia.rag.raglangchain4j.chat.rag.IntentBasedQueryRouter;
import com.lujia.rag.raglangchain4j.chat.rag.IntentContentInjector;
import com.lujia.rag.raglangchain4j.chat.rag.OnnxScoringModelHolder;
import com.lujia.rag.raglangchain4j.chat.rag.QueryRewriteTransformer;
import com.lujia.rag.raglangchain4j.chat.service.IntentRecognitionService;
import com.lujia.rag.raglangchain4j.document.mapper.RagKnowledgeDocumentMapper;
import com.lujia.rag.raglangchain4j.document.service.KnowledgeDocumentSegmentService;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import org.redisson.api.RedissonClient;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索增强配置类
 * <p>组装 RetrievalAugmentor 及其依赖组件：QueryTransformer、ContentRetriever、QueryRouter、ContentAggregator、ContentInjector</p>
 */
@Configuration
public class RagConfig {

    @Value("${bailian.api-key}")
    private String bailianApiKey;

    @Value("${bailian.base-url}")
    private String bailianBaseUrl;

    @Value("${bailian.rag-model:qwen-plus}")
    private String ragModelName;

    @Value("${neo4j.uri:bolt://localhost:7687}")
    private String neo4jUri;

    @Value("${neo4j.username:neo4j}")
    private String neo4jUsername;

    @Value("${neo4j.password:neo4j}")
    private String neo4jPassword;

    /**
     * RAG 业务聊天模型（用于检索增强生成）
     */
    @Bean
    public ChatModel ragChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(bailianApiKey)
                .baseUrl(bailianBaseUrl)
                .modelName(ragModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 查询改写转换器
     * <p>使用 LLM 将用户口语化、模糊的查询改写为更专业、更适合检索的查询文本</p>
     */
    @Bean
    public QueryTransformer queryTransformer() {
        return new QueryRewriteTransformer(ragChatModel());
    }

    /**
     * 混合检索内容检索器
     * <p>结合向量语义检索 + BM25 关键词检索，使用 RRF 融合排序</p>
     * <p>支持父子文档替换：命中子文档时自动替换为父文档内容，并通过 Redis 缓存父文档</p>
     */
    @Bean
    public ContentRetriever hybridContentRetriever(VectorStoreService vectorStoreService,
                                                   KnowledgeDocumentSegmentService segmentService,
                                                   RagKnowledgeDocumentMapper documentMapper,
                                                   RedissonClient redissonClient) {
        return new HybridSearchContentRetriever(vectorStoreService, segmentService, documentMapper, redissonClient, 5, 0.7);
    }

    /**
     * SQL 数据库内容检索器
     * <p>使用 LLM 将自然语言转换为 SQL 查询，从关系型数据库中检索数据</p>
     */
    @Bean
    public ContentRetriever sqlContentRetriever(DataSource dataSource, ChatModel ragChatModel) {
        return SqlDatabaseContentRetriever.builder()
                .dataSource(dataSource)
                .chatModel(ragChatModel)
                .maxRetries(3)
                .build();
    }

    /**
     * Neo4j 图数据库内容检索器（带 ES 降级兜底）
     * <p>基于 Neo4j 知识图谱，使用 LLM 将自然语言转换为 Cypher 查询，检索实体关系数据</p>
     * <p>当 Neo4j 查询失败（如 Cypher 结果为空、图谱无匹配数据）时，自动降级到 ES 混合检索</p>
     */
    @Bean
    public ContentRetriever neo4jContentRetriever(ChatModel ragChatModel,
                                                   ContentRetriever hybridContentRetriever) {
        Driver driver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword));
        Neo4jGraph graph = Neo4jGraph.builder()
                .driver(driver)
                .build();
        ContentRetriever neo4jRetriever = Neo4jText2CypherRetriever.builder()
                .graph(graph)
                .chatModel(ragChatModel)
                .maxRetries(3)
                .build();
        return new FallbackContentRetriever(neo4jRetriever, hybridContentRetriever, "Neo4j");
    }

    /**
     * 基于意图识别的查询路由器
     * <p>根据用户问题意图选择性路由到不同的检索器组合，未匹配意图默认走 ES 混合检索</p>
     */
    @Bean
    public QueryRouter intentQueryRouter(IntentRecognitionService intentRecognitionService,
                                         ContentRetriever hybridContentRetriever,
                                         ContentRetriever neo4jContentRetriever,
                                         ContentRetriever sqlContentRetriever) {
        Map<ChatIntent, List<ContentRetriever>> intentRetrieverMap = new EnumMap<>(ChatIntent.class);
        // 售前咨询：ES 知识库 + Neo4j 图谱（图谱失败自动降级到 ES）
        intentRetrieverMap.put(ChatIntent.PRE_SALES, List.of(hybridContentRetriever, neo4jContentRetriever));
        // 售后问题：ES 知识库
        intentRetrieverMap.put(ChatIntent.POST_SALES, List.of(hybridContentRetriever));
        // 价格咨询：ES 知识库 + Neo4j 图谱 + SQL 结构化数据
        intentRetrieverMap.put(ChatIntent.PRICE_INQUIRY, List.of(hybridContentRetriever, neo4jContentRetriever, sqlContentRetriever));
        // 系统操作：ES 知识库
        intentRetrieverMap.put(ChatIntent.SYSTEM_OPERATION, List.of(hybridContentRetriever));
        return new IntentBasedQueryRouter(intentRecognitionService, intentRetrieverMap);
    }

    /**
     * 聊天记忆提供者
     * <p>为每个 conversationId 维护独立的聊天记忆窗口，保留最近 20 条消息</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return conversationId -> MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(20)
                .build();
    }

    /**
     * 基于 ONNX 本地重排序模型的内容聚合器
     * <p>使用 bge-reranker-v2-m3 对检索结果进行精排，过滤低分内容</p>
     * <p>通过 {@link OnnxScoringModelHolder} 单例获取 OnnxScoringModel，避免重复加载模型</p>
     */
    @Bean
    public ContentAggregator contentAggregator() {
        ScoringModel scoringModel = OnnxScoringModelHolder.getInstance();
        return ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .minScore(0.0)
                .build();
    }

    /**
     * 基于意图识别的动态内容注入器
     * <p>根据意图识别结果，加载对应的提示词模板，将检索内容和用户问题组装后注入</p>
     */
    @Bean
    public ContentInjector contentInjector() {
        return new IntentContentInjector();
    }

    /**
     * 检索增强器
     * <p>组装完整的 RAG 流程：查询改写 → 意图路由 → 混合检索 → 重排序聚合 → 意图提示词注入</p>
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(QueryTransformer queryTransformer,
                                                  QueryRouter queryRouter,
                                                  ContentAggregator contentAggregator,
                                                  ContentInjector contentInjector) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .contentAggregator(contentAggregator)
                .contentInjector(contentInjector)
                .build();
    }
}
"""

target = r"D:\ideafile\code\rag-langchain4j\src\main\java\com\lujia\rag\raglangchain4j\chat\config\RagConfig.java"
with open(target, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content.lstrip('\n'))
print("RagConfig.java written successfully")
