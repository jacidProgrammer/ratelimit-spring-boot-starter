package com.jacid.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.support.RedisTestServer;

/** Lo que distingue a cada algoritmo de los otros dos. */
class AlgorithmBehaviourTest {

    private final StringRedisTemplate redis = RedisTestServer.newClient();
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, "test", List.of());

    private static String freshKey() {
        return UUID.randomUUID().toString();
    }

    private int drain(RateLimitPolicy policy, String key, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(policy, key).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    // --- Cubo de fichas ---

    @Test
    void tokenBucketCapsTheBurstBelowTheSustainedRate() throws InterruptedException {
        RateLimitPolicy policy =
                RateLimitPolicy.tokenBucket("burst", 10, Duration.ofSeconds(1), 3).withFailOpen(false);
        String key = freshKey();

        assertThat(limiter.tryAcquire(policy, key).limit()).as("el limite visible es la capacidad").isEqualTo(3);
        assertThat(drain(policy, key, 2)).isEqualTo(2);

        RateLimitResult rejected = limiter.tryAcquire(policy, key);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter())
                .as("10 fichas por segundo: la siguiente llega como mucho en 100 ms")
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMillis(100));

        Thread.sleep(rejected.retryAfter().toMillis());
        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();
    }

    @Test
    void tokenBucketRefillsGraduallyNotAllAtOnce() throws InterruptedException {
        RateLimitPolicy policy =
                RateLimitPolicy.tokenBucket("refill", 10, Duration.ofSeconds(1), 10).withFailOpen(false);
        String key = freshKey();
        assertThat(limiter.tryAcquire(policy, key, 10).allowed()).isTrue();

        Thread.sleep(350);
        int allowed = drain(policy, key, 10);

        assertThat(allowed).as("en 350 ms a 10 fichas/s hay 3 o 4 fichas, no 0 ni 10").isBetween(3, 5);
    }

    @Test
    void tokenBucketClampsStoredTokensWhenCapacityShrinks() {
        String key = freshKey();
        RateLimitPolicy wide =
                RateLimitPolicy.tokenBucket("shrunk", 10, Duration.ofHours(1), 10).withFailOpen(false);
        RateLimitPolicy narrow =
                RateLimitPolicy.tokenBucket("shrunk", 10, Duration.ofHours(1), 4).withFailOpen(false);
        assertThat(limiter.tryAcquire(wide, key).remaining()).isEqualTo(9);

        assertThat(limiter.tryAcquire(narrow, key).remaining()).isEqualTo(3);
    }

    // --- Ventana deslizante exacta ---

    /**
     * El fallo clasico de la ventana fija es dejar pasar el doble en la frontera entre dos ventanas.
     * Aqui se martillea durante mas de tres periodos y se comprueba que ningun intervalo de un periodo
     * contiene mas aceptadas que el limite.
     */
    @Test
    void slidingLogNeverExceedsTheLimitInAnyWindow() throws Exception {
        int limit = 5;
        Duration period = Duration.ofMillis(500);
        Duration latencySlack = Duration.ofMillis(40);
        RateLimitPolicy policy = RateLimitPolicy.of("exact", RateLimitAlgorithm.SLIDING_WINDOW_LOG, limit, period)
                .withFailOpen(false);
        String key = freshKey();
        List<Long> acceptedAt = Collections.synchronizedList(new ArrayList<>());
        long deadline = System.nanoTime() + period.multipliedBy(3).toNanos() + TimeUnit.MILLISECONDS.toNanos(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            tasks.add(pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    long sentAt = System.nanoTime();
                    if (limiter.tryAcquire(policy, key).allowed()) {
                        acceptedAt.add(sentAt);
                    }
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(acceptedAt);
        Collections.sort(sorted);
        assertThat(sorted).as("sin aceptadas el test no prueba nada").hasSizeGreaterThanOrEqualTo(limit * 3);
        long window = period.minus(latencySlack).toNanos();
        for (int i = 0; i < sorted.size(); i++) {
            long from = sorted.get(i);
            long inWindow = sorted.stream().filter(at -> at >= from && at < from + window).count();
            assertThat(inWindow).isLessThanOrEqualTo(limit);
        }
    }

    @Test
    void slidingLogFreesEachSlotWhenItsOwnRequestAges() throws InterruptedException {
        Duration period = Duration.ofMillis(600);
        RateLimitPolicy policy = RateLimitPolicy.of("aging", RateLimitAlgorithm.SLIDING_WINDOW_LOG, 2, period)
                .withFailOpen(false);
        String key = freshKey();
        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();
        Thread.sleep(300);
        assertThat(limiter.tryAcquire(policy, key).allowed()).isTrue();

        RateLimitResult rejected = limiter.tryAcquire(policy, key);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter())
                .as("se libera un hueco cuando caduca la primera, no cuando caducan todas")
                .isLessThanOrEqualTo(Duration.ofMillis(300));
        assertThat(rejected.resetAfter()).isGreaterThan(Duration.ofMillis(300));

        // La clave sigue viva por la segunda entrada: lo unico que libera el hueco es olvidar la primera.
        Thread.sleep(rejected.retryAfter().toMillis());
        RateLimitResult afterAging = limiter.tryAcquire(policy, key);
        assertThat(afterAging.allowed()).isTrue();
        assertThat(afterAging.remaining()).isZero();
    }

    // --- Ventana deslizante aproximada ---

    @Test
    void slidingCounterWeighsThePreviousWindowByElapsedTime() throws InterruptedException {
        int limit = 100;
        long periodMs = 1000;
        RateLimitPolicy policy = RateLimitPolicy.of(
                        "weighted", RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, limit, Duration.ofMillis(periodMs))
                .withFailOpen(false);
        String key = freshKey();

        // Las ventanas estan alineadas con el reloj de Redis: se llena una entera al principio.
        long now = redisTimeMs();
        long windowStart = (now / periodMs + 1) * periodMs;
        Thread.sleep(windowStart - now + 50);
        assertThat(limiter.tryAcquire(policy, key, limit).allowed()).isTrue();
        assertThat(limiter.tryAcquire(policy, key).allowed()).isFalse();

        // A mitad de la ventana siguiente la anterior pesa la mitad.
        Thread.sleep(windowStart + periodMs + periodMs / 2 - redisTimeMs());
        int allowed = drain(policy, key, limit);

        assertThat(allowed).as("100 * (1 - 0.5) ocupadas: caben unas 50").isBetween(45, 65);
    }

    @Test
    void slidingCounterStartsOverWhenThePeriodChanges() {
        String key = freshKey();
        assertThat(drain(counter("reperiod", Duration.ofHours(1)), key, 3)).isEqualTo(2);

        assertThat(limiter.tryAcquire(counter("reperiod", Duration.ofDays(1)), key).remaining())
                .as("un indice de ventana de otro periodo no significa nada: se empieza de cero")
                .isEqualTo(1);
    }

    /**
     * El caso anterior se resuelve solo porque los indices de ventana son distintos. Aqui se eligen dos
     * periodos cuyo indice coincide ahora mismo (el 10): sin comparar el periodo guardado, los
     * contadores del periodo viejo se tomarian por los de la ventana actual.
     */
    @Test
    void slidingCounterStartsOverEvenIfTheWindowIndexHappensToMatch() {
        long now = redisTimeMs();
        Duration before = Duration.ofMillis((long) (now / 10.5));
        Duration after = Duration.ofMillis((long) (now / 10.4));
        assertThat(now / before.toMillis()).isEqualTo(10);
        assertThat(now / after.toMillis()).isEqualTo(10);
        String key = freshKey();
        assertThat(drain(counter("reindexed", before), key, 3)).isEqualTo(2);

        assertThat(limiter.tryAcquire(counter("reindexed", after), key).remaining()).isEqualTo(1);
    }

    /**
     * La caducidad de la clave es limpieza, no parte del calculo: si la clave sobrevive (aqui se le
     * quita la caducidad a mano), unos contadores de hace tres ventanas no pueden seguir contando.
     */
    @Test
    void slidingCounterIgnoresWindowsOlderThanThePreviousOne() throws InterruptedException {
        RateLimitPolicy policy = counter("stale", Duration.ofMillis(100));
        String key = freshKey();
        assertThat(drain(policy, key, 3)).isEqualTo(2);
        assertThat(redis.persist(limiter.redisKey(policy, key))).isTrue();

        Thread.sleep(350);

        assertThat(redis.hasKey(limiter.redisKey(policy, key))).isTrue();
        assertThat(limiter.tryAcquire(policy, key).remaining()).isEqualTo(1);
    }

    private static RateLimitPolicy counter(String name, Duration period) {
        return RateLimitPolicy.of(name, RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, 2, period).withFailOpen(false);
    }

    private long redisTimeMs() {
        return redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
    }
}
