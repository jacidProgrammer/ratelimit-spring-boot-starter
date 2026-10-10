package com.jacid.ratelimit.sample;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jacid.ratelimit.RateLimitPolicyRegistry;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.web.RateLimit;

/** Cada respuesta dice que instancia la ha servido: la cuota es la misma se pregunte a la que se pregunte. */
@RestController
public class DemoController {

    private final String instance;
    private final RateLimiter limiter;
    private final RateLimitPolicyRegistry policies;

    DemoController(@Value("${server.port}") String port, RateLimiter limiter, RateLimitPolicyRegistry policies) {
        this.instance = "instancia-" + port;
        this.limiter = limiter;
        this.policies = policies;
    }

    /** Sin limite. */
    @GetMapping("/free")
    Map<String, String> free() {
        return Map.of("servedBy", instance);
    }

    /** Cubo de fichas por API key: rafagas de 10, 5 por segundo sostenidas. */
    @RateLimit("api")
    @GetMapping("/api/ping")
    Map<String, String> ping() {
        return Map.of("servedBy", instance);
    }

    /** Ventana exacta por direccion: 5 intentos por minuto, y si Redis cae se rechaza. */
    @RateLimit("login")
    @PostMapping("/api/login")
    Map<String, String> login() {
        return Map.of("servedBy", instance);
    }

    /** Ventana aproximada, una cuota para todos: 100 por minuto. */
    @RateLimit("reports")
    @GetMapping("/api/reports")
    Map<String, String> reports() {
        return Map.of("servedBy", instance);
    }

    /** Uso programatico: el coste depende de la peticion, algo que la anotacion no puede expresar. */
    @GetMapping("/api/export")
    ResponseEntity<Map<String, Object>> export(@RequestParam(defaultValue = "1") int pages) {
        RateLimitResult result = limiter.tryAcquire(policies.get("export"), "global", pages);
        if (!result.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf((result.retryAfter().toMillis() + 999) / 1000))
                    .body(Map.of("servedBy", instance, "remaining", result.remaining()));
        }
        return ResponseEntity.ok(Map.of("servedBy", instance, "pages", pages, "remaining", result.remaining()));
    }
}
