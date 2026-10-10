package com.jacid.ratelimit.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.web.RateLimitKeyResolvers;

@ConfigurationProperties("ratelimit")
public class RateLimitProperties {

    /** Interruptor general. Apagado, las anotaciones no hacen nada y no se habla con Redis. */
    private boolean enabled = true;

    /** Prefijo de todas las claves de Redis. */
    private String keyPrefix = "ratelimit";

    /** Que hacer si Redis no responde, salvo que la politica diga otra cosa: true deja pasar, false rechaza. */
    private boolean failOpen = true;

    /** Politicas, por nombre. */
    private Map<String, Policy> policies = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public Map<String, Policy> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies;
    }

    public static class Policy {

        /** Algoritmo: token-bucket, sliding-window-log o sliding-window-counter. */
        private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;

        /** Peticiones por periodo. Obligatorio. */
        private Long limit;

        /** Duracion del periodo, por ejemplo 1s, 5m o 1h. Obligatorio. */
        private Duration period;

        /** Capacidad del cubo de fichas. Por defecto, igual a limit. Solo con token-bucket. */
        private Long burst;

        /** A quien se le cuenta: ip, principal, global, header:<nombre> o bean:<nombre>. */
        private String key = RateLimitKeyResolvers.DEFAULT_SPEC;

        /** Que hacer si Redis no responde. Por defecto, lo que diga ratelimit.fail-open. */
        private Boolean failOpen;

        RateLimitPolicy toPolicy(String name, boolean defaultFailOpen) {
            if (limit == null) {
                throw new IllegalArgumentException("Falta ratelimit.policies." + name + ".limit");
            }
            if (period == null) {
                throw new IllegalArgumentException("Falta ratelimit.policies." + name + ".period");
            }
            return new RateLimitPolicy(
                    name,
                    algorithm,
                    limit,
                    period,
                    burst != null ? burst : limit,
                    failOpen != null ? failOpen : defaultFailOpen);
        }

        public RateLimitAlgorithm getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(RateLimitAlgorithm algorithm) {
            this.algorithm = algorithm;
        }

        public Long getLimit() {
            return limit;
        }

        public void setLimit(Long limit) {
            this.limit = limit;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }

        public Long getBurst() {
            return burst;
        }

        public void setBurst(Long burst) {
            this.burst = burst;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Boolean getFailOpen() {
            return failOpen;
        }

        public void setFailOpen(Boolean failOpen) {
            this.failOpen = failOpen;
        }
    }
}
