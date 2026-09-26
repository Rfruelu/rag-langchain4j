package com.lujia.rag.raglangchain4j.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BailianProperties {

    private final String apiKey;
    private final String baseUrl;

    public BailianProperties(@Value("${bailian.api-key}") String apiKey,
                             @Value("${bailian.base-url}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
