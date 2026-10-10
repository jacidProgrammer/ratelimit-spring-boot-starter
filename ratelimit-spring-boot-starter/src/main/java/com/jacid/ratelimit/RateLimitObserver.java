package com.jacid.ratelimit;

/** Recibe cada veredicto. Sirve para metricas o auditoria sin tocar el limitador. */
@FunctionalInterface
public interface RateLimitObserver {

    void onResult(RateLimitPolicy policy, RateLimitResult result);
}
