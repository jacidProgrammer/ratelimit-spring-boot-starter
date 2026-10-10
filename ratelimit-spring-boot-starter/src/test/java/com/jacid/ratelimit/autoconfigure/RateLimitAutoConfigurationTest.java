package com.jacid.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jacid.ratelimit.RateLimitAlgorithm;
import com.jacid.ratelimit.RateLimitObserver;
import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitPolicyRegistry;
import com.jacid.ratelimit.RateLimitResult;
import com.jacid.ratelimit.RateLimiter;
import com.jacid.ratelimit.metrics.MicrometerRateLimitObserver;
import com.jacid.ratelimit.redis.RedisRateLimiter;
import com.jacid.ratelimit.support.RedisTestServer;
import com.jacid.ratelimit.web.RateLimit;
import com.jacid.ratelimit.web.RateLimitInterceptor;
import com.jacid.ratelimit.web.RateLimitRejectionHandler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RateLimitAutoConfigurationTest {

    private static final String[] LOGIN = {"ratelimit.policies.login.limit=5", "ratelimit.policies.login.period=1m"};

    private final ApplicationContextRunner plain = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataRedisAutoConfiguration.class,
                    RateLimitAutoConfiguration.class,
                    RateLimitWebMvcAutoConfiguration.class));

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataRedisAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    RateLimitAutoConfiguration.class,
                    RateLimitWebMvcAutoConfiguration.class));

    @Test
    void aPlainApplicationGetsTheLimiterAndThePoliciesButNothingWeb() {
        plain.withPropertyValues(LOGIN).run(context -> {
            assertThat(context).hasSingleBean(RateLimiter.class);
            assertThat(context).getBean(RateLimiter.class).isInstanceOf(RedisRateLimiter.class);
            assertThat(context).doesNotHaveBean(RateLimitInterceptor.class);

            RateLimitPolicy login = context.getBean(RateLimitPolicyRegistry.class).get("login");
            assertThat(login.algorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
            assertThat(login.limit()).isEqualTo(5);
            assertThat(login.burst()).as("sin burst, la capacidad es el limite").isEqualTo(5);
            assertThat(login.period()).isEqualTo(Duration.ofMinutes(1));
            assertThat(login.failOpen()).isTrue();
        });
    }

    @Test
    void aWebApplicationAlsoGetsTheInterceptor() {
        web.withPropertyValues(LOGIN).run(context -> {
            assertThat(context).hasSingleBean(RateLimitInterceptor.class);
            assertThat(context).hasSingleBean(RateLimitRejectionHandler.class);
        });
    }

    @Test
    void itCanBeSwitchedOff() {
        web.withPropertyValues(LOGIN).withPropertyValues("ratelimit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimiter.class);
            assertThat(context).doesNotHaveBean(RateLimitPolicyRegistry.class);
            assertThat(context).doesNotHaveBean(RateLimitInterceptor.class);
        });
    }

    @Test
    void theApplicationCanBringItsOwnBeans() {
        web.withPropertyValues(LOGIN).withUserConfiguration(OwnBeans.class).run(context -> {
            assertThat(context).hasSingleBean(RateLimiter.class);
            assertThat(context).getBean(RateLimiter.class).isSameAs(OwnBeans.LIMITER);
            assertThat(context).hasSingleBean(RateLimitRejectionHandler.class);
            assertThat(context).getBean(RateLimitRejectionHandler.class).isSameAs(OwnBeans.HANDLER);
            assertThat(context).hasSingleBean(RateLimitInterceptor.class);
        });
    }

    @Test
    void perPolicyFailOpenWinsOverTheGlobalDefault() {
        plain.withPropertyValues(LOGIN)
                .withPropertyValues(
                        "ratelimit.fail-open=false",
                        "ratelimit.policies.lenient.limit=1",
                        "ratelimit.policies.lenient.period=1s",
                        "ratelimit.policies.lenient.fail-open=true")
                .run(context -> {
                    RateLimitPolicyRegistry policies = context.getBean(RateLimitPolicyRegistry.class);
                    assertThat(policies.get("login").failOpen()).isFalse();
                    assertThat(policies.get("lenient").failOpen()).isTrue();
                });
    }

    @Test
    void policiesThatMeanNothingStopTheStartup() {
        assertStartupFails(plain.withPropertyValues("ratelimit.policies.p.period=1m"), "limit");
        assertStartupFails(plain.withPropertyValues("ratelimit.policies.p.limit=5"), "period");
        assertStartupFails(
                plain.withPropertyValues("ratelimit.policies.p.limit=0", "ratelimit.policies.p.period=1m"), "limit");
        assertStartupFails(
                plain.withPropertyValues(
                        "ratelimit.policies.p.limit=5",
                        "ratelimit.policies.p.period=1m",
                        "ratelimit.policies.p.burst=9",
                        "ratelimit.policies.p.algorithm=sliding-window-log"),
                "burst");
        assertStartupFails(plain.withPropertyValues(LOGIN).withPropertyValues("ratelimit.key-prefix= "), "prefijo");
    }

    @Test
    void keySpecsThatCannotBeResolvedStopTheStartup() {
        assertStartupFails(web.withPropertyValues(LOGIN).withPropertyValues("ratelimit.policies.login.key=cookie"), "cookie");
        assertStartupFails(web.withPropertyValues(LOGIN).withPropertyValues("ratelimit.policies.login.key=header:"), "header");
        assertStartupFails(
                web.withPropertyValues(LOGIN).withPropertyValues("ratelimit.policies.login.key=bean:nope"), "nope");
    }

    @Test
    void anAnnotationPointingToAnUndeclaredPolicyStopsTheStartup() {
        web.withPropertyValues(LOGIN).withUserConfiguration(GoodController.class).run(context ->
                assertThat(context).hasNotFailed());

        web.withPropertyValues(LOGIN).withUserConfiguration(TypoController.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(deepest(context.getStartupFailure()))
                    .hasMessageContaining("logni")
                    .hasMessageContaining("TypoController")
                    .hasMessageContaining("login");
        });
    }

    @Test
    void verdictsBecomeMetricsWhenThereIsARegistry() {
        String key = UUID.randomUUID().toString();
        plain.withPropertyValues("ratelimit.policies.login.limit=2", "ratelimit.policies.login.period=1h")
                .withPropertyValues("spring.data.redis.url=" + RedisTestServer.url())
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    RateLimiter limiter = context.getBean(RateLimiter.class);
                    RateLimitPolicy login = context.getBean(RateLimitPolicyRegistry.class).get("login");
                    for (int i = 0; i < 5; i++) {
                        limiter.tryAcquire(login, key);
                    }

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(count(registry, "login", "allowed")).isEqualTo(2);
                    assertThat(count(registry, "login", "rejected")).isEqualTo(3);
                });
    }

    @Test
    void itWorksWithoutMicrometerOnTheClasspath() {
        plain.withPropertyValues(LOGIN)
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context).doesNotHaveBean(MicrometerRateLimitObserver.class);
                });
    }

    @Test
    void observersDeclaredByTheApplicationAreWiredIn() {
        String key = UUID.randomUUID().toString();
        plain.withPropertyValues(LOGIN)
                .withPropertyValues("spring.data.redis.url=" + RedisTestServer.url())
                .withUserConfiguration(Recorder.class)
                .run(context -> {
                    RateLimitPolicy login = context.getBean(RateLimitPolicyRegistry.class).get("login");
                    context.getBean(RateLimiter.class).tryAcquire(login, key);

                    assertThat(Recorder.SEEN).contains("login");
                });
    }

    /** El ultimo eslabon de la cadena de causas; la propia excepcion si no viene envuelta. */
    private static Throwable deepest(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static double count(MeterRegistry registry, String policy, String outcome) {
        return registry.get("ratelimit.requests")
                .tag("policy", policy)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static void assertStartupFails(ApplicationContextRunner runner, String mention) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(deepest(context.getStartupFailure())).hasMessageContaining(mention);
        });
    }

    private static void assertStartupFails(WebApplicationContextRunner runner, String mention) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(deepest(context.getStartupFailure())).hasMessageContaining(mention);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnBeans {

        static final RateLimiter LIMITER = (policy, key, permits) -> RateLimitResult.degradedAllow(policy);
        static final RateLimitRejectionHandler HANDLER = new RateLimitRejectionHandler() {};

        @Bean
        RateLimiter ownLimiter() {
            return LIMITER;
        }

        @Bean
        RateLimitRejectionHandler ownHandler() {
            return HANDLER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Recorder {

        static final List<String> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Bean
        RateLimitObserver recorder() {
            return (policy, result) -> SEEN.add(policy.name());
        }
    }

    @RestController
    static class GoodController {

        @RateLimit("login")
        @GetMapping("/login")
        String login() {
            return "ok";
        }
    }

    @RestController
    static class TypoController {

        @RateLimit("logni")
        @GetMapping("/login")
        String login() {
            return "ok";
        }
    }
}
