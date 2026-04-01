package com.drl.ratelimiter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that should be checked by the rate limiter.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * Maximum number of requests allowed during one window.
     *
     * @return request limit for the protected method
     */
    int limit();

    /**
     * Duration of the rate-limit window in milliseconds.
     *
     * @return window size in milliseconds
     */
    long windowMs();
}
