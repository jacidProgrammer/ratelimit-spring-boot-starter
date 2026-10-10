# ratelimit-spring-boot-starter

Rate limiter distribuido sobre Redis, empaquetado como starter de Spring Boot. Varias instancias de una
aplicación comparten una única cuota por cliente y entre todas no dejan pasar ni una petición de más.

Se añade la dependencia, se declara una política en la configuración y se anota el endpoint:

```yaml
ratelimit:
  policies:
    login:
      algorithm: sliding-window-log
      limit: 5
      period: 1m
```

```java
@RateLimit("login")
@PostMapping("/api/login")
Session login(@RequestBody Credentials credentials) { ... }
```

La sexta petición en un minuto recibe esto, la atienda la instancia que la atienda:

```
HTTP/1.1 429
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 58
Retry-After: 58

{"status":429,"error":"Too Many Requests","policy":"login","retryAfterSeconds":58}
```

- [Requisitos](#requisitos)
- [Instalación](#instalación)
- [Algoritmos](#algoritmos)
- [Configuración](#configuración)
- [A quién se le cuenta](#a-quién-se-le-cuenta)
- [Respuestas](#respuestas)
- [Si Redis no responde](#si-redis-no-responde)
- [Uso programático](#uso-programático)
- [Métricas](#métricas)
- [Personalización](#personalización)
- [Demo con dos instancias](#demo-con-dos-instancias)
- [Tests y verificación](#tests-y-verificación)
- [Límites conocidos](#límites-conocidos)
- [Más documentación](#más-documentación)

## Requisitos

| Qué | Versión | Para qué |
|---|---|---|
| JDK | 21 o superior | Compilar y ejecutar |
| Spring Boot | 4.1 | La aplicación que usa el starter |
| Redis | 7 o superior | Guardar las cuotas |
| Docker | cualquiera reciente | Solo para los tests y la demo |
| Python 3 | cualquiera reciente | Solo para el script de mutaciones |

## Instalación

El artefacto no está publicado en ningún repositorio. Se instala en el repositorio local de Maven:

```bash
./mvnw -DskipTests install
```

Y se añade a la aplicación:

```xml
<dependency>
    <groupId>com.jacid</groupId>
    <artifactId>ratelimit-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

La conexión con Redis es la de la aplicación, `spring.data.redis.*`. El starter no abre otra.

## Algoritmos

| | `token-bucket` | `sliding-window-log` | `sliding-window-counter` |
|---|---|---|---|
| Qué garantiza | ritmo sostenido de `limit` por `period`, con ráfagas de hasta `burst` | nunca más de `limit` en ningún intervalo de un `period` | lo mismo, estimado |
| Memoria por cliente | constante | hasta `limit` entradas | constante |
| Ráfagas | sí, hasta `burst` | no | en el peor caso, casi el doble de `limit` |
| Úsalo para | APIs | límites bajos donde pasarse importa | límites altos |

Por defecto, `token-bucket`. La explicación de cada uno, con ejemplos numéricos, está en
[docs/DISENO.md](docs/DISENO.md#los-tres-algoritmos).

## Configuración

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
      timeout: 250ms          # cuánto espera una petición a Redis antes de aplicar fail-open o fail-closed
      connect-timeout: 250ms

ratelimit:
  enabled: true               # false: las anotaciones no hacen nada y no se habla con Redis
  key-prefix: ratelimit       # prefijo de las claves de Redis
  fail-open: true             # qué hacer si Redis no responde, salvo que la política diga otra cosa
  policies:
    api:
      algorithm: token-bucket
      limit: 5                # obligatorio
      period: 1s              # obligatorio
      burst: 10               # solo token-bucket; por defecto, igual a limit
      key: header:X-Api-Key   # por defecto, ip
      fail-open: false        # por defecto, ratelimit.fail-open
```

> **Configura `spring.data.redis.timeout`.** Sin él, Lettuce espera 60 segundos: con Redis caído cada
> petición tardaría un minuto en decidir si pasa.

La aplicación no arranca, y dice por qué, si:

- a una política le falta `limit` o `period`, o no son positivos;
- una política define `burst` con un algoritmo que no es `token-bucket`;
- `key` no es una de las formas admitidas, o nombra un bean que no existe;
- un `@RateLimit` nombra una política que no está declarada.

## A quién se le cuenta

| `key` | Cuota por | Si la petición no trae esa identidad |
|---|---|---|
| `ip` | dirección del cliente | — |
| `principal` | usuario autenticado | se cuenta por dirección |
| `header:X-Api-Key` | valor de esa cabecera | se cuenta por dirección |
| `global` | una sola para todos | — |
| `bean:miResolver` | lo que devuelva ese bean `RateLimitKeyResolver` | se cuenta por dirección |

Una petición sin identidad nunca pasa sin contar.

**Detrás de un proxy** la dirección que ve la aplicación es la del proxy, y todos los clientes
compartirían cuota. Hay que activar `server.forward-headers-strategy=native` (o `framework`) en la
aplicación. El starter no lee `X-Forwarded-For` por su cuenta: la escribe el cliente, y poniendo un
valor distinto en cada petición estrenaría cuota cada vez.

## Respuestas

| Situación | Código | Cabeceras |
|---|---|---|
| Pasa | el del controlador | `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` |
| Cuota agotada | 429 | las tres anteriores y `Retry-After` |
| Redis caído, política fail-open | el del controlador | ninguna: no se ha contado nada |
| Redis caído, política fail-closed | 503 | ninguna |

- `X-RateLimit-Limit`: capacidad de la política (`burst` en el cubo de fichas).
- `X-RateLimit-Remaining`: lo que queda ahora mismo.
- `X-RateLimit-Reset`: segundos hasta recuperar la capacidad completa.
- `Retry-After`: segundos que hay que esperar para que esta petición pase. Se redondea hacia arriba.

Un endpoint sin `@RateLimit` no pasa por Redis ni lleva cabeceras.

## Si Redis no responde

Se decide por política, porque no hay una respuesta buena para todo:

- **fail-open** (por defecto): la petición pasa sin contar. Para una API, no limitar durante una caída
  es mejor que no servir.
- **fail-closed**: la petición se rechaza con 503. Para un login, dejarlo sin límite durante una caída
  es una invitación.

En los dos casos queda una línea de aviso en el log, como mucho una cada 10 segundos, y el veredicto
se cuenta en las métricas como `degraded`.

## Uso programático

Para lo que la anotación no puede expresar, como un coste que depende de la petición:

```java
@RestController
class ExportController {

    private final RateLimiter limiter;
    private final RateLimitPolicyRegistry policies;

    // ...

    @GetMapping("/api/export")
    ResponseEntity<?> export(@RequestParam int pages, Principal user) {
        RateLimitResult result = limiter.tryAcquire(policies.get("export"), user.getName(), pages);
        if (!result.allowed()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(result.retryAfter().toSeconds() + 1))
                    .build();
        }
        // ...
    }
}
```

`RateLimiter` no necesita una aplicación web: funciona igual en un consumidor de mensajes o en una
tarea programada. Pedir más permisos que la capacidad de la política lanza
`IllegalArgumentException`: esa petición no podría pasar nunca.

## Métricas

Si la aplicación tiene Micrometer, cada veredicto incrementa el contador `ratelimit.requests`:

| Etiqueta | Valores |
|---|---|
| `policy` | nombre de la política |
| `outcome` | `allowed`, `rejected`, `degraded` |

La clave del cliente no es una etiqueta a propósito: habría una serie temporal por cliente.

## Personalización

Declarar un bean de cualquiera de estos tipos sustituye o amplía lo que trae el starter:

| Bean | Para qué |
|---|---|
| `RateLimitKeyResolver` | Identificar al cliente de otra forma; se usa con `key: bean:<nombre>` |
| `RateLimitRejectionHandler` | Cambiar el cuerpo o el código de las respuestas 429 y 503 |
| `RateLimitObserver` | Recibir cada veredicto: auditoría, alertas |
| `RateLimiter` | Sustituir el limitador entero, por ejemplo por uno en memoria en los tests |

## Demo con dos instancias

```bash
scripts/demo.sh
```

Levanta un Redis en Docker y dos instancias de la aplicación de ejemplo (`ratelimit-sample`) en los
puertos 18181 y 18182, lanza ráfagas en paralelo repartidas entre las dos y comprueba los resultados.
Termina con error si alguna comprobación falla, y al acabar lo para todo.

```
== 1. Ventana exacta: 40 logins en paralelo contra las dos instancias, limite 5 por minuto
  OK    aceptadas (200): 5
  OK    rechazadas (429): 35
  aceptadas por instancia: 3 18181 2 18182

== 2. Ventana aproximada, cuota global: 300 peticiones, limite 100 por minuto
  OK    aceptadas (200): 100
  OK    rechazadas (429): 200
...
== 7. Redis caido
  OK    sin limite: no depende de Redis: 200
  OK    api (fail-open): deja pasar: 200
  OK    login (fail-closed): 503: 503
```

## Tests y verificación

```bash
./mvnw verify
```

Los tests arrancan su propio Redis con Testcontainers. Si no hay acceso al demonio Docker, se les puede
dar uno que ya esté en marcha:

```bash
TEST_REDIS_URL=redis://localhost:6379 ./mvnw verify
```

| Clase | Qué fija |
|---|---|
| `RedisRateLimiterContractTest` | Lo que cumple cualquier algoritmo: límite exacto, `Retry-After` suficiente, claves aisladas, caducidad, y la cuota compartida entre 3 instancias con 18 hilos |
| `AlgorithmBehaviourTest` | Lo que distingue a cada uno: ráfaga y relleno gradual, ninguna ventana por encima del límite, el peso de la ventana anterior |
| `FailurePolicyTest` | Redis caído y estado corrupto, con fail-open y fail-closed |
| `RateLimitWebIntegrationTest` | La anotación, las cabeceras, las cinco formas de clave, peticiones asíncronas |
| `RedisDownWebIntegrationTest` | La aplicación arranca y responde sin Redis |
| `RateLimitAutoConfigurationTest` | Qué beans hay en cada caso y qué configuraciones impiden arrancar |

### ¿Y los tests prueban algo?

```bash
python3 scripts/mutation-check.py
```

Un test que nunca se ha visto en rojo no protege. El script rompe a propósito cada premisa del diseño
(una línea del código de producción cada vez: que aceptar no gaste fichas, que la clave no caduque, que
el dispatch asíncrono cuente dos veces...), ejecuta el test que dice vigilarla y restaura el fichero.
Si el test sigue en verde, el script termina con error. Son 47 mutaciones y tarda unos 10 minutos.
Mientras corre modifica `src/main`: no compiles nada a la vez.

Además, `theSameLoadCatchesALimiterThatIsNotAtomic` calibra la prueba de concurrencia: un limitador
que lee y escribe en dos pasos, bajo la misma carga, deja pasar de más.

## Límites conocidos

- No hay integración con WebFlux; solo con Spring MVC.
- Una petición solo puede llevar una política con la anotación.
- Las políticas se leen al arrancar: cambiar un límite exige reiniciar.
- Si Redis conmuta a una réplica que no había recibido las últimas escrituras, se deja pasar de más
  durante un periodo.
- El interceptor se ejecuta después de los filtros, incluida la autenticación: protege la lógica de
  la aplicación, no la infraestructura que hay delante.
- No se ha probado contra Redis Cluster ni contra Redis Sentinel.

## Más documentación

- [docs/DISENO.md](docs/DISENO.md): los algoritmos, las 15 decisiones de diseño con el test que fija
  cada una, lo que no resuelve y un índice de preguntas de entrevista.
