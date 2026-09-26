package com.lujia.rag.raglangchain4j.common.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * AI 对话限流器
 * <p>基于 Redisson RRateLimiter 实现滑动窗口限流，防止恶意刷量耗尽百炼配额</p>
 * <p>分桶键优先级：登录用户 {@code u:loginId} → 来源 IP {@code ip:地址} → 会话 {@code conv:会话ID}。
 * 飞书机器人等无 Sa-Token 上下文的入口在异步线程内处理，取不到请求 IP，因此落到会话桶，
 * 不同会话互不影响</p>
 */
@Slf4j
@Component
public class ChatRateLimiter {

    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:chat:";
    private static final String SHARED_BUCKET = "shared";

    private final RedissonClient redissonClient;

    /** 窗口内允许的请求数 */
    @Value("${rag.chat.rate-limit-permits:10}")
    private long permits;

    /** 窗口时长（秒） */
    @Value("${rag.chat.rate-limit-seconds:10}")
    private long windowSeconds;

    public ChatRateLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 检查本次对话请求是否放行，超限抛出 {@link RateLimitExceededException}
     *
     * @param conversationId 会话业务 ID，作为最低优先级的分桶依据
     */
    public void checkChatAllowed(String conversationId) {
        String bucket = resolveBucket(conversationId);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(RATE_LIMIT_KEY_PREFIX + bucket);
        applyRate(rateLimiter);
        if (!rateLimiter.tryAcquire()) {
            log.warn("对话限流触发: bucket={}, permits={}/{}s", bucket, permits, windowSeconds);
            throw new RateLimitExceededException("请求过于频繁，请稍后再试");
        }
    }

    /**
     * 解析分桶键：登录用户优先，其次来源 IP，最后回落到会话
     */
    private String resolveBucket(String conversationId) {
        try {
            Object loginId = StpUtil.getLoginId();
            if (loginId != null) {
                return "u:" + loginId;
            }
        } catch (Exception e) {
            log.debug("获取登录用户失败，改用 IP/会话分桶: {}", e.getMessage());
        }

        String clientIp = resolveClientIp();
        if (clientIp != null) {
            return "ip:" + clientIp;
        }

        return conversationId != null && !conversationId.isBlank()
                ? "conv:" + conversationId
                : SHARED_BUCKET;
    }

    /**
     * 取直连客户端地址
     * <p>刻意不读 X-Forwarded-For：该头可被客户端伪造，读它等于给了绕过限流的入口。
     * 反向代理部署下所有请求 IP 相同，需配置 {@code server.forward-headers-strategy} 由容器统一解析</p>
     */
    private String resolveClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String remoteAddr = attributes.getRequest().getRemoteAddr();
            if (remoteAddr != null && !remoteAddr.isBlank()) {
                return remoteAddr;
            }
        }
        return null;
    }

    /**
     * 将限流配置同步到 Redis
     * <p>{@code trySetRate} 只在键不存在时写入，会导致改过配置后老键永久沿用旧速率，
     * 因此这里比对现有配置，不一致时用 {@code setRate} 覆盖</p>
     */
    private void applyRate(RRateLimiter rateLimiter) {
        long intervalMillis = windowSeconds * 1000L;
        RateLimiterConfig current = rateLimiter.getConfig();
        if (current == null) {
            rateLimiter.trySetRate(RateType.OVERALL, permits, windowSeconds, RateIntervalUnit.SECONDS);
            return;
        }
        boolean changed = current.getRate() == null || current.getRateInterval() == null
                || current.getRate().longValue() != permits
                || current.getRateInterval().longValue() != intervalMillis;
        if (changed) {
            log.info("对话限流配置变更，重置速率桶: permits={}/{}s", permits, windowSeconds);
            rateLimiter.setRate(RateType.OVERALL, permits, windowSeconds, RateIntervalUnit.SECONDS);
        }
    }
}
