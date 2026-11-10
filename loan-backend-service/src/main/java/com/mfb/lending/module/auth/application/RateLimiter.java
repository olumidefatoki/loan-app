package com.mfb.lending.module.auth.application;

import java.time.Duration;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.module.auth.persistence.LoginAttemptRepository;
import com.mfb.lending.module.auth.security.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RedisTemplate<String, String> redis;
    private final LoginAttemptRepository attempts;
    private final Clock clock;

    public RateLimiter(
            RedisTemplate<String, String> redis,
            LoginAttemptRepository attempts,
            Clock clock) {
        this.redis = redis;
        this.attempts = attempts;
        this.clock = clock;
    }

    public void assertAllowed(String action, String ipAddress, String identifier, int maxAttempts, Duration window) {
        assertAllowed(action, ipAddress, identifier, maxAttempts, window, false);
    }

    public void assertLoginAllowed(String ipAddress, String identifier, int maxAttempts, Duration window) {
        assertAllowed("login", ipAddress, identifier, maxAttempts, window, true);
    }

    private void assertAllowed(
            String action,
            String ipAddress,
            String identifier,
            int maxAttempts,
            Duration window,
            boolean databaseFallback) {
        String ip = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
        try {
            long ipHits = increment("auth:rl:" + action + ":ip:" + ip, window);
            long identifierHits = increment("auth:rl:" + action + ":id:" + TokenHasher.sha256(identifier), window);
            if (ipHits > maxAttempts || identifierHits > maxAttempts) {
                throw limited();
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Rate limit store unavailable for {}", action);
            if (!databaseFallback) {
                throw new ApiException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The request could not be completed");
            }
            long recorded = attempts.countSince(ip, identifier, clock.instant().minus(window));
            if (recorded >= maxAttempts) {
                throw limited();
            }
        }
    }

    private long increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        long hits = count == null ? 1L : count;
        if (hits == 1L) {
            redis.expire(key, window);
        }
        return hits;
    }

    private static ApiException limited() {
        return new ApiException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later");
    }
}
