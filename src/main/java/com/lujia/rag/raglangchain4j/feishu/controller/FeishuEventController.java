package com.lujia.rag.raglangchain4j.feishu.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lujia.rag.raglangchain4j.feishu.config.FeishuProperties;
import com.lujia.rag.raglangchain4j.feishu.model.FeishuEvent;
import com.lujia.rag.raglangchain4j.feishu.service.FeishuMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书事件订阅回调接口
 * <p>接收飞书 Open Platform 推送的事件通知，包括：</p>
 * <ul>
 *   <li>URL 验证挑战（首次配置回调地址时飞书发送的验证请求）</li>
 *   <li>消息事件回调（im.message.receive_v1，用户发送消息给机器人时触发）</li>
 * </ul>
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/event-subscription-guide/event-format">飞书事件格式</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu")
public class FeishuEventController {

    /** 事件去重 Redis key 前缀，TTL 5 分钟 */
    private static final String EVENT_DEDUP_PREFIX = "feishu:event:dedup:";
    private static final Duration EVENT_DEDUP_TTL = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private FeishuProperties feishuProperties;

    @Autowired
    private FeishuMessageHandler messageHandler;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 飞书事件回调入口
     * <p>处理流程：</p>
     * <ol>
     *   <li>URL 验证挑战：直接返回 challenge 值</li>
     *   <li>事件回调：验证 token → 事件去重 → 处理消息事件</li>
     * </ol>
     *
     * @param body 飞书推送的原始 JSON 请求体
     * @return URL 验证时返回 challenge，事件回调时返回空成功响应
     */
    @PostMapping("/event")
    public Map<String, Object> handleEvent(@RequestBody String body) {
        try {
            FeishuEvent event = objectMapper.readValue(body, FeishuEvent.class);

            // 1. URL 验证挑战（首次配置回调地址时飞书发送）
            if ("url_verification".equals(event.getType())) {
                log.info("收到飞书 URL 验证挑战");
                // 验证 token
                if (!verifyToken(event.getToken())) {
                    log.warn("飞书 URL 验证 token 不匹配");
                    return Map.of("code", -1, "msg", "token mismatch");
                }
                Map<String, Object> response = new HashMap<>();
                response.put("challenge", event.getChallenge());
                return response;
            }

            // 2. 事件回调处理
            if ("event_callback".equals(event.getType())) {
                // 验证 token
                String eventToken = event.getHeader() != null ? event.getHeader().token() : event.getToken();
                if (!verifyToken(eventToken)) {
                    log.warn("飞书事件 token 不匹配");
                    return Map.of("code", -1, "msg", "token mismatch");
                }

                // 事件去重（飞书可能重复推送同一事件）
                String eventId = event.getHeader() != null ? event.getHeader().eventId() : null;
                if (eventId != null && !deduplicateEvent(eventId)) {
                    log.info("飞书事件已处理过，跳过: eventId={}", eventId);
                    return Map.of("code", 0);
                }

                // 处理消息事件
                String eventType = event.getHeader() != null ? event.getHeader().eventType() : null;
                if ("im.message.receive_v1".equals(eventType)) {
                    log.info("收到飞书消息事件: eventId={}", eventId);
                    messageHandler.handleMessage(event);
                } else {
                    log.debug("忽略非消息事件: eventType={}", eventType);
                }
            }

            return Map.of("code", 0);

        } catch (Exception e) {
            log.error("处理飞书事件异常", e);
            return Map.of("code", -1, "msg", "internal error");
        }
    }

    /**
     * 验证飞书请求 token 是否匹配
     *
     * @param token 请求中携带的 token
     * @return 是否匹配配置的 verification_token
     */
    private boolean verifyToken(String token) {
        String configuredToken = feishuProperties.getVerificationToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            // 未配置 token 时跳过验证（开发环境）
            return true;
        }
        return configuredToken.equals(token);
    }

    /**
     * 事件去重检查
     * <p>使用 Redis 记录已处理的事件 ID，防止飞书重复推送导致重复处理</p>
     *
     * @param eventId 事件 ID
     * @return true 表示首次处理，false 表示已处理过
     */
    private boolean deduplicateEvent(String eventId) {
        String key = EVENT_DEDUP_PREFIX + eventId;
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            // setIfAbsent 即 Redis SETNX，原子操作
            boolean isNew = bucket.setIfAbsent("1", EVENT_DEDUP_TTL);
            return isNew;
        } catch (Exception e) {
            log.warn("事件去重检查失败，允许处理: eventId={}", eventId, e);
            return true; // Redis 异常时允许处理，避免消息丢失
        }
    }
}
