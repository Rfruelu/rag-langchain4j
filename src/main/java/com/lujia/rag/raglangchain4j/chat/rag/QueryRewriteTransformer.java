package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatMessageMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * 查询改写转换器
 * <p>使用 LLM 将用户口语化、模糊的查询改写为更专业、更适合检索的查询文本</p>
 * <p>结合多轮对话上下文进行改写，能正确解析“这个”“它”“上次那个”等指代性表述</p>
 * <p>例如：
 * <ul>
 *   <li>历史: “帮我找32支精梳全棉的卫衣布料” → 当前: “这个多少钱” → “32支精梳全棉卫衣布料 价格”</li>
 *   <li>历史: “有没有不倒绒的供应商” → 当前: “它有什么缺点” → “不倒绒面料 缺点 特性”</li>
 * </ul>
 * </p>
 */
@Slf4j
public class QueryRewriteTransformer implements QueryTransformer {

    /** 加载的历史消息轮数（每轮 = 1条用户消息 + 1条AI回复） */
    private static final int HISTORY_TURNS = 3;

    private final ChatModel chatModel;

    private final RagChatMessageMapper messageMapper;

    public QueryRewriteTransformer(ChatModel chatModel, RagChatMessageMapper messageMapper) {
        this.chatModel = chatModel;
        this.messageMapper = messageMapper;
    }

    @Override
    public Collection<Query> transform(Query query) {
        try {
            String originalText = query.text();

            // 发射 RAG 管道状态：步骤1 - 查询改写
            RagStatusContext.emitStatus(1, 4, "正在改写查询", "优化查询语句以提高检索准确率");

            // 加载多轮对话上下文
            String historyContext = loadConversationHistory();

            String prompt;
            if (!historyContext.isEmpty()) {
                prompt = """
                    你是一个查询改写助手。结合对话历史，将用户的最新问题改写为更适合知识库检索的形式。
                    要求：
                    1. 结合对话历史理解用户的真实意图，特别是“这个”“它”“多少钱”等指代性表述
                    2. 提取核心查询意图
                    3. 使用更专业、更精确的术语
                    4. 扩展相关词汇以提高检索覆盖率
                    5. 保持简洁，不超过20个字
                    6. 直接输出改写后的查询，不要有任何解释
                    
                    对话历史：
                    %s
                    用户最新问题：%s
                    改写后的查询：
                    """.formatted(historyContext, originalText);
            } else {
                prompt = """
                    你是一个查询改写助手。将用户的问题改写为更适合知识库检索的形式。
                    要求：
                    1. 提取核心查询意图
                    2. 使用更专业、更精确的术语
                    3. 扩展相关词汇以提高检索覆盖率
                    4. 保持简洁，不超过20个字
                    5. 直接输出改写后的查询，不要有任何解释
                    
                    用户问题：%s
                    改写后的查询：
                    """.formatted(originalText);
            }

            String rewrittenQuery = chatModel.chat(prompt);

            if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
                rewrittenQuery = rewrittenQuery.trim();
                log.debug("查询改写完成: 原始='{}', 改写='{}'", originalText, rewrittenQuery);
                return List.of(Query.from(rewrittenQuery));
            }
            
            log.debug("查询改写结果为空，使用原始查询: '{}'", originalText);
            return List.of(query);
            
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
            return List.of(query);
        }
    }

    /**
     * 从数据库加载该会话的最近几轮对话历史
     *
     * @return 格式化的对话历史文本，无历史时返回空字符串
     */
    private String loadConversationHistory() {
        String conversationId = IntentContextHolder.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return "";
        }

        try {
            // 加载最近 N 轮消息（每轮 = 用户消息 + AI回复）
            List<RagChatMessage> messages = DbBackedChatMemory.loadRecentMessages(
                    messageMapper, conversationId, HISTORY_TURNS * 2);

            if (messages.isEmpty()) {
                return "";
            }

            // 格式化为对话历史文本
            StringBuilder sb = new StringBuilder();
            for (RagChatMessage msg : messages) {
                // 仅保留用户与助手消息
                if (!"USER".equals(msg.getType()) && !"ASSISTANT".equals(msg.getType())) {
                    continue;
                }
                String role = "USER".equals(msg.getType()) ? "用户" : "助手";
                // 截取内容，避免过长
                String content = msg.getContent();
                if (content.length() > 100) {
                    content = content.substring(0, 100) + "...";
                }
                sb.append(role).append("：").append(content).append("\n");
            }

            log.debug("查询改写加载 {} 条历史消息: conversationId={}", messages.size(), conversationId);
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("加载对话历史失败: conversationId={}, error={}", conversationId, e.getMessage());
            return "";
        }
    }
}
