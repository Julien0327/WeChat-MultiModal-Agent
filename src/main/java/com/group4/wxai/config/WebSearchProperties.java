package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "web-search.tavily")
public class WebSearchProperties {

    private String apiKey;
    private String baseUrl = "https://api.tavily.com";
    private int maxResults = 5;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public int effectiveMaxResults() {
        return Math.max(1, Math.min(maxResults, 5));
    }
}
