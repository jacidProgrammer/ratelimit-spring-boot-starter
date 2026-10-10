package com.jacid.ratelimit;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Una regla: {@code limit} peticiones cada {@code period}.
 *
 * @param name nombre de la politica; forma parte de la clave de Redis
 * @param algorithm algoritmo que la aplica
 * @param limit peticiones permitidas por periodo (en el cubo de fichas, fichas que se rellenan por periodo)
 * @param period duracion del periodo
 * @param burst capacidad del cubo de fichas; en las ventanas deslizantes es siempre igual a {@code limit}
 * @param failOpen que hacer si Redis no responde: {@code true} deja pasar, {@code false} rechaza
 */
public record RateLimitPolicy(
        String name, RateLimitAlgorithm algorithm, long limit, Duration period, long burst, boolean failOpen) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public RateLimitPolicy {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nombre de politica no valido: '" + name + "'. Se admiten letras, digitos, '_', '.' y '-' (maximo 64)");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("La politica '" + name + "' no tiene algoritmo");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("La politica '" + name + "' necesita limit >= 1, y tiene " + limit);
        }
        if (period == null || period.toMillis() < 1) {
            throw new IllegalArgumentException("La politica '" + name + "' necesita period >= 1 ms, y tiene " + period);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("La politica '" + name + "' necesita burst >= 1, y tiene " + burst);
        }
        if (algorithm != RateLimitAlgorithm.TOKEN_BUCKET && burst != limit) {
            throw new IllegalArgumentException(
                    "La politica '" + name + "' define burst=" + burst + " pero " + algorithm
                            + " no admite rafagas: burst solo existe en TOKEN_BUCKET");
        }
    }

    public static RateLimitPolicy tokenBucket(String name, long limit, Duration period, long burst) {
        return new RateLimitPolicy(name, RateLimitAlgorithm.TOKEN_BUCKET, limit, period, burst, true);
    }

    public static RateLimitPolicy of(String name, RateLimitAlgorithm algorithm, long limit, Duration period) {
        return new RateLimitPolicy(name, algorithm, limit, period, limit, true);
    }

    public RateLimitPolicy withFailOpen(boolean value) {
        return new RateLimitPolicy(name, algorithm, limit, period, burst, value);
    }

    /** Lo maximo que se puede consumir de una vez: la capacidad del cubo o el limite de la ventana. */
    public long capacity() {
        return burst;
    }
}
