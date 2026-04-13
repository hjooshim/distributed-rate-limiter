package com.drl.ratelimiter.identity;

import java.security.Principal;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ClientIdentityResolver {

    private static final String UNKNOWN_CLIENT = "unknown-client";
    private static final String PRINCIPAL_PREFIX = "principal:";
    private static final String IP_PREFIX = "ip:";

    private final RateLimitIdentityProperties properties;

    public ClientIdentityResolver(RateLimitIdentityProperties properties) {
        this.properties = properties;
    }

    public String resolveCurrentClientId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return UNKNOWN_CLIENT;
        }

        String principalId = principalIdentity(servletRequestAttributes.getRequest().getUserPrincipal());
        if (principalId != null) {
            return principalId;
        }

        String forwardedIdentity = forwardedIdentity(
                servletRequestAttributes.getRequest().getHeader(properties.getForwardedHeaderName())
        );
        if (forwardedIdentity != null) {
            return forwardedIdentity;
        }

        String remoteIdentity = ipIdentity(servletRequestAttributes.getRequest().getRemoteAddr());
        if (remoteIdentity != null) {
            return remoteIdentity;
        }

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

        return Arrays.stream(forwardedFor.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
