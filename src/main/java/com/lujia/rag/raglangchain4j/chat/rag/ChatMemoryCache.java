package com.lujia.rag.raglangchain4j.chat.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 会话历史消息短 TTL 缓存
 * <p>DbBackedChatMemory 每轮对话都会查询 rag_chat_message 表，
 * 本组件将最近消息列表以 JSON 形式缓存在 Redis 中（默认 60 秒），
 * 消息落库后由业务层调用 {@link #evict(String)} 主动失效，保证一致性</p>
 * <p>缓存读写失败时静默回源数据库，不影响主流程</p>
 */
@Slf4j
@Component
public class ChatMemoryCache {

    private static final String KEY_PREFIX = "chat:memory:";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /** 缓存过期时间（秒） */
    @Value("${rag.memory-cache-ttl-seconds:60}")
    private long ttlSeconds;

    public ChatMemoryCache(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * 读取缓存的会话历史消息，未命中时通过 loader 回源并回填缓存
     *
     * @param conversationId 会话ID
     * @param limit          消息条数上限（不同上限使用独立缓存键）
     * @param loader         数据库回源加载器
     * @return 按时间正序的消息列表
     */
    public List<RagChatMessage> get(String conversationId, int limit, Supplier<List<RagChatMessage>> loader) {
        String key = key(conversationId, limit);
        try {
            String json = redissonClient.<String>getBucket(key).get();
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<>() {
                });
            }
        } catch (Exception e) {
            log.warn("读取会话记忆缓存失败，回源数据库: conversationId={}, error={}", conversationId, e.getMessage());
        }

        List<RagChatMessage> messages = loader.get();
        try {
            redissonClient.getBucket(key).set(objectMapper.writeValueAsString(messages), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入会话记忆缓存失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
        return messages;
    }

    /**
     * 会话有新消息落库后调用，清除该会话的全部记忆缓存
     */
    public void evict(String conversationId) {
        try {
            redissonClient.getKeys().deleteByPattern(KEY_PREFIX + conversationId + ":*");
        } catch (Exception e) {
            log.warn("清除会话记忆缓存失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    private String key(String conversationId, int limit) {
        return KEY_PREFIX + conversationId + ":" + limit;
    }
}
