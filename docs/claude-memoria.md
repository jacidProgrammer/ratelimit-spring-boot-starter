---
name: ratelimit-spring-boot-starter
description: Rate limiter distribuido Redis+Lua como starter de Spring Boot 4.1 en ~/WebstormProjects/ratelimit-spring-boot-starter; decisiones que tome yo, como verificarlo y trampas de los tests con tiempo
metadata:
  type: project
---

Creado el 2026-09-29 en `~/WebstormProjects/ratelimit-spring-boot-starter` (sin git, sin publicar). Pieza
de portfolio para entrevistas de system design, ver [[job-monitor-backend-java]]. Maven multi-modulo:
`ratelimit-spring-boot-starter` (libreria) y `ratelimit-sample` (app de ejemplo). Tres algoritmos en Lua
(`token-bucket`, `sliding-window-log`, `sliding-window-counter`), anotacion `@RateLimit("politica")` para
Spring MVC, API programatica `RateLimiter`, metricas Micrometer. README de uso y `docs/DISENO.md` con 15
decisiones numeradas D1-D15, cada una con su test.

**Why:** el usuario pidio "token bucket o sliding window" y no estaba para preguntar; implemente los tres
y tome yo las decisiones. Las que mas pueden cambiar si opina: sin identidad (sin API key o sin usuario)
se cuenta por IP; fail-closed responde 503 y no 429; los rechazos no consumen cuota; permisos mayores que
la capacidad lanzan IllegalArgumentException; cabeceras `X-RateLimit-*` y no las del borrador IETF;
comentarios y docs en espanol como [[lab-interop-api]].

**How to apply:** verificar con `./mvnw verify` (Testcontainers funciona desde el host con el JDK 25
local; `TEST_REDIS_URL` lo sustituye), `scripts/demo.sh` (Redis en :16390, instancias en :18181 y :18182,
se limpia solo) y `python3 scripts/mutation-check.py` (unos 10 min; modifica `src/main` mientras corre,
asi que no compilar nada a la vez). Trampas que costaron un ciclo: un test que mide el tiempo desde antes
de la primera llamada descuenta la latencia de abrir la conexion y solo falla en frio, con la clase
ejecutada sola; rechazos seguidos caben en el margen de la espera y no prueban que no consuman; en el
contador de ventanas la rama "ventana mas vieja que la anterior" es inalcanzable mientras la clave
caduque, se prueba con `PERSIST`; en AssertJ `rootCause()` falla si la excepcion no viene envuelta.
