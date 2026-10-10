package com.jacid.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitObserver;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiterUnavailableException;
import com.jacid.ratelimit.support.RedisTestServer;

/** Que pasa cuando Redis no esta, y quien se entera. */
class FailurePolicyTest {

    private static StringRedisTemplate deadRedis() {
        return RedisTestServer.newClient("127.0.0.1", closedPort(), Duration.ofMillis(300));
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void failOpenLetsTheRequestThroughAndSaysItDidNotCount(RateLimitAlgorithm algorithm) {
        RedisRateLimiter limiter = new RedisRateLimiter(deadRedis(), "test", List.of());
        RateLimitPolicy policy = RateLimitPolicy.of("open", algorithm, 1, Duration.ofHours(1)).withFailOpen(true);

        for (int i = 0; i < 3; i++) {
            RateLimitResult result = limiter.tryAcquire(policy, "key");
            assertThat(result.allowed()).isTrue();
            assertThat(result.degraded()).isTrue();
            assertThat(result.remaining()).as("no se sabe cuanto queda: no se inventa").isEqualTo(-1);
        }
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void failClosedRefusesInsteadOfLettingUncountedRequestsIn(RateLimitAlgorithm algorithm) {
        RedisRateLimiter limiter = new RedisRateLimiter(deadRedis(), "test", List.of());
        RateLimitPolicy policy = RateLimitPolicy.of("closed", algorithm, 1, Duration.ofHours(1)).withFailOpen(false);

        assertThatThrownBy(() -> limiter.tryAcquire(policy, "key"))
                .isInstanceOf(RateLimiterUnavailableException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void corruptedStateFollowsTheSameFailurePolicy() {
        StringRedisTemplate redis = RedisTestServer.newClient();
        RedisRateLimiter limiter = new RedisRateLimiter(redis, "test", List.of());
        String key = UUID.randomUUID().toString();
        RateLimitPolicy open =
                RateLimitPolicy.of("corrupt", RateLimitAlgorithm.TOKEN_BUCKET, 1, Duration.ofHours(1));
        redis.opsForValue().set(limiter.redisKey(open, key), "esto no es un hash", Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire(open, key).degraded()).isTrue();
        assertThatThrownBy(() -> limiter.tryAcquire(open.withFailOpen(false), key))
                .isInstanceOf(RateLimiterUnavailableException.class);
    }

    @Test
    void observersSeeEveryVerdictIncludingDegradedOnes() {
        List<String> seen = new ArrayList<>();
        RateLimitObserver broken = (policy, result) -> {
            throw new IllegalStateException("un observador roto no puede tumbar la peticion");
        };
        RateLimitObserver recorder = (policy, result) -> seen.add(
                policy.name() + ":" + (result.degraded() ? "degraded" : result.allowed() ? "allowed" : "rejected"));
        String key = UUID.randomUUID().toString();
        RateLimitPolicy policy = RateLimitPolicy.of("seen", RateLimitAlgorithm.TOKEN_BUCKET, 1, Duration.ofHours(1));

        RedisRateLimiter healthy = new RedisRateLimiter(RedisTestServer.newClient(), "test", List.of(broken, recorder));
        healthy.tryAcquire(policy, key);
        healthy.tryAcquire(policy, key);
        new RedisRateLimiter(deadRedis(), "test", List.of(broken, recorder)).tryAcquire(policy, key);

        assertThat(seen).containsExactly("seen:allowed", "seen:rejected", "seen:degraded");
    }

    @Test
    void observersAlsoSeeTheRefusalsOfAFailClosedPolicy() {
        List<String> seen = new ArrayList<>();
        RateLimitObserver recorder =
                (policy, result) -> seen.add(result.allowed() + ":" + result.degraded());
        RateLimitPolicy policy = RateLimitPolicy.of("seen", RateLimitAlgorithm.TOKEN_BUCKET, 1, Duration.ofHours(1))
                .withFailOpen(false);
        RedisRateLimiter limiter = new RedisRateLimiter(deadRedis(), "test", List.of(recorder));

        assertThatThrownBy(() -> limiter.tryAcquire(policy, "key"))
                .isInstanceOf(RateLimiterUnavailableException.class);

        assertThat(seen).as("una caida que rechaza trafico tiene que verse en las metricas").containsExactly("false:true");
    }
}
