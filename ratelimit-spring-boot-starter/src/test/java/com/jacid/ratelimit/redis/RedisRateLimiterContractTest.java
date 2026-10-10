package com.jacid.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.support.RedisTestServer;

/**
 * Lo que cumple cualquier algoritmo, contra un Redis real. Las politicas son fail-closed para que
 * un error del script rompa el test en vez de convertirse en un "permitido" degradado.
 */
class RedisRateLimiterContractTest {

    private static final Duration LONG = Duration.ofHours(1);

    private final StringRedisTemplate redis = RedisTestServer.newClient();
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, "test", List.of());

    private static RateLimitPolicy policy(RateLimitAlgorithm algorithm, long limit, Duration period) {
        return RateLimitPolicy.of("contract-" + algorithm.code(), algorithm, limit, period).withFailOpen(false);
    }

    private static String freshKey() {
        return UUID.randomUUID().toString();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void allowsExactlyTheLimitAndReportsWhatIsLeft(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 5, LONG);
        String key = freshKey();

        for (long expectedRemaining = 4; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitResult result = limiter.tryAcquire(policy, key);
            assertThat(result.allowed()).isTrue();
            assertThat(result.limit()).isEqualTo(5);
            assertThat(result.remaining()).isEqualTo(expectedRemaining);
            assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
            assertThat(result.degraded()).isFalse();
        }

        RateLimitResult rejected = limiter.tryAcquire(policy, key);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfter()).isPositive();
        assertThat(rejected.degraded()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void waitingRetryAfterIsEnoughToPass(RateLimitAlgorithm algorithm) throws InterruptedException {
        Duration period = Duration.ofMillis(600);
        RateLimitPolicy policy = policy(algorithm, 2, period);
        String key = freshKey();
        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();

        RateLimitResult rejected = limiter.tryAcquire(policy, key);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isPositive().isLessThanOrEqualTo(period.multipliedBy(2));

        Thread.sleep(rejected.retryAfter().toMillis());

        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void keysDoNotShareQuota(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 1, LONG);
        String alice = freshKey();
        String bob = freshKey();

        assertThat(limiter.tryAcquire(policy, alice).allowed()).isTrue();
        assertThat(limiter.tryAcquire(policy, alice).allowed()).isFalse();
        assertThat(limiter.tryAcquire(policy, bob).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void policiesDoNotShareQuota(RateLimitAlgorithm algorithm) {
        RateLimitPolicy login = RateLimitPolicy.of("login", algorithm, 1, LONG).withFailOpen(false);
        RateLimitPolicy search = RateLimitPolicy.of("search", algorithm, 1, LONG).withFailOpen(false);
        String key = freshKey();

        assertThat(limiter.tryAcquire(login, key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(login, key).allowed()).isFalse();
        assertThat(limiter.tryAcquire(search, key).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void aRequestCanCostSeveralPermits(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 10, LONG);
        String key = freshKey();

        assertThat(limiter.tryAcquire(policy, key, 4).remaining()).isEqualTo(6);

        RateLimitResult tooExpensive = limiter.tryAcquire(policy, key, 7);
        assertThat(tooExpensive.allowed()).isFalse();
        assertThat(tooExpensive.remaining()).isEqualTo(6);

        RateLimitResult exact = limiter.tryAcquire(policy, key, 6);
        assertThat(exact.allowed()).isTrue();
        assertThat(exact.remaining()).isZero();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void permitsThatCouldNeverPassAreAProgrammingError(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 3, LONG);
        String key = freshKey();

        assertThatThrownBy(() -> limiter.tryAcquire(policy, key, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(policy, key, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(policy, " ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(policy, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter.tryAcquire(policy, key, 3).allowed()).isTrue();
    }

    /** Sin esperas: con un periodo de una hora, lo unico que puede cambiar la cuota son las propias peticiones. */
    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void aRejectionLeavesTheQuotaExactlyAsItWas(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 10, LONG);
        String key = freshKey();
        assertThat(limiter.tryAcquire(policy, key, 4).remaining()).isEqualTo(6);

        for (int i = 0; i < 5; i++) {
            RateLimitResult rejected = limiter.tryAcquire(policy, key, 7);
            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.remaining()).isEqualTo(6);
        }

        assertThat(limiter.tryAcquire(policy, key, 6).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void rejectedRequestsDoNotConsumeQuota(RateLimitAlgorithm algorithm) throws InterruptedException {
        RateLimitPolicy policy = policy(algorithm, 3, Duration.ofMillis(500));
        String key = freshKey();
        RateLimitResult full = limiter.tryAcquire(policy, key, 3);
        // Se mide desde la respuesta y no desde el envio: resetAfter cuenta desde que Redis proceso la
        // peticion, y medir antes descontaria la latencia (en frio, abrir la conexion) de la espera.
        long start = System.nanoTime();
        assertThat(full.allowed()).isTrue();

        // Repartidos en el tiempo: si un rechazo gastara cuota, el ultimo la retendria 200 ms mas alla
        // de resetAfter. Todos seguidos cabrian en el margen de la espera y el test no distinguiria.
        // Cada intento pide la cuota entera: asi no puede pasar con lo que se va recuperando entretanto.
        int rejections = 0;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(20);
            if (!limiter.tryAcquire(policy, key, 3).allowed()) {
                rejections++;
            }
        }
        assertThat(rejections).isEqualTo(10);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Thread.sleep(Math.max(0, full.resetAfter().toMillis() - elapsedMs) + 20);

        assertThat(limiter.tryAcquire(policy, key, 3).allowed())
                .as("tras resetAfter la cuota entera vuelve a estar disponible, como si no hubiera habido rechazos")
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void stateExpiresOnItsOwn(RateLimitAlgorithm algorithm) throws InterruptedException {
        Duration period = Duration.ofMillis(400);
        RateLimitPolicy policy = policy(algorithm, 2, period);
        String key = freshKey();

        RateLimitResult result = limiter.tryAcquire(policy, key);
        String redisKey = limiter.redisKey(policy, key);

        assertThat(redis.hasKey(redisKey)).isTrue();
        Long ttl = redis.getExpire(redisKey, TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(period.multipliedBy(2).toMillis());
        assertThat(result.resetAfter()).isPositive().isLessThanOrEqualTo(period.multipliedBy(2));

        Thread.sleep(result.resetAfter().toMillis() + 50);

        assertThat(redis.hasKey(redisKey)).as("una clave sin caducidad es una fuga de memoria").isFalse();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void hugeKeysAreHashedButStayDistinct(RateLimitAlgorithm algorithm) {
        RateLimitPolicy policy = policy(algorithm, 1, LONG);
        String prefix = "x".repeat(10_000);
        String one = prefix + freshKey();
        String other = prefix + freshKey();

        assertThat(limiter.redisKey(policy, one)).hasSizeLessThan(200);
        assertThat(limiter.redisKey(policy, one)).isNotEqualTo(limiter.redisKey(policy, other));
        assertThat(limiter.tryAcquire(policy, one).allowed()).isTrue();
        assertThat(limiter.tryAcquire(policy, one).allowed()).isFalse();
        assertThat(limiter.tryAcquire(policy, other).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void changingTheAlgorithmOfAPolicyDoesNotCollideWithOldState(RateLimitAlgorithm algorithm) {
        String key = freshKey();
        for (RateLimitAlgorithm previous : RateLimitAlgorithm.values()) {
            RateLimitPolicy before = RateLimitPolicy.of("migrated", previous, 5, LONG).withFailOpen(false);
            assertThat(limiter.tryAcquire(before, key).allowed()).isTrue();
        }
        RateLimitPolicy after = RateLimitPolicy.of("migrated", algorithm, 5, LONG).withFailOpen(false);

        assertThat(limiter.tryAcquire(after, key).allowed()).isTrue();
    }

    /**
     * La premisa del proyecto: varias instancias de la aplicacion, cada una con su conexion, comparten
     * una unica cuota y entre todas no dejan pasar ni una peticion de mas.
     */
    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void severalInstancesShareOneQuotaExactly(RateLimitAlgorithm algorithm) throws Exception {
        int limit = 50;
        RateLimitPolicy policy = policy(algorithm, limit, LONG);
        List<RateLimiter> instances = new ArrayList<>();
        for (int i = 0; i < ConcurrentLoad.INSTANCES; i++) {
            instances.add(new RedisRateLimiter(RedisTestServer.newClient(), "test", List.of()));
        }

        ConcurrentLoad.Outcome outcome = ConcurrentLoad.run(instances, policy, freshKey());

        assertThat(outcome.attempts()).isGreaterThan(limit * 2);
        assertThat(outcome.allowed()).isEqualTo(limit);
        assertThat(outcome.rejected()).isEqualTo(outcome.attempts() - limit);
    }

    /**
     * Calibracion de la sonda anterior: un limitador que lee y escribe en dos pasos deja pasar de mas
     * bajo la misma carga. Si esta carga no fuera capaz de verlo, el test de arriba no probaria nada.
     */
    @Test
    void theSameLoadCatchesALimiterThatIsNotAtomic() throws Exception {
        int limit = 50;
        RateLimitPolicy policy = policy(RateLimitAlgorithm.TOKEN_BUCKET, limit, LONG);
        int worstOverAdmission = 0;
        for (int round = 0; round < 3 && worstOverAdmission == 0; round++) {
            List<RateLimiter> instances = new ArrayList<>();
            for (int i = 0; i < ConcurrentLoad.INSTANCES; i++) {
                instances.add(new ReadThenWriteLimiter(RedisTestServer.newClient()));
            }
            ConcurrentLoad.Outcome outcome = ConcurrentLoad.run(instances, policy, freshKey());
            worstOverAdmission = Math.max(worstOverAdmission, outcome.allowed() - limit);
        }

        assertThat(worstOverAdmission).as("peticiones aceptadas por encima del limite").isPositive();
    }

    /** Como no hay que hacerlo: GET, comparar en la aplicacion, INCR. Entre el GET y el INCR cabe otra instancia. */
    private static final class ReadThenWriteLimiter implements RateLimiter {

        private final StringRedisTemplate redis;

        ReadThenWriteLimiter(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public RateLimitResult tryAcquire(RateLimitPolicy policy, String key, int permits) {
            String redisKey = "naive:" + key;
            String stored = redis.opsForValue().get(redisKey);
            long used = stored == null ? 0 : Long.parseLong(stored);
            boolean allowed = used + permits <= policy.limit();
            if (allowed) {
                redis.opsForValue().increment(redisKey, permits);
                redis.expire(redisKey, Duration.ofMinutes(1));
            }
            return new RateLimitResult(allowed, policy.limit(), 0, Duration.ZERO, Duration.ZERO, false);
        }
    }
}
