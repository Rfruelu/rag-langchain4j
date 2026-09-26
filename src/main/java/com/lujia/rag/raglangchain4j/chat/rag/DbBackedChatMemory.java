package com.lujia.rag.raglangchain4j.chat.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatMessageMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于数据库持久化的 ChatMemory 实现
 * <p>从 rag_chat_message 表加载对话历史作为多轮上下文，
 * 消息的持久化由 RagChatServiceImpl 负责，本类仅负责读取</p>
 * <p>传入 {@link ChatMemoryCache} 时优先读短 TTL 缓存，避免每轮对话重复查库；
 * 缓存由业务层在消息落库后主动失效</p>
 */
@Slf4j
public class DbBackedChatMemory implements ChatMemory {

    private final Object id;
    private final RagChatMessageMapper messageMapper;
    private final int maxMessages;
    private final ChatMemoryCache cache;

    public DbBackedChatMemory(Object id, RagChatMessageMapper messageMapper, int maxMessages) {
        this(id, messageMapper, maxMessages, null);
    }

    public DbBackedChatMemory(Object id, RagChatMessageMapper messageMapper, int maxMessages, ChatMemoryCache cache) {
        this.id = id;
        this.messageMapper = messageMapper;
        this.maxMessages = maxMessages;
        this.cache = cache;
    }

    /**
     * 加载指定会话的最近 N 条消息（统一的"加载最近历史"实现）
     * <p>先按时间倒序取最近 N 条，再反转为时间正序返回</p>
     *
     * @param messageMapper  消息 Mapper
     * @param conversationId 会话ID
     * @param limit          最大加载条数
     * @return 按时间正序排列的消息列表
     */
    public static List<RagChatMessage> loadRecentMessages(RagChatMessageMapper messageMapper,
                                                          String conversationId, int limit) {
        LambdaQueryWrapper<RagChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagChatMessage::getConversationId, conversationId)
                .orderByDesc(RagChatMessage::getCreatedTime);
        List<RagChatMessage> messages = messageMapper.selectPage(new Page<>(1, limit, false), wrapper).getRecords();
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public Object id() {
        return id;
    }

    /**
     * 从数据库（或短 TTL 缓存）加载该会话的最近 N 条消息作为上下文
     * <p>仅加载 USER 和 ASSISTANT 类型的消息，按时间正序返回</p>
     */
    @Override
    public List<ChatMessage> messages() {
        String conversationId = id.toString();

        List<RagChatMessage> dbMessages;
        if (cache != null) {
            dbMessages = cache.get(conversationId, maxMessages,
                    () -> loadRecentMessages(messageMapper, conversationId, maxMessages));
        } else {
            dbMessages = loadRecentMessages(messageMapper, conversationId, maxMessages);
        }

        // 转换为 langchain4j ChatMessage
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (RagChatMessage msg : dbMessages) {
            if ("USER".equals(msg.getType())) {
                chatMessages.add(UserMessage.from(msg.getContent()));
            } else if ("ASSISTANT".equals(msg.getType())) {
                chatMessages.add(AiMessage.from(msg.getContent()));
            }
        }

        log.debug("从数据库加载 {} 条历史消息: conversationId={}", chatMessages.size(), conversationId);
        return chatMessages;
    }

    /**
     * 空操作 — 消息持久化由 RagChatServiceImpl 统一管理
     */
    @Override
    public void add(ChatMessage message) {
        // 消息已由 RagChatServiceImpl 保存到数据库，此处无需操作
    }

    /**
     * 空操作 — 数据库消息由业务层管理（逻辑删除）
     */
    @Override
    public void clear() {
        // 数据库消息由业务层管理，此处无需操作
    }
}
