package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm.dashscope")
public class LlmProperties {

    private String apiKey;
    private String baseUrl;
    private String textModel;
    private String visionModel;
    private String imageModel;
    private String ttsModel;
}
