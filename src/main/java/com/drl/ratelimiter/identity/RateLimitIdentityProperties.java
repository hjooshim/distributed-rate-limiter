package com.drl.ratelimiter.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * CONFIGURATION PROPERTIES - Client identity policy
 * ============================================================
 *
 * This class exposes the configuration knobs that control how
 * {@link ClientIdentityResolver} interprets caller identity.
 *
 * Two settings matter:
 *   - trustForwardedHeader: whether forwarded headers may influence identity
 *   - forwardedHeaderName: which header should be inspected when trust is on
 *
 * Keeping this policy in configuration allows the same application code to run
 * safely in different environments:
 *   - direct local development: trustForwardedHeader = false
 *   - trusted reverse proxy / load balancer: trustForwardedHeader = true
 */
@Component
@ConfigurationProperties(prefix = "ratelimit.identity")
public class RateLimitIdentityProperties {

    private boolean trustForwardedHeader;
    private String forwardedHeaderName = "X-Forwarded-For";

    /**
     * Whether the configured forwarded header should be trusted for client identity.
     *
     * @return {@code true} when forwarded headers are trusted
     */
    public boolean isTrustForwardedHeader() {
        return trustForwardedHeader;
    }

    /**
     * Sets whether the configured forwarded header should be trusted.
     *
     * @param trustForwardedHeader {@code true} to trust the forwarded header
     */
    public void setTrustForwardedHeader(boolean trustForwardedHeader) {
        this.trustForwardedHeader = trustForwardedHeader;
    }

    /**
     * Returns the header name used when forwarded-header trust is enabled.
     *
     * @return forwarded header name
     */
    public String getForwardedHeaderName() {
        return forwardedHeaderName;
    }

    /**
     * Sets the header name used when forwarded-header trust is enabled.
     *
     * @param forwardedHeaderName forwarded header name
     */
    public void setForwardedHeaderName(String forwardedHeaderName) {
        this.forwardedHeaderName = forwardedHeaderName;
    }
}
