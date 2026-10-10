package com.jacid.ratelimit;

/** Redis no responde y la politica exige rechazar antes que dejar pasar sin contar. */
public class RateLimiterUnavailableException extends RuntimeException {

    private final String policyName;

    public RateLimiterUnavailableException(String policyName, Throwable cause) {
        super("El rate limiter no esta disponible y la politica '" + policyName + "' es fail-closed", cause);
        this.policyName = policyName;
    }

    public String policyName() {
        return policyName;
    }
}
