package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "weather.amap")
public class WeatherProperties {

    private String apiKey;
    private String baseUrl = "https://restapi.amap.com/v3";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
