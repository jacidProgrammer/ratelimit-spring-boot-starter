package com.jacid.ratelimit;

/** Punto de entrada programatico. La anotacion web se apoya en esto mismo. */
public interface RateLimiter {

    /**
     * Intenta consumir {@code permits} unidades de la cuota de {@code key}.
     *
     * @throws IllegalArgumentException si {@code permits} es menor que 1 o mayor que la capacidad de
     *     la politica: esa peticion no podria pasar nunca
     * @throws RateLimiterUnavailableException si Redis no responde y la politica es fail-closed
     */
    RateLimitResult tryAcquire(RateLimitPolicy policy, String key, int permits);

    default RateLimitResult tryAcquire(RateLimitPolicy policy, String key) {
        return tryAcquire(policy, key, 1);
    }
}
