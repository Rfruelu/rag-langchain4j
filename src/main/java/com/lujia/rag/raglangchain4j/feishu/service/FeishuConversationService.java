package com.lujia.rag.raglangchain4j.feishu.service;

import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import com.lujia.rag.raglangchain4j.chat.service.RagChatService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 飞书会话映射服务
 * <p>维护飞书 chat_id 与系统内部 conversationId 的映射关系，
 * 支持单聊（p2p）和群聊（group）两种模式</p>
 *
 * <p>映射策略：</p>
 * <ul>
 *   <li>单聊：每个用户的 open_id 对应一个独立会话，一人一会话</li>
 *   <li>群聊：整个群共享一个会话上下文，一群一会话</li>
 *   <li>缓存：Redis 存储映射关系，TTL 7 天，自动续期</li>
 * </ul>
 */
@Slf4j
@Service
public class FeishuConversationService {

    /** Redis key 前缀：飞书 chat_id → 内部 conversationId */
    private static final String FEISHU_CHAT_MAPPING_PREFIX = "feishu:chat:mapping:";

    /** 映射缓存 TTL：7 天 */
    private static final Duration MAPPING_TTL = Duration.ofDays(7);

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 获取或创建飞书会话对应的内部 conversationId
     * <p>单聊时基于 open_id 隔离，群聊时基于 chat_id 共享</p>
     *
     * @param chatId    飞书会话 ID
     * @param chatType  会话类型：p2p（单聊）或 group（群聊）
     * @param openId    发送者的 open_id（单聊时使用）
     * @return 内部 conversationId
     */
    public String getOrCreateConversation(String chatId, String chatType, String openId) {
        // 1. 先查 Redis 缓存
        String cacheKey = FEISHU_CHAT_MAPPING_PREFIX + chatId;
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey);
            String conversationId = bucket.get();
            if (conversationId != null) {
                // 续期 TTL
                bucket.expire(MAPPING_TTL);
                log.debug("飞书会话映射缓存命中: chatId={}, conversationId={}", chatId, conversationId);
                return conversationId;
            }
        } catch (Exception e) {
            log.warn("Redis 查询飞书会话映射失败: chatId={}", chatId, e);
        }

        // 2. 缓存未命中，创建新会话
        String conversationId = createNewConversation(chatId, chatType, openId);

        // 3. 写入 Redis 缓存
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey);
            bucket.set(conversationId, MAPPING_TTL);
            log.info("飞书会话映射已缓存: chatId={}, conversationId={}", chatId, conversationId);
        } catch (Exception e) {
            log.warn("Redis 缓存飞书会话映射失败: chatId={}", chatId, e);
        }

        return conversationId;
    }

    /**
     * 创建新的内部会话
     *
     * @param chatId   飞书会话 ID
     * @param chatType 会话类型
     * @param openId   发送者 open_id
     * @return 新创建的 conversationId
     */
    private String createNewConversation(String chatId, String chatType, String openId) {
        // 飞书用户统一使用 "feishu_" 前缀的 userId，与 Web 端用户隔离
        String userId;
        String title;

        if ("group".equals(chatType)) {
            // 群聊：一群一会话，userId 使用群 ID
            userId = "feishu_group_" + chatId;
            title = "飞书群聊";
        } else {
            // 单聊：一人一会话，userId 使用 open_id
            userId = "feishu_" + openId;
            title = "飞书对话";
        }

        RagChatConversation conversation = ragChatService.createConversation(userId, title);
        log.info("创建飞书会话映射: chatId={}, chatType={}, conversationId={}",
                chatId, chatType, conversation.getConversationId());

        return conversation.getConversationId();
    }

    /**
     * 清除飞书会话映射（用于重置会话场景）
     *
     * @param chatId 飞书会话 ID
     */
    public void clearMapping(String chatId) {
        String cacheKey = FEISHU_CHAT_MAPPING_PREFIX + chatId;
        try {
            redissonClient.getBucket(cacheKey).delete();
            log.info("飞书会话映射已清除: chatId={}", chatId);
        } catch (Exception e) {
            log.warn("清除飞书会话映射失败: chatId={}", chatId, e);
        }
    }
}
