package com.jacid.ratelimit.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jacid.ratelimit.RateLimitPolicyRegistry;

/**
 * Comprueba al arrancar que toda {@link RateLimit} nombra una politica declarada. Sin esto, una
 * errata en el nombre no se veria hasta la primera peticion a ese endpoint, en produccion.
 */
public class RateLimitAnnotationValidator implements SmartInitializingSingleton {

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final RateLimitPolicyRegistry policies;

    public RateLimitAnnotationValidator(
            ObjectProvider<RequestMappingHandlerMapping> mappings, RateLimitPolicyRegistry policies) {
        this.mappings = mappings;
        this.policies = policies;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        mappings.orderedStream().forEach(mapping -> {
            for (HandlerMethod method : mapping.getHandlerMethods().values()) {
                RateLimit annotation = RateLimitInterceptor.find(method);
                if (annotation != null && !policies.contains(annotation.value())) {
                    problems.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                            + " usa @RateLimit(\"" + annotation.value() + "\")");
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Hay endpoints con una politica de rate limit que no existe: " + problems
                    + ". Declaradas en ratelimit.policies: " + policies.names());
        }
    }
}
