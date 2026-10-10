package com.jacid.ratelimit;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Las politicas declaradas en la configuracion, por nombre. */
public final class RateLimitPolicyRegistry {

    private final Map<String, RateLimitPolicy> policies = new LinkedHashMap<>();

    public RateLimitPolicyRegistry(Collection<RateLimitPolicy> policies) {
        for (RateLimitPolicy policy : policies) {
            if (this.policies.putIfAbsent(policy.name(), policy) != null) {
                throw new IllegalArgumentException("Politica duplicada: '" + policy.name() + "'");
            }
        }
    }

    public boolean contains(String name) {
        return policies.containsKey(name);
    }

    public Set<String> names() {
        return policies.keySet();
    }

    /** @throws IllegalArgumentException si no existe: una politica desconocida nunca significa "sin limite" */
    public RateLimitPolicy get(String name) {
        RateLimitPolicy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalArgumentException(
                    "No existe la politica '" + name + "'. Declaradas: " + policies.keySet());
        }
        return policy;
    }
}
