package com.jacid.ratelimit.metrics;

import org.springframework.beans.factory.ObjectProvider;

import com.jacid.ratelimit.RateLimitObserver;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cuenta los veredictos en {@code ratelimit.requests}, con las etiquetas {@code policy} y {@code
 * outcome} (allowed, rejected o degraded).
 *
 * <p>La clave del cliente no es una etiqueta a proposito: hay tantas como clientes y cada valor
 * distinto es una serie temporal nueva.
 */
public class MicrometerRateLimitObserver implements RateLimitObserver {

    public static final String METRIC = "ratelimit.requests";

    private final ObjectProvider<MeterRegistry> registry;

    public MicrometerRateLimitObserver(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void onResult(RateLimitPolicy policy, RateLimitResult result) {
        // Se resuelve en cada uso y no al construir: asi no importa en que orden se configuren las metricas.
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        String outcome = result.degraded() ? "degraded" : result.allowed() ? "allowed" : "rejected";
        meters.counter(METRIC, "policy", policy.name(), "outcome", outcome).increment();
    }
}
