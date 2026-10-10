package com.jacid.ratelimit.web;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
class TestApplication {

    static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @Bean("tenantResolver")
    RateLimitKeyResolver tenantResolver() {
        return request -> request.getParameter("tenant");
    }

    @RestController
    static class MethodLevelController {

        @GetMapping("/free")
        String free() {
            return "free";
        }

        @RateLimit("by-ip")
        @GetMapping("/by-ip")
        String byIp() {
            INVOCATIONS.incrementAndGet();
            return "ok";
        }

        @RateLimit("by-header")
        @GetMapping("/by-header")
        String byHeader() {
            return "ok";
        }

        @RateLimit("by-principal")
        @GetMapping("/by-principal")
        String byPrincipal() {
            return "ok";
        }

        @RateLimit("shared")
        @GetMapping("/shared")
        String shared() {
            return "ok";
        }

        @RateLimit("by-tenant")
        @GetMapping("/by-tenant")
        String byTenant() {
            return "ok";
        }

        @RateLimit("by-ip")
        @GetMapping("/async")
        Callable<String> async() {
            return () -> "ok";
        }
    }

    @RateLimit("class-wide")
    @RestController
    @RequestMapping("/class")
    static class ClassLevelController {

        @GetMapping("/inherits")
        String inherits() {
            return "ok";
        }

        @RateLimit("by-header")
        @GetMapping("/overrides")
        String overrides() {
            return "ok";
        }
    }
}
