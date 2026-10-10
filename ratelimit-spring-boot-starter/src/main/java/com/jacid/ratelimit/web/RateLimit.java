package com.jacid.ratelimit.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Aplica una politica declarada en {@code ratelimit.policies.<nombre>} a un metodo de controlador, o a
 * todos los de una clase. La del metodo gana a la de la clase.
 *
 * <p>La anotacion solo nombra la politica: el limite, el algoritmo y la clave viven en la
 * configuracion, para poder cambiarlos sin recompilar. Un nombre que no existe impide arrancar.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** Nombre de la politica. */
    String value();
}
