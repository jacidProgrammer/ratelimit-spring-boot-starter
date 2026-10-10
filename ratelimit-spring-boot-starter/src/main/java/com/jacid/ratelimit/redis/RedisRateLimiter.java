package com.jacid.ratelimit.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitObserver;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.RateLimiterUnavailableException;

/**
 * Limitador sobre Redis. Cada consulta es un unico script Lua sobre una unica clave:
 *
 * <ul>
 *   <li>es atomico, asi que dos instancias no pueden leer el mismo saldo y gastarlo las dos;
 *   <li>es un solo viaje de red;
 *   <li>funciona en Redis Cluster sin hash tags, porque no hay una segunda clave que pueda caer en
 *       otro slot.
 * </ul>
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** Una clave de cliente mas larga que esto se sustituye por su hash. */
    static final int MAX_KEY_LENGTH = 128;

    private static final long LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final List<RateLimitObserver> observers;
    private final Map<RateLimitAlgorithm, RedisScript<List>> scripts = new EnumMap<>(RateLimitAlgorithm.class);
    private final AtomicLong lastFailureLog = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    public RedisRateLimiter(StringRedisTemplate redis, String keyPrefix, List<RateLimitObserver> observers) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("El prefijo de las claves no puede estar vacio");
        }
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.observers = List.copyOf(observers);
        scripts.put(RateLimitAlgorithm.TOKEN_BUCKET, load("token_bucket.lua"));
        scripts.put(RateLimitAlgorithm.SLIDING_WINDOW_LOG, load("sliding_window_log.lua"));
        scripts.put(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, load("sliding_window_counter.lua"));
    }

    private static RedisScript<List> load(String file) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(file, RedisRateLimiter.class));
        script.setResultType(List.class);
        return script;
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitPolicy policy, String key, int permits) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("La clave no puede estar vacia: todas las peticiones compartirian cuota");
        }
        if (permits < 1 || permits > policy.capacity()) {
            throw new IllegalArgumentException("permits=" + permits + " no puede pasar nunca por la politica '"
                    + policy.name() + "', cuya capacidad es " + policy.capacity());
        }
        RateLimitResult result = evaluate(policy, key, permits);
        notifyObservers(policy, result);
        return result;
    }

    private RateLimitResult evaluate(RateLimitPolicy policy, String key, int permits) {
        try {
            List<?> reply = redis.execute(
                    scripts.get(policy.algorithm()), List.of(redisKey(policy, key)), arguments(policy, permits));
            return new RateLimitResult(
                    number(reply, 0) == 1,
                    policy.capacity(),
                    number(reply, 1),
                    Duration.ofMillis(number(reply, 2)),
                    Duration.ofMillis(number(reply, 3)),
                    false);
        } catch (DataAccessException e) {
            logFailure(policy, e);
            if (policy.failOpen()) {
                return RateLimitResult.degradedAllow(policy);
            }
            notifyObservers(policy, new RateLimitResult(false, policy.capacity(), -1, Duration.ZERO, Duration.ZERO, true));
            throw new RateLimiterUnavailableException(policy.name(), e);
        }
    }

    private static Object[] arguments(RateLimitPolicy policy, int permits) {
        String periodMicros = Long.toString(policy.period().toNanos() / 1000);
        if (policy.algorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
            return new Object[] {
                Long.toString(policy.capacity()), Long.toString(policy.limit()), periodMicros, Integer.toString(permits)
            };
        }
        return new Object[] {Long.toString(policy.limit()), periodMicros, Integer.toString(permits)};
    }

    private static long number(List<?> reply, int index) {
        return ((Number) reply.get(index)).longValue();
    }

    /** La clave de Redis que guarda el estado de {@code key} en {@code policy}. */
    public String redisKey(RateLimitPolicy policy, String key) {
        String safe = key.length() > MAX_KEY_LENGTH ? "sha256:" + sha256(key) : key;
        return keyPrefix + ":" + policy.algorithm().code() + ":" + policy.name() + ":" + safe;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notifyObservers(RateLimitPolicy policy, RateLimitResult result) {
        for (RateLimitObserver observer : observers) {
            try {
                observer.onResult(policy, result);
            } catch (RuntimeException e) {
                log.warn("Un observador del rate limiter ha fallado: {}", e.toString());
            }
        }
    }

    /** Con Redis caido fallan todas las peticiones: una linea cada diez segundos, no una por peticion. */
    private void logFailure(RateLimitPolicy policy, DataAccessException e) {
        long now = System.nanoTime();
        long last = lastFailureLog.get();
        if (now - last >= LOG_INTERVAL_NANOS && lastFailureLog.compareAndSet(last, now)) {
            log.warn(
                    "Redis no ha respondido al rate limiter; la politica '{}' {}. Causa: {}",
                    policy.name(),
                    policy.failOpen() ? "deja pasar sin contar (fail-open)" : "rechaza (fail-closed)",
                    e.toString());
        }
    }
}
