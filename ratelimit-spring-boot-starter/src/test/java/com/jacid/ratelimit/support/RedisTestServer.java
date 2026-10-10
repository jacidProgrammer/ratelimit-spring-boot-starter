package com.jacid.ratelimit.support;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * El Redis de los tests, en un unico sitio.
 *
 * <p>Por defecto lo levanta Testcontainers, uno para toda la suite. Si esta definida {@code
 * TEST_REDIS_URL} se usa ese y no se arranca nada: hace falta donde no hay acceso al demonio Docker.
 */
public final class RedisTestServer {

    private static final String EXTERNAL = System.getenv("TEST_REDIS_URL");
    private static final String IMAGE = System.getenv().getOrDefault("TEST_REDIS_IMAGE", "redis:7-alpine");

    private static GenericContainer<?> container;
    private static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();

    private RedisTestServer() {}

    public static synchronized String url() {
        if (EXTERNAL != null) {
            return EXTERNAL;
        }
        if (container == null) {
            container = new GenericContainer<>(IMAGE).withExposedPorts(6379);
            container.start();
        }
        return "redis://%s:%d".formatted(container.getHost(), container.getMappedPort(6379));
    }

    /**
     * Una conexion nueva e independiente. Cada llamada equivale a otra instancia de la aplicacion: no
     * comparte nada con las demas salvo el servidor.
     */
    public static synchronized StringRedisTemplate newClient() {
        URI uri = URI.create(url());
        return newClient(uri.getHost(), uri.getPort(), Duration.ofSeconds(5));
    }

    public static synchronized StringRedisTemplate newClient(String host, int port, Duration timeout) {
        LettuceClientConfiguration client =
                LettuceClientConfiguration.builder().commandTimeout(timeout).build();
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        factory.afterPropertiesSet();
        factory.start();
        FACTORIES.add(factory);
        return new StringRedisTemplate(factory);
    }
}
