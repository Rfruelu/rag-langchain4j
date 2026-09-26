package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import com.lujia.rag.raglangchain4j.chat.service.IntentRecognitionService;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 基于意图识别的查询路由器
 * <p>根据用户问题的意图分类，选择性路由到不同的检索器组合：</p>
 * <ul>
 *   <li>GENERAL（通用闲聊）→ 不检索，直接由 LLM 生成回复</li>
 *   <li>PRE_SALES / POST_SALES → ES 混合检索（知识库文档）</li>
 *   <li>PRICE_INQUIRY → ES 混合检索 + Neo4j 图检索 + SQL 检索（价格涉及图谱关系和结构化数据）</li>
 *   <li>SYSTEM_OPERATION → ES 混合检索（操作文档）</li>
 * </ul>
 */
@Slf4j
public class IntentBasedQueryRouter implements QueryRouter {

    private final IntentRecognitionService intentRecognitionService;

    /**
     * 意图 → 检索器列表 的映射表
     * <p>不同意图对应不同的检索器组合，避免向不相关的数据源发起无效查询</p>
     */
    private final Map<ChatIntent, List<ContentRetriever>> intentRetrieverMap;

    public IntentBasedQueryRouter(IntentRecognitionService intentRecognitionService,
                                  Map<ChatIntent, List<ContentRetriever>> intentRetrieverMap) {
        this.intentRecognitionService = intentRecognitionService;
        this.intentRetrieverMap = intentRetrieverMap;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        try {
            // 发射 RAG 管道状态：步骤2 - 意图识别
            RagStatusContext.emitStatus(2, 4, "正在识别意图", "分析用户问题的业务意图分类");

            // 优先复用外部已识别的意图（由 RagChatServiceImpl 在调用 streamChat 前设入 ThreadLocal）
            ChatIntent intent = IntentContextHolder.getIntent();

            if (intent == null) {
                // ThreadLocal 中无意图，执行 LLM 意图识别（结合多轮对话上下文）
                String conversationId = IntentContextHolder.getConversationId();
                intent = intentRecognitionService.recognizeIntent(
                        conversationId != null ? conversationId : "default", query.text());
                IntentContextHolder.setIntent(intent);
            } else {
                log.info("查询路由 - 复用外部已识别意图: intent={}", intent);
            }

            log.info("查询路由 - 意图识别: query='{}', intent={}", query.text(), intent);

            if (intent == ChatIntent.GENERAL) {
                log.info("通用闲聊，跳过知识库检索");
                return List.of();
            }

            // 根据意图获取对应的检索器列表，未配置的意图默认走售前检索器组合
            List<ContentRetriever> retrievers = intentRetrieverMap.get(intent);
            if (retrievers == null) {
                retrievers = intentRetrieverMap.getOrDefault(ChatIntent.PRE_SALES, List.of());
            }

            log.info("查询路由 - 选择检索器: intent={}, retrieverCount={}", intent, retrievers.size());
            return retrievers;

        } catch (Exception e) {
            log.warn("查询路由 - 意图识别失败，默认走知识库检索: {}", e.getMessage());
            // 降级时设置为 PRE_SALES，使用通用业务提示词
            IntentContextHolder.setIntent(ChatIntent.PRE_SALES);
            return intentRetrieverMap.getOrDefault(ChatIntent.PRE_SALES, List.of());
        }
    }
}
