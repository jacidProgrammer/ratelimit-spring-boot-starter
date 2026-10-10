package com.jacid.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RateLimitInterceptorTest {

    @Test
    void secondsAreRoundedUpSoTheClientNeverRetriesTooEarly() {
        assertThat(RateLimitInterceptor.ceilSeconds(Duration.ZERO)).isZero();
        assertThat(RateLimitInterceptor.ceilSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(RateLimitInterceptor.ceilSeconds(Duration.ofMillis(1000))).isEqualTo(1);
        assertThat(RateLimitInterceptor.ceilSeconds(Duration.ofMillis(1001))).isEqualTo(2);
    }
}
