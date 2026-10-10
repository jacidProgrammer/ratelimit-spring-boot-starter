package com.jacid.ratelimit;

import java.time.Duration;

/**
 * Veredicto de una consulta.
 *
 * @param allowed si la peticion pasa
 * @param limit capacidad de la politica
 * @param remaining lo que queda disponible ahora mismo; {@code -1} si no se sabe (ver {@code degraded})
 * @param retryAfter cuanto esperar para que esta misma peticion pase; cero si ha pasado
 * @param resetAfter cuanto falta para recuperar la capacidad completa
 * @param degraded {@code true} si Redis no respondio y el veredicto sale de la politica de fallo,
 *     no de un contador real
 */
public record RateLimitResult(
        boolean allowed, long limit, long remaining, Duration retryAfter, Duration resetAfter, boolean degraded) {

    public static RateLimitResult degradedAllow(RateLimitPolicy policy) {
        return new RateLimitResult(true, policy.capacity(), -1, Duration.ZERO, Duration.ZERO, true);
    }
}
