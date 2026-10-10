package com.jacid.ratelimit.web;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

/**
 * La clave de cada politica, a partir de su especificacion en la configuracion:
 *
 * <ul>
 *   <li>{@code ip}: la direccion del cliente
 *   <li>{@code principal}: el usuario autenticado
 *   <li>{@code header:<nombre>}: el valor de una cabecera, por ejemplo una API key
 *   <li>{@code global}: una unica cuota para todo el mundo
 *   <li>{@code bean:<nombre>}: un {@link RateLimitKeyResolver} de la aplicacion
 * </ul>
 *
 * <p>Toda clave lleva delante su origen. Sin el, un cliente podria mandar como API key el texto
 * {@code ip:203.0.113.7} y gastar la cuota de esa direccion.
 */
public final class RateLimitKeyResolvers {

    public static final String DEFAULT_SPEC = "ip";

    private final Map<String, RateLimitKeyResolver> byPolicy = new LinkedHashMap<>();

    /**
     * @param specs especificacion de cada politica, por nombre de politica
     * @param beans busca un resolver de la aplicacion por nombre de bean; devuelve {@code null} si no existe
     */
    public RateLimitKeyResolvers(Map<String, String> specs, Function<String, RateLimitKeyResolver> beans) {
        specs.forEach((policy, spec) -> byPolicy.put(policy, parse(policy, spec, beans)));
    }

    public String resolve(String policy, HttpServletRequest request) {
        RateLimitKeyResolver resolver = byPolicy.get(policy);
        String key = resolver == null ? null : resolver.resolve(request);
        return key == null ? ip(request) : key;
    }

    private static RateLimitKeyResolver parse(
            String policy, String spec, Function<String, RateLimitKeyResolver> beans) {
        String value = spec == null ? "" : spec.trim();
        if (value.equals("ip")) {
            return RateLimitKeyResolvers::ip;
        }
        if (value.equals("global")) {
            return request -> "global";
        }
        if (value.equals("principal")) {
            return request -> {
                Principal principal = request.getUserPrincipal();
                return principal == null ? null : prefixed("principal", principal.getName());
            };
        }
        if (value.startsWith("header:")) {
            String header = value.substring("header:".length()).trim();
            if (header.isEmpty()) {
                throw invalid(policy, spec, "falta el nombre de la cabecera tras 'header:'");
            }
            return request -> prefixed("header:" + header.toLowerCase(), request.getHeader(header));
        }
        if (value.startsWith("bean:")) {
            String name = value.substring("bean:".length()).trim();
            RateLimitKeyResolver bean = name.isEmpty() ? null : beans.apply(name);
            if (bean == null) {
                throw invalid(policy, spec, "no hay ningun bean RateLimitKeyResolver llamado '" + name + "'");
            }
            return request -> prefixed("bean:" + name, bean.resolve(request));
        }
        throw invalid(policy, spec, "se admite ip, principal, global, header:<nombre> o bean:<nombre>");
    }

    /**
     * La direccion que ve el contenedor. Detras de un proxy es la del proxy salvo que la aplicacion
     * active {@code server.forward-headers-strategy}; aqui no se lee {@code X-Forwarded-For} porque
     * cualquiera puede escribirla y estrenar cuota en cada peticion.
     */
    private static String ip(HttpServletRequest request) {
        return "ip:" + request.getRemoteAddr();
    }

    private static String prefixed(String origin, String value) {
        return value == null || value.isBlank() ? null : origin + ":" + value.trim();
    }

    private static IllegalArgumentException invalid(String policy, String spec, String reason) {
        return new IllegalArgumentException(
                "La politica '" + policy + "' tiene una clave no valida, key='" + spec + "': " + reason);
    }
}
