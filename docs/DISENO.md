# Diseño y decisiones

Por qué el limitador está hecho así, qué se descartó y qué no resuelve. Cada decisión apunta al test
que la fija; si el test no existe, lo dice.

- [El problema](#el-problema)
- [Los tres algoritmos](#los-tres-algoritmos)
- [Decisiones](#decisiones)
- [Qué no resuelve](#qué-no-resuelve)
- [Preguntas de entrevista que este código contesta](#preguntas-de-entrevista-que-este-código-contesta)

## El problema

Una aplicación con varias instancias detrás de un balanceador quiere limitar a 5 intentos de login por
minuto. Un contador en memoria no sirve: cada instancia tiene el suyo y con 3 instancias el límite real
es 15. El estado tiene que estar en un sitio común, y leerlo y escribirlo tiene que ser una sola
operación: si dos instancias leen "quedan 1" a la vez, las dos aceptan.

```
cliente ──> balanceador ──┬──> instancia A ──┐
                          ├──> instancia B ──┼──> Redis   (un script Lua por petición)
                          └──> instancia C ──┘
```

`RedisRateLimiterContractTest#theSameLoadCatchesALimiterThatIsNotAtomic` reproduce el fallo con un
limitador que hace `GET`, compara y luego `INCR`: bajo la misma carga con la que se prueba el limitador
de verdad, deja pasar más del límite.

## Los tres algoritmos

| | Cubo de fichas | Ventana exacta | Ventana aproximada |
|---|---|---|---|
| Nombre en la configuración | `token-bucket` | `sliding-window-log` | `sliding-window-counter` |
| Estado en Redis | hash: fichas y marca de tiempo | conjunto ordenado: una entrada por permiso aceptado | hash: dos contadores |
| Memoria por clave | constante | proporcional al límite | constante |
| Ráfagas | sí, hasta `burst` | no: nunca más de `limit` en un periodo | sí, en el peor caso |
| Exactitud | exacto respecto a su modelo | exacto | estimación |
| Para qué | APIs: ritmo sostenido con margen para picos | límites bajos donde pasarse importa: login, envío de SMS | límites altos donde importa la memoria |

**Cubo de fichas.** El cubo tiene `burst` fichas como máximo y se rellena a `limit` fichas por `period`.
No hay ningún proceso que rellene: en cada petición se calcula cuánto se habría rellenado desde la
anterior. Las fichas son fraccionarias, así que el relleno es continuo: a 10 por segundo, a los 350 ms
hay 3,5 fichas.

**Ventana exacta.** Guarda el instante de cada permiso aceptado y cuenta los del último periodo. Es la
única de las tres en la que "5 por minuto" significa que en ningún intervalo de un minuto hay más de 5.
El precio es la memoria: un límite de 10 000 son hasta 10 000 entradas por cliente.

**Ventana aproximada.** Divide el tiempo en ventanas fijas alineadas con el reloj, cuenta cuántas
peticiones hubo en la actual y en la anterior, y estima lo gastado en el último periodo como

```
gastado = anterior × (parte de la ventana anterior que aún cae dentro del periodo) + actual
```

Ejemplo con límite 100 por minuto: la ventana anterior tuvo 100 peticiones y van 30 segundos de la
actual. La anterior pesa la mitad: 100 × 0,5 = 50 gastadas, caben otras 50.

La estimación supone que las peticiones de la ventana anterior llegaron repartidas. Si llegaron todas
en su último segundo, siguen dentro del último minuto durante casi toda la ventana actual, pero su peso
va bajando igual: **en el peor caso deja pasar casi el doble del límite en un periodo**, lo mismo que una
ventana fija. A cambio reparte ese exceso a lo largo de la ventana en vez de permitirlo de golpe en la
frontera. Para tráfico normal el error es pequeño; para un atacante que lo conozca, no. Por eso el
login del ejemplo usa la ventana exacta.

## Decisiones

**D1. Un script Lua por petición, sobre una sola clave.** Redis ejecuta un script sin intercalar otros
comandos, así que leer, decidir y escribir es atómico, y es un único viaje de red. Alternativas
descartadas: `MULTI/EXEC` con `WATCH` (reintentos bajo contención, justo cuando hay más carga) y un
cerrojo distribuido (dos viajes más y un problema nuevo si quien lo tiene se cae).
Test: `severalInstancesShareOneQuotaExactly`, con 3 conexiones independientes, 18 hilos y 450 intentos
contra un límite de 50.

**D2. El reloj es el de Redis.** El script llama a `TIME`. Si cada instancia mandara su hora, un reloj
adelantado 2 segundos rellenaría el cubo 2 segundos antes que los demás. Coste: el estado depende de
que el reloj de Redis no salte hacia atrás; si salta, el cubo no resta fichas (`elapsed` nunca es
negativo) pero tampoco rellena hasta que el reloj alcanza la última marca.
Sin test: no hay forma de mover el reloj de Redis desde un test.

**D3. Una sola clave por consulta.** En Redis Cluster un script solo puede tocar claves del mismo slot.
Con una clave no hay nada que alinear y no hacen falta hash tags. Por eso la ventana aproximada guarda
sus dos contadores en un hash y no en dos claves.
Sin test contra un cluster real.

**D4. Toda clave caduca.** El TTL es el tiempo que falta para que el estado sea indistinguible de no
tener estado: cubo lleno o ventana vacía. Un cliente que hace una petición y no vuelve no deja nada.
Test: `stateExpiresOnItsOwn`.

**D5. La clave incluye el algoritmo.** `prefijo:algoritmo:política:cliente`. Si una política pasa de
cubo de fichas a ventana exacta, el estado viejo es un hash y el nuevo un conjunto ordenado: con la
misma clave, Redis respondería `WRONGTYPE` a todas las peticiones hasta que caducara.
Test: `changingTheAlgorithmOfAPolicyDoesNotCollideWithOldState`.

**D6. Los rechazos no consumen.** Un cliente que insiste mientras está limitado no alarga su propia
espera. La alternativa (penalizar la insistencia) es legítima, pero hace que `Retry-After` mienta a
quien reintenta antes de tiempo.
Test: `rejectedRequestsDoNotConsumeQuota`.

**D7. `Retry-After` es suficiente, no orientativo.** Esperar lo que dice la respuesta garantiza que la
misma petición pasa, si nadie más gasta esa cuota entretanto. Se redondea hacia arriba en cada
conversión (microsegundos a milisegundos, milisegundos a segundos).
Test: `waitingRetryAfterIsEnoughToPass`.

**D8. Redis caído: se decide por política.** `fail-open` deja pasar sin contar; `fail-closed` rechaza
con 503. No hay una respuesta buena para todo: tumbar la API entera porque el limitador no contesta es
peor que no limitar durante un rato, pero dejar el login sin límite durante una caída es una invitación.
El 503 es deliberado: un 429 le diría al cliente que ha gastado una cuota que nadie ha contado.
Lo que tarda en decidirse es `spring.data.redis.timeout`: con el valor por defecto de Lettuce, 60
segundos, cada petición esperaría un minuto antes de aplicar la política.
Tests: `FailurePolicyTest`, `RedisDownWebIntegrationTest`.

**D9. Con Redis caído no se anuncian cabeceras de cuota.** No se ha contado nada.
Test: `failOpenServesTheRequestAndDoesNotInventAQuota`.

**D10. La anotación solo nombra la política.** El límite vive en la configuración: se cambia sin
recompilar y hay un único sitio donde mirar cuánto puede hacer cada cliente. Un nombre que no existe
impide arrancar, en vez de dar un error en la primera petición.
Test: `anAnnotationPointingToAnUndeclaredPolicyStopsTheStartup`.

**D11. Las claves llevan su origen.** `ip:203.0.113.7`, `principal:ana`, `header:x-api-key:abc`. Sin
el origen, mandar `ip:203.0.113.7` como API key gastaría la cuota de esa dirección.
Test: `aHeaderValueCannotImpersonateAnAddress`.

**D12. Sin identidad se cuenta por dirección.** Si la política cuenta por API key y la petición no la
trae, se cuenta por IP. Nunca se deja pasar sin contar.
Test: `aMissingHeaderFallsBackToTheAddressInsteadOfSkippingTheLimit`.

**D13. No se lee `X-Forwarded-For`.** La escribe el cliente: un atacante pondría un valor distinto en
cada petición y estrenaría cuota cada vez. Detrás de un proxy hay que activar
`server.forward-headers-strategy` para que el contenedor, que sabe de qué proxy se fía, resuelva la
dirección real.
Sin test: depende de la configuración de la aplicación que usa el starter.

**D14. Las claves largas se resumen.** Una cabecera puede medir kilobytes y acabaría siendo una clave
de Redis. Por encima de 128 caracteres se guarda su SHA-256.
Test: `hugeKeysAreHashedButStayDistinct`.

**D15. La clave del cliente no es una etiqueta de las métricas.** Habría una serie temporal por
cliente. Las etiquetas son la política y el resultado.

## Qué no resuelve

- **Conmutación por error de Redis.** La replicación es asíncrona: si el primario cae antes de
  replicar, la réplica promovida tiene un estado anterior y deja pasar de más durante un periodo.
- **Una petición con varias políticas.** Por ejemplo, por usuario y además global. Se pueden encadenar
  llamadas a `RateLimiter`, pero si la primera acepta y la segunda rechaza, la primera ya ha gastado.
- **Una clave muy caliente.** Una cuota global con mucho tráfico es una sola clave en un solo nodo y un
  viaje a Redis por petición. La solución habitual es que cada instancia reserve un lote de permisos y
  los reparta en memoria, a cambio de exactitud. No está hecho.
- **Tráfico que no llega a Spring MVC.** El interceptor se ejecuta después de los filtros, incluido
  Spring Security: una petición rechazada ya ha pagado la autenticación. Para proteger la
  infraestructura y no solo la lógica, el límite va en el gateway.
- **WebFlux.** Solo hay integración con servlets. `RateLimiter` sí se puede usar desde cualquier sitio,
  pero bloquea.
- **Configuración en caliente.** Las políticas se leen al arrancar.

## Preguntas de entrevista que este código contesta

| Pregunta | Dónde mirar |
|---|---|
| ¿Por qué no un contador en memoria? | [El problema](#el-problema) |
| ¿Cómo evitas la condición de carrera entre instancias? | D1 |
| ¿Cubo de fichas o ventana deslizante? | [Los tres algoritmos](#los-tres-algoritmos) |
| ¿Qué falla en una ventana fija? | `slidingLogNeverExceedsTheLimitInAnyWindow` y la mutación "ventana fija en vez de deslizante" |
| ¿Qué pasa si los relojes de las instancias no coinciden? | D2 |
| ¿Funciona en Redis Cluster? | D3 |
| ¿Cuánta memoria gasta por cliente? | Tabla de algoritmos y D4 |
| ¿Qué pasa si Redis se cae? | D8, D9 |
| ¿Y si Redis conmuta a una réplica? | [Qué no resuelve](#qué-no-resuelve) |
| ¿Cómo identificas al cliente detrás de un proxy? | D13 |
| ¿Cómo lo escalarías a 100 000 peticiones por segundo sobre una clave? | [Qué no resuelve](#qué-no-resuelve), clave caliente |
| ¿Cómo sabes que tus tests prueban algo? | `scripts/mutation-check.py` |
