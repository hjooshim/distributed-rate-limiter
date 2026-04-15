package com.drl.ratelimiter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ============================================================
 * UNIT TESTS - ClientIdentityResolver
 * ============================================================
 *
 * Verifies the precedence rules used to build the caller identity that later
 * becomes part of each rate-limit key.
 *
 * Resolution order under test:
 *   principal -> trusted forwarded header -> remote address -> unknown-client
 */
class ClientIdentityResolverTest {

    @AfterEach
    void clearRequestContext() {
        // RequestContextHolder is thread-local state. Reset it after each test
        // so one synthetic request never leaks into the next one.
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------------------------------------------------------
    // Principal precedence
    // ---------------------------------------------------------

    @Test
    @DisplayName("Principal should be preferred over forwarded and remote addresses")
    void principalShouldBePreferredOverForwardedAndRemoteAddresses() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();
        properties.setTrustForwardedHeader(true);
        ClientIdentityResolver resolver = new ClientIdentityResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(namedPrincipal("alice"));
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.setRemoteAddr("198.51.100.25");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(resolver.resolveCurrentClientId()).isEqualTo("principal:alice");
    }

    // ---------------------------------------------------------
    // Forwarded-header trust
    // ---------------------------------------------------------

    @Test
    @DisplayName("Forwarded header should be ignored unless trust is enabled")
    void forwardedHeaderShouldBeIgnoredUnlessTrustIsEnabled() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();
        ClientIdentityResolver resolver = new ClientIdentityResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.setRemoteAddr("198.51.100.25");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(resolver.resolveCurrentClientId()).isEqualTo("ip:198.51.100.25");
    }

    @Test
    @DisplayName("Trusted forwarded header should use the first trimmed value")
    void trustedForwardedHeaderShouldUseTheFirstTrimmedValue() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();
        properties.setTrustForwardedHeader(true);
        ClientIdentityResolver resolver = new ClientIdentityResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 203.0.113.10 , 10.0.0.1 ");
        request.setRemoteAddr("198.51.100.25");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(resolver.resolveCurrentClientId()).isEqualTo("ip:203.0.113.10");
    }

    // ---------------------------------------------------------
    // Last-resort fallback
    // ---------------------------------------------------------

    @Test
    @DisplayName("Unknown client should be used only as a last resort")
    void unknownClientShouldBeUsedOnlyAsALastResort() {
        RateLimitIdentityProperties properties = new RateLimitIdentityProperties();
        ClientIdentityResolver resolver = new ClientIdentityResolver(properties);

        assertThat(resolver.resolveCurrentClientId()).isEqualTo("unknown-client");
    }

    private Principal namedPrincipal(String name) {
        return () -> name;
    }
}
