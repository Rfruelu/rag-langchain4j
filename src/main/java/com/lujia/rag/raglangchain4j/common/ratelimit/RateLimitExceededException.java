package com.lujia.rag.raglangchain4j.common.ratelimit;

/**
 * 触发限流时抛出，由全局异常处理器映射为 HTTP 429
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
