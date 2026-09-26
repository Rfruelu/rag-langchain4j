package com.lujia.rag.raglangchain4j.feishu.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lujia.rag.raglangchain4j.feishu.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书 Open API 服务
 * <p>封装飞书 API 调用：tenant_access_token 管理（自动刷新）、发送消息等</p>
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/getting-started/server-error-codes">飞书 API 文档</a>
 */
@Slf4j
@Service
public class FeishuApiService {

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String SEND_MESSAGE_URL = "https://open.feishu.cn/open-apis/im/v1/messages";

    /** Token 提前 5 分钟刷新，避免过期 */
    private static final long TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private FeishuProperties feishuProperties;

    /** 缓存的 tenant_access_token */
    private volatile String cachedToken;

    /** Token 过期时间戳（毫秒） */
    private volatile long tokenExpireTime;

    /**
     * 获取 tenant_access_token（自动缓存和刷新）
     *
     * @return 有效的 tenant_access_token
     */
    public String getTenantAccessToken() {
        // 检查缓存是否仍然有效
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireTime - TOKEN_REFRESH_MARGIN_MS) {
            return cachedToken;
        }

        synchronized (this) {
            // 双重检查锁
            if (cachedToken != null && System.currentTimeMillis() < tokenExpireTime - TOKEN_REFRESH_MARGIN_MS) {
                return cachedToken;
            }
            return refreshToken();
        }
    }

    /**
     * 刷新 tenant_access_token
     */
    private String refreshToken() {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("app_id", feishuProperties.getAppId());
            body.put("app_secret", feishuProperties.getAppSecret());

            try (HttpResponse response = HttpRequest.post(TOKEN_URL)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .timeout(10000)
                    .execute()) {

                JsonNode json = objectMapper.readTree(response.body());
                int code = json.get("code").asInt();
                if (code != 0) {
                    throw new RuntimeException("获取 tenant_access_token 失败: code=" + code
                            + ", msg=" + json.get("msg").asText());
                }

                cachedToken = json.get("tenant_access_token").asText();
                long expireSeconds = json.get("expire").asLong();
                tokenExpireTime = System.currentTimeMillis() + expireSeconds * 1000;

                log.info("飞书 tenant_access_token 已刷新，有效期 {}s", expireSeconds);
                return cachedToken;
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("刷新飞书 token 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送文本消息
     *
     * @param receiveIdType 接收者 ID 类型：open_id、chat_id
     * @param receiveId     接收者 ID
     * @param text          文本内容
     */
    public void sendTextMessage(String receiveIdType, String receiveId, String text) {
        try {
            Map<String, String> content = new HashMap<>();
            content.put("text", text);

            sendMessage(receiveIdType, receiveId, "text", objectMapper.writeValueAsString(content));
        } catch (Exception e) {
            log.error("发送飞书文本消息失败: receiveId={}", receiveId, e);
        }
    }

    /**
     * 发送卡片消息（Interactive Card）
     *
     * @param receiveIdType 接收者 ID 类型：open_id、chat_id
     * @param receiveId     接收者 ID
     * @param cardJson      卡片 JSON 字符串
     */
    public void sendCardMessage(String receiveIdType, String receiveId, String cardJson) {
        try {
            sendMessage(receiveIdType, receiveId, "interactive", cardJson);
        } catch (Exception e) {
            log.error("发送飞书卡片消息失败: receiveId={}", receiveId, e);
        }
    }

    /**
     * 回复消息（在指定消息下回复）
     *
     * @param messageId  要回复的消息 ID
     * @param cardJson   卡片 JSON 字符串
     */
    public void replyCardMessage(String messageId, String cardJson) {
        try {
            String url = SEND_MESSAGE_URL + "/" + messageId + "/reply";
            String token = getTenantAccessToken();

            Map<String, Object> body = new HashMap<>();
            body.put("msg_type", "interactive");
            body.put("content", cardJson);

            try (HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .timeout(15000)
                    .execute()) {

                JsonNode json = objectMapper.readTree(response.body());
                int code = json.get("code").asInt();
                if (code != 0) {
                    log.error("回复飞书卡片消息失败: messageId={}, code={}, msg={}",
                            messageId, code, json.get("msg").asText());
                } else {
                    log.info("回复飞书卡片消息成功: messageId={}", messageId);
                }
            }

        } catch (Exception e) {
            log.error("回复飞书卡片消息异常: messageId={}", messageId, e);
        }
    }

    /**
     * 发送消息的通用方法
     *
     * @param receiveIdType 接收者 ID 类型
     * @param receiveId     接收者 ID
     * @param msgType       消息类型：text、interactive 等
     * @param content       消息内容 JSON 字符串
     */
    private void sendMessage(String receiveIdType, String receiveId, String msgType, String content) {
        try {
            String url = SEND_MESSAGE_URL + "?receive_id_type=" + receiveIdType;
            String token = getTenantAccessToken();

            Map<String, Object> body = new HashMap<>();
            body.put("receive_id", receiveId);
            body.put("msg_type", msgType);
            body.put("content", content);

            try (HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .timeout(15000)
                    .execute()) {

                JsonNode json = objectMapper.readTree(response.body());
                int code = json.get("code").asInt();
                if (code != 0) {
                    log.error("发送飞书消息失败: receiveId={}, code={}, msg={}",
                            receiveId, code, json.get("msg").asText());
                } else {
                    log.info("发送飞书消息成功: receiveId={}, msgType={}", receiveId, msgType);
                }
            }

        } catch (Exception e) {
            log.error("发送飞书消息异常: receiveId={}", receiveId, e);
        }
    }
}
