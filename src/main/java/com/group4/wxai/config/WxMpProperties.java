package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "wx.mp")
public class WxMpProperties {

    private String appId;
    private String appSecret;
    private String token;
    private String aesKey;
}
