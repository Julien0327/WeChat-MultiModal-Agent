package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "async")
public class AsyncProperties {

    private int corePoolSize;
    private int maxPoolSize;
    private int queueCapacity;
}
