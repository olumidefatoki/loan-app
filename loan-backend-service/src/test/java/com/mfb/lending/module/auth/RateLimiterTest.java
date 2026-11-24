package com.mfb.lending.module.auth;

import java.time.Clock;
import java.time.Duration;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.module.auth.application.RateLimiter;
import com.mfb.lending.module.auth.persistence.LoginAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void aCounterWithoutATtlIsGivenOneAndThenLimited() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(2L, 11L);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        RateLimiter limiter = new RateLimiter(redis, mock(LoginAttemptRepository.class), Clock.systemUTC());

        limiter.assertKeyed("api", "client-1", 10, Duration.ofMinutes(1));
        verify(redis).expire(anyString(), eq(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> limiter.assertKeyed("api", "client-1", 10, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRedisOutageFailsClosed() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        RateLimiter limiter = new RateLimiter(redis, mock(LoginAttemptRepository.class), Clock.systemUTC());

        assertThatThrownBy(() -> limiter.assertKeyed("api", "client-1", 10, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.SERVICE_UNAVAILABLE).isNotNull();
    }
}
