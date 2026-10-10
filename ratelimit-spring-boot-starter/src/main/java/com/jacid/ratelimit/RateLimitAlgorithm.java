package com.jacid.ratelimit;

/**
 * Algoritmos disponibles. El codigo corto forma parte de la clave de Redis: si una politica cambia
 * de algoritmo, el estado nuevo no se mezcla con el viejo (que tiene otro tipo de dato) y el viejo
 * caduca solo.
 */
public enum RateLimitAlgorithm {

    /** Cubo de fichas: permite rafagas hasta {@code burst} y rellena a ritmo constante. Memoria O(1). */
    TOKEN_BUCKET("tb"),

    /** Ventana deslizante exacta: guarda una marca por peticion aceptada. Memoria O(limite). */
    SLIDING_WINDOW_LOG("swl"),

    /** Ventana deslizante aproximada: dos contadores ponderados por el tiempo. Memoria O(1). */
    SLIDING_WINDOW_COUNTER("swc");

    private final String code;

    RateLimitAlgorithm(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
