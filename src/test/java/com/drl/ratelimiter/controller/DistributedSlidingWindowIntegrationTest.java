package com.drl.ratelimiter.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.drl.ratelimiter.RateLimiterApplication;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ============================================================
 * INTEGRATION TESTS - Distributed sliding-window behavior
 * ============================================================
 *
 * Starts two independent Spring application contexts against one shared Redis
 * container to verify that sliding-window state is truly distributed.
 *
 * Coverage:
 *   - two app nodes should share one quota for the same client
 *   - endpoint isolation must still hold across different nodes
 */
@Testcontainers
class DistributedSlidingWindowIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @AfterEach
    void clearRedis() {
        // Clear Redis between tests so state created by one pair of app nodes
        // never affects the next scenario.
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379))
        );
        connectionFactory.afterPropertiesSet();

        try (var connection = connectionFactory.getConnection()) {
            connection.flushAll();
        } finally {
            connectionFactory.destroy();
        }
    }

    // ---------------------------------------------------------
    // Shared quota across nodes
    // ---------------------------------------------------------

    @Test
    @DisplayName("Two app nodes should share the same sliding-window quota for one client")
    void twoAppNodesShouldShareTheSameSlidingWindowQuotaForOneClient() {
        try (
                ConfigurableApplicationContext nodeA = startNode("node-a");
                ConfigurableApplicationContext nodeB = startNode("node-b")
        ) {
            // Fill all three slots from node-A and node-B alternately.
            // Because both nodes write to the same Redis sorted set, the
            // window fills up regardless of which node handles each request.
            assertThat(call(nodeA, "/api/sliding-window/primary", "203.0.113.60").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(call(nodeB, "/api/sliding-window/primary", "203.0.113.60").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(call(nodeA, "/api/sliding-window/primary", "203.0.113.60").getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            // The fourth request — regardless of which node receives it — must
            // be rejected because the shared sorted set already holds three entries.
            assertThat(call(nodeB, "/api/sliding-window/primary", "203.0.113.60").getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ---------------------------------------------------------
    // Endpoint isolation across nodes
    // ---------------------------------------------------------

    @Test
    @DisplayName("Endpoint isolation should still hold when requests hit different app nodes")
    void endpointIsolationShouldStillHoldWhenRequestsHitDifferentAppNodes() {
        try (
                ConfigurableApplicationContext nodeA = startNode("node-a");
                ConfigurableApplicationContext nodeB = startNode("node-b")
        ) {
            // Exhaust the primary quota entirely from node-A.
            for (int i = 0; i < 3; i++) {
                assertThat(call(nodeA, "/api/sliding-window/primary", "203.0.113.61").getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
            assertThat(call(nodeA, "/api/sliding-window/primary", "203.0.113.61").getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // The secondary endpoint uses a separate Redis key, so its window
            // is empty even when the request arrives at a different node.
            assertThat(call(nodeB, "/api/sliding-window/secondary", "203.0.113.61").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    private ConfigurableApplicationContext startNode(String nodeName) {
        // Each context behaves like a separate application node, but both point
        // to the same Redis backend so window state is shared.
        return new SpringApplicationBuilder(RateLimiterApplication.class)
                .properties(
                        "server.port=0",
                        "spring.application.name=distributed-rate-limiter-" + nodeName,
                        "ratelimit.identity.trust-forwarded-header=true",
                        "spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379)
                )
                .run();
    }

    private ResponseEntity<String> call(
            ConfigurableApplicationContext context,
            String path,
            String clientIp
    ) {
        // Trusting X-Forwarded-For in these tests lets us simulate one logical
        // client consistently across multiple app nodes.
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", clientIp);

        return restTemplate.exchange(
                baseUrl(context) + path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }
}
