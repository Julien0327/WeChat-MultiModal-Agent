package com.group4.wxai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ilink")
public class ILinkProperties {

    private boolean enabled = true;
    private String adminToken = "";
    private String sessionFile = "data/ilink-session.json";
    private Duration pollErrorBackoff = Duration.ofSeconds(3);
    private Duration loginTimeout = Duration.ofMinutes(3);
    private String channelVersion = "1.0.0";

    public Duration effectivePollErrorBackoff() {
        return pollErrorBackoff == null || pollErrorBackoff.isNegative() || pollErrorBackoff.isZero()
                ? Duration.ofSeconds(3) : pollErrorBackoff;
    }

    public Duration effectiveLoginTimeout() {
        return loginTimeout == null || loginTimeout.isNegative() || loginTimeout.isZero()
                ? Duration.ofMinutes(3) : loginTimeout;
    }
}
