# ratelimit-spring-boot-starter

Rate limiter distribuido sobre Redis (Lua) como starter de Spring Boot. Pieza de portfolio; sin publicar, sin remoto.
Maven multimódulo: `ratelimit-spring-boot-starter` (librería) y `ratelimit-sample` (app de ejemplo).

## Stack
Java 21 (`java.version`), Spring Boot 4.1.1, Maven wrapper 3.9.16 (`./mvnw`), Redis 7, Testcontainers, Micrometer opcional.
Paquete `com.jacid.ratelimit`. Tres algoritmos en `src/main/resources/com/jacid/ratelimit/redis/*.lua`.

## Comandos (raíz del repo; siempre `./mvnw`)
- Build: `./mvnw -B -ntp -q -DskipTests package` · instalar en .m2: `./mvnw -DskipTests install`
- Test completo: `./mvnw verify` (Testcontainers; con Redis propio: `TEST_REDIS_URL=redis://localhost:16390 ./mvnw verify`)
- Un fichero: `./mvnw -B -ntp -pl ratelimit-spring-boot-starter test -Dtest=RateLimitPolicyTest`
- Un método: `-Dtest='RedisRateLimiterContractTest#allowsExactlyTheLimitAndReportsWhatIsLeft'`
- Arranque: `docker compose up -d --wait` (Redis en :16390) y `java -jar ratelimit-sample/target/ratelimit-sample-0.1.0.jar` (:18181)
- Demo E2E: `scripts/demo.sh` (puertos 16390, 18181, 18182 libres; se limpia solo)
- Mutaciones: `python3 scripts/mutation-check.py [filtro]` (47 mutaciones, ~10 min)
- Release en seco: `scripts/release-dry-run.sh [vX.Y.Z]` (= `./mvnw -DskipTests -Prelease -Dgpg.skip verify`, exige 3 jars). Publicar de verdad: tag `v<version>` + push; pasos externos en `docs/PUBLICAR.md`
- Lint/formato: no hay configurado. No inventes uno (google-java-format falla en JDK 25+).

## Cómo se verifica una entrega
- `./mvnw verify` con cifras literales `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`.
- Cambio en `.lua` o en un algoritmo: `scripts/demo.sh` y mutaciones del módulo tocado (avisa antes: es pesado).
- Cada decisión D1-D15 de `docs/DISENO.md` tiene su test; una decisión nueva lleva test y mutación vista en rojo.
- Di siempre qué no corriste. Docker caído = tests sin correr, no verdes.

## No tocar
- `src/main` mientras corre `mutation-check.py`: lo modifica y lo restaura; no compiles a la vez.
- `target/` (generado) y `.mvn/wrapper/`. Nunca claves ni `.env` en el árbol.
- Puertos: Redis demo 16390 (no 6379), apps 18181/18182.

## Convenciones
- Fuentes (java, lua, yaml, pom, scripts) en español sin tildes; README y docs con tildes.
- Commits en español, sin atribución a Claude. `git add` con rutas explícitas.
- Tests con tiempo: `@Timeout`; nunca `sleep` sin tope.
- Trampas del stack: `.claude/rules/java-spring.md` (se carga solo por `paths:`).

@docs/claude-memoria.md
