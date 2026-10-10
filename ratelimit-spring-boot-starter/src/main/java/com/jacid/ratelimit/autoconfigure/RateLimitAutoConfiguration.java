package com.jacid.ratelimit.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jacid.ratelimit.RateLimitObserver;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitPolicyRegistry;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.metrics.MicrometerRateLimitObserver;
import com.jacid.ratelimit.redis.RedisRateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * El limitador y sus politicas. Usa el {@link StringRedisTemplate} de la aplicacion: la conexion se
 * configura con {@code spring.data.redis.*}, como cualquier otro uso de Redis.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBooleanProperty(name = "ratelimit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RateLimitPolicyRegistry rateLimitPolicyRegistry(RateLimitProperties properties) {
        List<RateLimitPolicy> policies = new ArrayList<>();
        properties.getPolicies().forEach((name, policy) -> policies.add(policy.toPolicy(name, properties.isFailOpen())));
        return new RateLimitPolicyRegistry(policies);
    }

    // Sin @ConditionalOnBean(StringRedisTemplate): si falta, arrancar sin limitador dejaria las
    // anotaciones sin efecto y en silencio. Es preferible que el arranque falle diciendo que falta.
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    RedisRateLimiter redisRateLimiter(
            StringRedisTemplate redis, RateLimitProperties properties, ObjectProvider<RateLimitObserver> observers) {
        return new RedisRateLimiter(redis, properties.getKeyPrefix(), observers.orderedStream().toList());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MicrometerRateLimitObserver micrometerRateLimitObserver(ObjectProvider<MeterRegistry> registry) {
            return new MicrometerRateLimitObserver(registry);
        }
    }
}
