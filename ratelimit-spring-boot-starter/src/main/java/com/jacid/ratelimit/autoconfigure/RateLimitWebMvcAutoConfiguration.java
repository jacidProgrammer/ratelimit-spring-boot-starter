package com.jacid.ratelimit.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jacid.ratelimit.RateLimitPolicyRegistry;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.web.RateLimit;
import com.jacid.ratelimit.web.RateLimitAnnotationValidator;
import com.jacid.ratelimit.web.RateLimitInterceptor;
import com.jacid.ratelimit.web.RateLimitKeyResolver;
import com.jacid.ratelimit.web.RateLimitKeyResolvers;
import com.jacid.ratelimit.web.RateLimitRejectionHandler;

/** La anotacion {@link RateLimit} para Spring MVC. Solo en aplicaciones web con servlets. */
@AutoConfiguration(after = RateLimitAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HandlerInterceptor.class)
@ConditionalOnBean(RateLimiter.class)
public class RateLimitWebMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RateLimitKeyResolvers rateLimitKeyResolvers(
            ObjectProvider<RateLimitProperties> properties, ListableBeanFactory beans) {
        Map<String, String> specs = new LinkedHashMap<>();
        properties.ifAvailable(
                value -> value.getPolicies().forEach((name, policy) -> specs.put(name, policy.getKey())));
        Map<String, RateLimitKeyResolver> resolvers = beans.getBeansOfType(RateLimitKeyResolver.class);
        return new RateLimitKeyResolvers(specs, resolvers::get);
    }

    @Bean
    @ConditionalOnMissingBean
    RateLimitRejectionHandler rateLimitRejectionHandler() {
        return new RateLimitRejectionHandler() {};
    }

    @Bean
    @ConditionalOnMissingBean
    RateLimitInterceptor rateLimitInterceptor(
            RateLimiter limiter,
            RateLimitPolicyRegistry policies,
            RateLimitKeyResolvers keys,
            RateLimitRejectionHandler rejection) {
        return new RateLimitInterceptor(limiter, policies, keys, rejection);
    }

    @Bean
    WebMvcConfigurer rateLimitWebMvcConfigurer(RateLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    RateLimitAnnotationValidator rateLimitAnnotationValidator(
            ObjectProvider<RequestMappingHandlerMapping> mappings, RateLimitPolicyRegistry policies) {
        return new RateLimitAnnotationValidator(mappings, policies);
    }
}
