package com.drl.ratelimiter.identity;

import java.security.Principal;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ============================================================
 * IDENTITY RESOLUTION - Build the caller portion of rate-limit keys
 * ============================================================
 *
 * This class decides "who is the client?" for the entire rate-limiter path.
 * The returned value becomes part of the logical rate-limit key, so the
 * precedence rules here directly determine which requests share quota.
 *
 * Resolution order:
 *   1. authenticated principal
 *   2. trusted forwarded header value
 *   3. remote IP address
 *   4. unknown-client fallback
 *
 * Why prefixes matter:
 *   A principal and an IP address might have the same raw text, but they
 *   should never collide in the same bucket. Prefixes like principal:alice
 *   and ip:203.0.113.10 keep those namespaces separate.
 *
 * Security note:
 *   Forwarded headers are optional because they should be trusted only when
 *   the application sits behind infrastructure that sanitizes or controls
 *   those headers. Otherwise, a client could spoof its own identity.
 */
@Component
public class ClientIdentityResolver {

    private static final String UNKNOWN_CLIENT = "unknown-client";
    private static final String PRINCIPAL_PREFIX = "principal:";
    private static final String IP_PREFIX = "ip:";

    private final RateLimitIdentityProperties properties;

    /**
     * Creates a resolver using the configured identity properties.
     *
     * @param properties rate-limit identity configuration
     */
    public ClientIdentityResolver(RateLimitIdentityProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves the client id for the active HTTP request context.
     *
     * @return namespaced client id such as {@code principal:alice} or {@code ip:203.0.113.10}
     */
    public String resolveCurrentClientId() {
        // If this code runs outside an HTTP request (for example in a unit
        // test or background thread), there is no request-bound identity.
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return UNKNOWN_CLIENT;
        }

        // First preference: authenticated user identity.
        String principalId = principalIdentity(servletRequestAttributes.getRequest().getUserPrincipal());
        if (principalId != null) {
            return principalId;
        }

        // Second preference: forwarded identity, but only when configuration
        // explicitly says that header can be trusted.
        String forwardedIdentity = forwardedIdentity(
                servletRequestAttributes.getRequest().getHeader(properties.getForwardedHeaderName())
        );
        if (forwardedIdentity != null) {
            return forwardedIdentity;
        }

        // Third preference: direct remote address from the current request.
        String remoteIdentity = ipIdentity(servletRequestAttributes.getRequest().getRemoteAddr());
        if (remoteIdentity != null) {
            return remoteIdentity;
        }

        // Last resort: keep the key non-null and deterministic even when no
        // caller identity can be resolved at all.
        return UNKNOWN_CLIENT;
    }

    private String principalIdentity(Principal principal) {
        if (principal == null) {
            return null;
        }
        String name = principal.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return PRINCIPAL_PREFIX + name.trim();
    }

    private String forwardedIdentity(String forwardedHeader) {
        // Only trust forwarded headers when the deployment environment is
        // known to sanitize them correctly.
        if (!properties.isTrustForwardedHeader()) {
            return null;
        }

        String forwardedClient = firstForwardedValue(forwardedHeader);
        if (forwardedClient == null) {
            return null;
        }

        return IP_PREFIX + forwardedClient;
    }

    private String ipIdentity(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return null;
        }
        return IP_PREFIX + rawIp.trim();
    }

    private String firstForwardedValue(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }

        // X-Forwarded-For is typically a comma-separated chain.
        // The left-most value represents the original client.
        return Arrays.stream(forwardedFor.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
