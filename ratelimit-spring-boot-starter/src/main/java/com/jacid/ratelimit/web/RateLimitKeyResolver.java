package com.jacid.ratelimit.web;

import jakarta.servlet.http.HttpServletRequest;

/** Decide a quien se le cuenta una peticion. */
@FunctionalInterface
public interface RateLimitKeyResolver {

    /**
     * @return la identidad del cliente, o {@code null} o vacio si esta peticion no la trae; en ese
     *     caso se cuenta por direccion, nunca se deja sin limite
     */
    String resolve(HttpServletRequest request);
}
