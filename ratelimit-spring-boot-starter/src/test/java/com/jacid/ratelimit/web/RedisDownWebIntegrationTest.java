package com.jacid.ratelimit.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** La aplicacion arranca y responde con Redis caido; que responde depende de la politica. */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "spring.data.redis.timeout=300ms",
            "spring.data.redis.connect-timeout=300ms",
            "ratelimit.fail-open=false",
            "ratelimit.policies.by-ip.limit=1",
            "ratelimit.policies.by-ip.period=1h",
            "ratelimit.policies.by-ip.fail-open=true",
            "ratelimit.policies.by-header.limit=1",
            "ratelimit.policies.by-header.period=1h",
            "ratelimit.policies.by-principal.limit=1",
            "ratelimit.policies.by-principal.period=1h",
            "ratelimit.policies.by-principal.fail-open=false",
            "ratelimit.policies.shared.limit=1",
            "ratelimit.policies.shared.period=1h",
            "ratelimit.policies.by-tenant.limit=1",
            "ratelimit.policies.by-tenant.period=1h",
            "ratelimit.policies.class-wide.limit=1",
            "ratelimit.policies.class-wide.period=1h",
        })
@AutoConfigureMockMvc
class RedisDownWebIntegrationTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            int closedPort = socket.getLocalPort();
            registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + closedPort);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void failOpenServesTheRequestAndDoesNotInventAQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/by-ip"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
        }
    }

    @Test
    void failClosedAnswers503BecauseTheClientDidNothingWrong() throws Exception {
        mvc.perform(get("/by-principal"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("X-RateLimit-Remaining"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.policy").value("by-principal"));
    }

    @Test
    void aPolicyWithoutItsOwnSettingInheritsTheGlobalOne() throws Exception {
        mvc.perform(get("/by-header")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void endpointsWithoutTheAnnotationDoNotDependOnRedis() throws Exception {
        mvc.perform(get("/free")).andExpect(status().isOk());
    }
}
