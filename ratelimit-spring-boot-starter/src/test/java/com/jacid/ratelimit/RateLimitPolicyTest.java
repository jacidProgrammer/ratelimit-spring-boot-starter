package com.jacid.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void rejectsValuesThatWouldMeanNothing() {
        assertInvalid(() -> RateLimitPolicy.of("p", RateLimitAlgorithm.TOKEN_BUCKET, 0, MINUTE), "limit");
        assertInvalid(() -> RateLimitPolicy.of("p", RateLimitAlgorithm.TOKEN_BUCKET, 5, Duration.ZERO), "period");
        assertInvalid(() -> RateLimitPolicy.of("p", RateLimitAlgorithm.TOKEN_BUCKET, 5, null), "period");
        assertInvalid(() -> RateLimitPolicy.of("p", null, 5, MINUTE), "algoritmo");
        assertInvalid(() -> RateLimitPolicy.tokenBucket("p", 5, MINUTE, 0), "burst");
        assertInvalid(() -> RateLimitPolicy.of("con espacio", RateLimitAlgorithm.TOKEN_BUCKET, 5, MINUTE), "Nombre");
        assertInvalid(() -> RateLimitPolicy.of("a:b", RateLimitAlgorithm.TOKEN_BUCKET, 5, MINUTE), "Nombre");
        assertInvalid(() -> RateLimitPolicy.of(null, RateLimitAlgorithm.TOKEN_BUCKET, 5, MINUTE), "Nombre");
    }

    @Test
    void burstOnlyExistsInTheTokenBucket() {
        assertThat(RateLimitPolicy.tokenBucket("p", 5, MINUTE, 20).capacity()).isEqualTo(20);
        for (RateLimitAlgorithm algorithm :
                List.of(RateLimitAlgorithm.SLIDING_WINDOW_LOG, RateLimitAlgorithm.SLIDING_WINDOW_COUNTER)) {
            assertThat(RateLimitPolicy.of("p", algorithm, 5, MINUTE).capacity()).isEqualTo(5);
            assertInvalid(() -> new RateLimitPolicy("p", algorithm, 5, MINUTE, 20, true), "burst");
        }
    }

    @Test
    void anUnknownPolicyIsAnErrorNotAnAbsenceOfLimit() {
        RateLimitPolicyRegistry registry = new RateLimitPolicyRegistry(
                List.of(RateLimitPolicy.of("login", RateLimitAlgorithm.TOKEN_BUCKET, 5, MINUTE)));

        assertThat(registry.get("login").limit()).isEqualTo(5);
        assertThat(registry.contains("logni")).isFalse();
        assertThatThrownBy(() -> registry.get("logni"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logni")
                .hasMessageContaining("login");
    }

    @Test
    void duplicatedNamesAreRejected() {
        RateLimitPolicy policy = RateLimitPolicy.of("login", RateLimitAlgorithm.TOKEN_BUCKET, 5, MINUTE);

        assertThatThrownBy(() -> new RateLimitPolicyRegistry(List.of(policy, policy)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalid(Runnable construction, String mention) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mention);
    }
}
