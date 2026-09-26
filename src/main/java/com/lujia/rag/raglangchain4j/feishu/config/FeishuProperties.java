package com.lujia.rag.raglangchain4j.feishu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书机器人应用配置
 * <p>读取 application.yml 中 feishu 前缀的配置项</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /** 飞书应用 App ID */
    private String appId;

    /** 飞书应用 App Secret */
    private String appSecret;

    /** 事件订阅验证 Token（用于校验请求来源） */
    private String verificationToken;

    /** 事件加密密钥（可选，启用后事件 body 为加密内容） */
    private String encryptKey;

    /** MinIO 外部可访问地址，用于飞书卡片中展示图片（替换内部 localhost 地址） */
    private String externalImageBaseUrl;
}
