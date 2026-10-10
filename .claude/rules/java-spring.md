---
paths: ["**/*.java", "**/*.kt", "**/pom.xml", "**/build.gradle*", "**/application*.y*ml"]
---
# Trampas Java / Spring Boot (portfolio 2026)
- Usa el wrapper del repo (`./mvnw`, `./gradlew`); si no hay JDK local, Maven en contenedor (ver memoria web-template-java-verificacion). `mvn -q` para salidas cortas; lee siempre `Tests run: N, Failures: F, Errors: E, Skipped: S`.
- Spring Boot 4.x renombró artefactos: antes de escribir un `pom`, lista los starters gestionados en `~/.m2/repository/org/springframework/boot/spring-boot-dependencies/<ver>/*.pom` (en 4.1: `spring-boot-web-server-test` sustituye al antiguo starter de test REST; `LocalServerPort`, `EntityScan` y `HibernateJpaAutoConfiguration` cambiaron de paquete; `spring-boot-maven-plugin` repackage necesita `classifier exec` si hay jar normal).
- Un `.m2` viejo da errores que parecen de código: comprueba la versión resuelta antes de tocar fuentes.
- Un test que duerme hasta el timeout cuesta 10 min y un jstack: `@Timeout` en tests de rate-limit/esperas y `-Dsurefire.timeout`.
- Imágenes Docker retiradas de Docker Hub (MinIO): si un `docker pull` falla, busca el sustituto (RustFS) antes de reintentar.
- google-java-format no funciona en JDK 25+: usa el formateador de Eclipse. Resilience4j/Redisson/AMQP en Boot 4: ver memoria docflow.
- Mutaciones: una ronda por módulo; cada mutante compila el proyecto (minutos). Fija la base en caché y trocea en ≤40.
- Claves de desarrollo (`*.pem`, `.env`) fuera del árbol o en `.gitignore` antes del primer commit. Nunca copies claves de otro repo.
- Las demos "sin probar contra Azure/Stripe real" se declaran así en el README y en el cierre.
