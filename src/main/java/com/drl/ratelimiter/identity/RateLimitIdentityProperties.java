package com.drl.ratelimiter.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimit.identity")
public class RateLimitIdentityProperties {

    private boolean trustForwardedHeader;
    private String forwardedHeaderName = "X-Forwarded-For";

    public boolean isTrustForwardedHeader() {
        return trustForwardedHeader;
    }

    public void setTrustForwardedHeader(boolean trustForwardedHeader) {
        this.trustForwardedHeader = trustForwardedHeader;
    }

    public String getForwardedHeaderName() {
        return forwardedHeaderName;
    }

    public void setForwardedHeaderName(String forwardedHeaderName) {
        this.forwardedHeaderName = forwardedHeaderName;
    }
}
