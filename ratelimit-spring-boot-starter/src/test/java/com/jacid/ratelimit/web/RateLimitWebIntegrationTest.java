package com.jacid.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jacid.ratelimit.support.RedisTestServer;

@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "ratelimit.policies.by-ip.algorithm=sliding-window-log",
            "ratelimit.policies.by-ip.limit=3",
            "ratelimit.policies.by-ip.period=1h",
            "ratelimit.policies.by-header.algorithm=token-bucket",
            "ratelimit.policies.by-header.limit=1",
            "ratelimit.policies.by-header.period=1h",
            "ratelimit.policies.by-header.burst=2",
            "ratelimit.policies.by-header.key=header:X-Api-Key",
            "ratelimit.policies.by-principal.limit=1",
            "ratelimit.policies.by-principal.period=1h",
            "ratelimit.policies.by-principal.key=principal",
            "ratelimit.policies.shared.algorithm=sliding-window-counter",
            "ratelimit.policies.shared.limit=2",
            "ratelimit.policies.shared.period=1h",
            "ratelimit.policies.shared.key=global",
            "ratelimit.policies.by-tenant.limit=1",
            "ratelimit.policies.by-tenant.period=1h",
            "ratelimit.policies.by-tenant.key=bean:tenantResolver",
            "ratelimit.policies.class-wide.limit=1",
            "ratelimit.policies.class-wide.period=1h",
        })
@AutoConfigureMockMvc
class RateLimitWebIntegrationTest {

    /** Redis puede ser externo y sobrevivir entre ejecuciones: cada ejecucion usa su propio espacio de claves. */
    private static final String RUN = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
        registry.add("ratelimit.key-prefix", () -> "web-" + RUN);
    }

    @Autowired
    private MockMvc mvc;

    private static String fresh() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder from(String ip, MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void endpointsWithoutTheAnnotationAreUntouched() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/free"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
        }
    }

    @Test
    void countsDownInTheHeadersAndThenAnswers429WithoutCallingTheController() throws Exception {
        String ip = fresh();
        int before = TestApplication.INVOCATIONS.get();

        for (int remaining = 2; remaining >= 0; remaining--) {
            mvc.perform(from(ip, get("/by-ip")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(remaining)))
                    .andExpect(header().exists("X-RateLimit-Reset"))
                    .andExpect(header().doesNotExist("Retry-After"));
        }
        MvcResult rejected = mvc.perform(from(ip, get("/by-ip")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.policy").value("by-ip"))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 3600L);
        assertThat(rejected.getResponse().getContentAsString()).contains("\"retryAfterSeconds\":" + retryAfter);
        assertThat(TestApplication.INVOCATIONS.get() - before).isEqualTo(3);
    }

    @Test
    void eachClientAddressHasItsOwnQuota() throws Exception {
        String busy = fresh();
        for (int i = 0; i < 3; i++) {
            mvc.perform(from(busy, get("/by-ip"))).andExpect(status().isOk());
        }

        mvc.perform(from(busy, get("/by-ip"))).andExpect(status().isTooManyRequests());
        mvc.perform(from(fresh(), get("/by-ip"))).andExpect(status().isOk());
    }

    @Test
    void headerKeyedPoliciesSeparateClientsBehindTheSameAddress() throws Exception {
        String ip = fresh();
        String alice = fresh();

        mvc.perform(from(ip, get("/by-header").header("X-Api-Key", alice)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"));
        mvc.perform(from(ip, get("/by-header").header("X-Api-Key", alice))).andExpect(status().isOk());
        mvc.perform(from(ip, get("/by-header").header("X-Api-Key", alice)))
                .andExpect(status().isTooManyRequests());

        mvc.perform(from(ip, get("/by-header").header("X-Api-Key", fresh()))).andExpect(status().isOk());
    }

    @Test
    void aMissingHeaderFallsBackToTheAddressInsteadOfSkippingTheLimit() throws Exception {
        String ip = fresh();

        mvc.perform(from(ip, get("/by-header"))).andExpect(status().isOk());
        mvc.perform(from(ip, get("/by-header").header("X-Api-Key", " "))).andExpect(status().isOk());
        mvc.perform(from(ip, get("/by-header"))).andExpect(status().isTooManyRequests());
        mvc.perform(from(fresh(), get("/by-header"))).andExpect(status().isOk());
    }

    @Test
    void aHeaderValueCannotImpersonateAnAddress() throws Exception {
        String ip = fresh();
        mvc.perform(from(ip, get("/by-header"))).andExpect(status().isOk());
        mvc.perform(from(ip, get("/by-header"))).andExpect(status().isOk());
        mvc.perform(from(ip, get("/by-header"))).andExpect(status().isTooManyRequests());

        // Si las claves no llevaran su origen, mandar "ip:<victima>" como API key gastaria su cuota.
        mvc.perform(from(fresh(), get("/by-header").header("X-Api-Key", "ip:" + ip)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
    }

    @Test
    void principalKeyedPoliciesFollowTheUserAcrossAddresses() throws Exception {
        String alice = fresh();

        mvc.perform(from(fresh(), get("/by-principal").principal(() -> alice))).andExpect(status().isOk());
        mvc.perform(from(fresh(), get("/by-principal").principal(() -> alice)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(from(fresh(), get("/by-principal").principal(() -> fresh()))).andExpect(status().isOk());
    }

    @Test
    void globalPoliciesAreOneQuotaForEveryone() throws Exception {
        int ok = 0;
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            int status = mvc.perform(from(fresh(), get("/shared"))).andReturn().getResponse().getStatus();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                rejected++;
            }
        }

        assertThat(ok).isEqualTo(2);
        assertThat(rejected).isEqualTo(3);
    }

    @Test
    void aCustomResolverBeanCanBeNamedInThePolicy() throws Exception {
        String tenant = fresh();
        String ip = fresh();

        mvc.perform(from(ip, get("/by-tenant").param("tenant", tenant))).andExpect(status().isOk());
        mvc.perform(from(fresh(), get("/by-tenant").param("tenant", tenant)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(from(ip, get("/by-tenant").param("tenant", fresh()))).andExpect(status().isOk());
    }

    @Test
    void theClassAnnotationCoversEveryMethodAndTheMethodOneWins() throws Exception {
        String ip = fresh();

        mvc.perform(from(ip, get("/class/inherits")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "1"));
        mvc.perform(from(ip, get("/class/inherits"))).andExpect(status().isTooManyRequests());

        mvc.perform(from(ip, get("/class/overrides").header("X-Api-Key", fresh())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"));
    }

    @Test
    void anAsyncRequestIsCountedOnceNotOncePerDispatch() throws Exception {
        String ip = fresh();

        MvcResult started = mvc.perform(from(ip, get("/async")))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(content().string("ok"));

        mvc.perform(from(ip, get("/by-ip"))).andExpect(header().string("X-RateLimit-Remaining", "1"));
    }
}
