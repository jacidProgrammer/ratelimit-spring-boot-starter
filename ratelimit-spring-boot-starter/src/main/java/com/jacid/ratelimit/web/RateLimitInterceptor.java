package com.jacid.ratelimit.web;

import java.time.Duration;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitPolicyRegistry;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.RateLimiterUnavailableException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Aplica {@link RateLimit} antes de llamar al controlador. */
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";
    public static final String RETRY_AFTER = "Retry-After";

    private final RateLimiter limiter;
    private final RateLimitPolicyRegistry policies;
    private final RateLimitKeyResolvers keys;
    private final RateLimitRejectionHandler rejection;

    public RateLimitInterceptor(
            RateLimiter limiter,
            RateLimitPolicyRegistry policies,
            RateLimitKeyResolvers keys,
            RateLimitRejectionHandler rejection) {
        this.limiter = limiter;
        this.policies = policies;
        this.keys = keys;
        this.rejection = rejection;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Una peticion asincrona pasa dos veces por aqui (REQUEST y ASYNC): solo se cuenta la primera.
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimit annotation = find(method);
        if (annotation == null) {
            return true;
        }
        RateLimitPolicy policy = policies.get(annotation.value());
        RateLimitResult result;
        try {
            result = limiter.tryAcquire(policy, keys.resolve(policy.name(), request));
        } catch (RateLimiterUnavailableException e) {
            rejection.onUnavailable(request, response, policy);
            return false;
        }
        if (result.degraded()) {
            // No se ha contado nada: anunciar una cuota seria inventarsela.
            return true;
        }
        response.setHeader(LIMIT, Long.toString(result.limit()));
        response.setHeader(REMAINING, Long.toString(result.remaining()));
        response.setHeader(RESET, Long.toString(ceilSeconds(result.resetAfter())));
        if (result.allowed()) {
            return true;
        }
        response.setHeader(RETRY_AFTER, Long.toString(ceilSeconds(result.retryAfter())));
        rejection.onRejected(request, response, policy, result);
        return false;
    }

    static RateLimit find(HandlerMethod method) {
        RateLimit onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimit.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimit.class);
    }

    /** Hacia arriba: decirle a un cliente que espere 0 segundos cuando faltan 400 ms le hace fallar otra vez. */
    static long ceilSeconds(Duration duration) {
        return (duration.toMillis() + 999) / 1000;
    }
}
