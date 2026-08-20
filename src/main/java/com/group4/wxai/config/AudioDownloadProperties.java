package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "audio-download")
public class AudioDownloadProperties {

    private String publicBaseUrl = "";
    private Duration ttl = Duration.ofMinutes(30);

    public boolean isConfigured() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(publicBaseUrl.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public Duration effectiveTtl() {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(30) : ttl;
    }
}
