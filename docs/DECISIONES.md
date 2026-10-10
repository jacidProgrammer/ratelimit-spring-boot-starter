# Decisiones de publicación (2026-10-10)

Las D1-D15 del diseño del rate limiter están en [DISENO.md](DISENO.md). Estas son de empaquetado y
publicación; las tomé yo (Claude) salvo las marcadas como del usuario, y son baratas de revertir.

| Id | Decisión | Alternativa | Por qué |
|---|---|---|---|
| P1 (usuario) | La versión la manda el pom; el tag debe ser `v<version>` o el workflow falla | `versions:set` desde el tag en CI | Una sola fuente de verdad; lo publicado es lo que hay en el repo |
| P2 (usuario) | `autoPublish=true`, `waitUntil=published` | Subir y pulsar Publish en el portal | Sin paso manual; el job termina cuando la versión existe |
| P3 (usuario) | `<developers>` con id, nombre y email | Solo id y nombre | Es lo habitual en Central |
| P4 | `ratelimit-sample` no se publica (`skipPublishing`, `maven.deploy.skip`, sin sources/javadoc/gpg) | Publicarlo o sacarlo del reactor | Es una app de ejemplo, no una librería; el pom padre sí se publica porque el starter lo referencia |
| P5 | Java 21 Temurin en CI aunque en local hay JDK 25 | 25 | `java.version` del pom es 21; compilar con el mismo major que el target evita sorpresas en `--release` |
| P6 | gpg con `--pinentry-mode loopback` y passphrase por `MAVEN_GPG_PASSPHRASE` | Agente gpg en el runner | Es lo que setup-java y maven-gpg-plugin 3.2 esperan; sin pinentry interactivo |
| P7 | El workflow de release ejecuta los tests (`deploy` sin `-DskipTests`); el dry-run local sí los salta | Saltarlos también en CI | No publicar nada que no pase los tests; Testcontainers funciona en ubuntu-latest |
| P8 | Dry-run por `workflow_dispatch` + input `dry_run`, no `act` | `act` en local | `act` no está instalado y no se instala sin preguntar; el ensayo local es `scripts/release-dry-run.sh` |
| P9 | Perfil `release` en el pom padre, no en `settings.xml` ni en el pom del módulo | Config fuera del repo | Reproducible desde el clon; en local se desactiva la firma con `-Dgpg.skip` |
| P10 | central-publishing-maven-plugin 0.11.0 y maven-gpg-plugin 3.2.8 fijados en properties | Dejar que Spring gestione gpg | Spring Boot no fija la versión del plugin de Central; gpg se fija por coherencia |

Pendiente (no decidido, fuera del alcance de hoy): fichero `LICENSE` en la raíz. El pom declara
Apache-2.0 y GitHub no mostrará la licencia hasta que exista el fichero.
