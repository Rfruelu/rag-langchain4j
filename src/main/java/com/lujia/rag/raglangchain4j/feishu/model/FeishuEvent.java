package com.lujia.rag.raglangchain4j.feishu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 飞书事件订阅请求体模型
 * <p>支持飞书 Open Platform v2.0 事件格式，包含 URL 验证挑战和消息事件</p>
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/event-subscription-guide/event-format">飞书事件格式</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuEvent {

    /**
     * 事件类型：url_verification（验证挑战）或 event_callback（事件回调）
     */
    private String type;

    /**
     * URL 验证挑战码（type=url_verification 时存在）
     */
    private String challenge;

    /**
     * 验证 Token，用于校验请求来源
     */
    private String token;

    /**
     * 事件头信息（type=event_callback 时存在）
     */
    private EventHeader header;

    /**
     * 事件体（type=event_callback 时存在）
     */
    private EventBody event;

    /**
     * 事件头
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventHeader(
            /** 事件 ID，用于幂等处理 */
            @JsonProperty("event_id") String eventId,
            /** 事件类型，如 im.message.receive_v1 */
            @JsonProperty("event_type") String eventType,
            /** 事件创建时间（毫秒时间戳字符串） */
            @JsonProperty("create_time") String createTime,
            /** 事件来源的 token */
            @JsonProperty("token") String token,
            /** 应用 ID */
            @JsonProperty("app_id") String appId,
            /** 租户 ID */
            @JsonProperty("tenant_key") String tenantKey) {
    }

    /**
     * 事件体
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventBody(
            /** 发送者信息 */
            @JsonProperty("sender") Sender sender,
            /** 消息内容 */
            @JsonProperty("message") Message message) {
    }

    /**
     * 消息发送者
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sender(
            /** 发送者 ID 信息 */
            @JsonProperty("sender_id") SenderId senderId,
            /** 发送者类型：user */
            @JsonProperty("sender_type") String senderType) {
    }

    /**
     * 发送者 ID
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SenderId(
            /** 用户在应用内的唯一标识 */
            @JsonProperty("open_id") String openId,
            /** 用户在租户内的唯一标识 */
            @JsonProperty("user_id") String userId,
            /** 用户在 union 内的唯一标识 */
            @JsonProperty("union_id") String unionId) {
    }

    /**
     * 消息内容
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            /** 消息 ID */
            @JsonProperty("message_id") String messageId,
            /** 根消息 ID（回复消息时存在） */
            @JsonProperty("root_id") String rootId,
            /** 消息类型：text, post, image 等 */
            @JsonProperty("message_type") String messageType,
            /** 会话 ID */
            @JsonProperty("chat_id") String chatId,
            /** 会话类型：p2p（单聊）或 group（群聊） */
            @JsonProperty("chat_type") String chatType,
            /** 消息内容 JSON 字符串，格式因消息类型而异 */
            @JsonProperty("content") String content,
            /** 创建时间（毫秒时间戳字符串） */
            @JsonProperty("create_time") String createTime,
            /** @提及的用户列表（群聊中 @机器人时存在） */
            @JsonProperty("mentions") String mentions) {
    }

    /**
     * 提及的用户信息（群聊中 @机器人时使用）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mention(
            /** 被提及用户的 ID */
            @JsonProperty("id") MentionId id,
            /** 被提及用户的名称 */
            @JsonProperty("name") String name,
            /** @的 key */
            @JsonProperty("key") String key) {
    }

    /**
     * 提及用户的 ID
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MentionId(
            @JsonProperty("open_id") String openId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("union_id") String unionId) {
    }
}
