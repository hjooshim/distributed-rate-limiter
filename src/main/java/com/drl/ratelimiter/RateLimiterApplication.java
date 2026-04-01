package com.drl.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the distributed rate limiter Spring Boot application.
 *
 * <p>This bootstrap class enables component scanning for the infrastructure
 * modules that will be added in later commits.
 */
@SpringBootApplication
public class RateLimiterApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args raw command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
