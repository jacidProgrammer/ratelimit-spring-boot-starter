package com.jacid.ratelimit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimitResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe la respuesta cuando una peticion no pasa. Las cabeceras de cuota ya estan puestas cuando se
 * llama. Declarar un bean de este tipo sustituye al comportamiento por defecto.
 */
public interface RateLimitRejectionHandler {

    /** El cliente ha agotado su cuota: 429. */
    default void onRejected(
            HttpServletRequest request, HttpServletResponse response, RateLimitPolicy policy, RateLimitResult result)
            throws IOException {
        long seconds = RateLimitInterceptor.ceilSeconds(result.retryAfter());
        write(response, 429, "Too Many Requests", policy, ",\"retryAfterSeconds\":" + seconds);
    }

    /**
     * No se ha podido contar y la politica es fail-closed: 503, no 429. El cliente no ha hecho nada
     * mal y no tiene cuota que esperar.
     */
    default void onUnavailable(HttpServletRequest request, HttpServletResponse response, RateLimitPolicy policy)
            throws IOException {
        write(response, 503, "Service Unavailable", policy, "");
    }

    private static void write(HttpServletResponse response, int status, String error, RateLimitPolicy policy, String extra)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // El nombre de la politica solo admite [A-Za-z0-9_.-]: no necesita escaparse.
        response.getWriter()
                .write("{\"status\":" + status + ",\"error\":\"" + error + "\",\"policy\":\"" + policy.name() + "\""
                        + extra + "}");
    }
}
