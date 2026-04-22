package com.drl.ratelimiter.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for rate-limit identity configuration properties.
 * These checks keep invalid configuration from leaking into request handling.
 */
class RateLimitIdentityPropertiesTest {

    @Test
    @DisplayName("Blank forwarded header names should be rejected")
    void blankForwardedHeaderNamesShouldBeRejected() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();

        assertThatThrownBy(() -> properties.setForwardedHeaderName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forwardedHeaderName");
    }

    @Test
    @DisplayName("Forwarded header names should be trimmed before storage")
    void forwardedHeaderNamesShouldBeTrimmedBeforeStorage() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();

        properties.setForwardedHeaderName("  X-Real-IP  ");

        assertThat(properties.getForwardedHeaderName()).isEqualTo("X-Real-IP");
    }
}
