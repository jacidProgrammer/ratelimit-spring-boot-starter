# TASK — Publicar ratelimit-spring-boot-starter en Maven Central (2026-10-10)

## Objetivo
Que `io.github.jacidprogrammer:ratelimit-spring-boot-starter:0.1.0` se publique en Maven Central al crear
el tag `v0.1.0` en GitHub, sin pasos manuales en el repo, y que tú tengas la lista exacta de lo que haces fuera.

## Terminado significa
1. `./mvnw -DskipTests -Prelease -Dgpg.skip verify` termina en BUILD SUCCESS y existen
   `ratelimit-spring-boot-starter/target/ratelimit-spring-boot-starter-0.1.0{,-sources,-javadoc}.jar`
   (3 ficheros; `-Dgpg.skip` porque no hay `gpg` en esta máquina; en CI firma de verdad).
2. `./mvnw -DskipTests verify` (sin perfil) sigue igual que hoy: no genera javadoc ni intenta firmar.
3. `ratelimit-sample` no se despliega: en el `verify` con perfil no aparece en la lista de artefactos del
   bundle (`skipPublishing`/`maven.deploy.skip` en su pom) y el `.pom` del parent sí (lo exige Central).
4. `./mvnw help:effective-pom -Prelease | grep -c -E '<(licenses|scm|developers)>'` ≥ 3 y el groupId de
   los tres poms y del README es `io.github.jacidprogrammer` (`grep -r com.jacid --include=pom.xml --include=README.md` = 0).
5. `.github/workflows/release.yml` existe, dispara con `push: tags: ['v*']`, falla si `v${project.version}`
   ≠ tag, y pasa en seco con `workflow_dispatch` + input `dry_run=true` (sin desplegar). `act` no está
   instalado: el dry-run queda documentado en `docs/PUBLICAR.md` y ejecutado aquí como script
   (`scripts/release-dry-run.sh`, mismos comandos que el workflow, sin secretos).
6. README: sección "Instalación" con coordenadas Maven y Gradle (Kotlin DSL) y nota de que requiere 0.1.0 en Central.
7. `docs/PUBLICAR.md`: pasos tuyos fuera del repo, numerados, con el nombre exacto de cada secreto de GitHub.
8. Commits (sin atribución a Claude): (a) estado actual del repo, (b) pom + README, (c) workflow + docs + script.

## Fuera de alcance / no tocar
- Renombrar paquetes Java `com.jacid.*` (groupId ≠ package; cambiarlo es otra tarea).
- Crear claves GPG, tokens, cuenta Sonatype, repo en GitHub o secretos: lo haces tú; yo no ejecuto ni `gpg`
  ni `gh secret`. Tampoco `brew install act|gnupg`.
- Publicar de verdad, crear el tag, hacer `git push` o añadir el remote.
- Workflow de CI de tests (solo el de release). Firmas con sigstore. Publicar `ratelimit-sample`. SNAPSHOTs.
- Cambios en código, tests, DISENO.md, demo o mutation-check.

## Decisiones del usuario (ya tomadas)
- Repo: `github.com/jacidProgrammer/ratelimit-spring-boot-starter`.
- Versión: manda el pom; el workflow falla si el tag no es `v<version>`.
- `autoPublish=true` y `waitUntil=published` (irreversible: Central no borra versiones).
- `<developers>`: id jacidProgrammer, nombre José Antonio Cid, email joseantoniocid.programmer@gmail.com.
- Mías, reversibles, en docs/DECISIONES.md: javadoc con `-Xdoclint:none`? NO, se corrige lo que falle;
  gpg en modo `--pinentry-mode loopback` con passphrase por env; central plugin versión actual (0.8.x);
  Java 21 en CI (Temurin) aunque aquí hay 25.

## Plan y presupuesto
F1 (hilo principal, ~6 llamadas): commit inicial del estado actual.
F2 (~12): poms (groupId, metadatos, perfil `release`), README; verificar criterios 1-4; commit.
F3 (~12): workflow, `scripts/release-dry-run.sh`, `docs/PUBLICAR.md`; ejecutar el dry-run; commit.
Sin subagentes (repo pequeño). ~30 llamadas, <80K de contexto. Cierre con /verify.

## Verificación prevista
Se entrega: salida de `-Prelease -Dgpg.skip verify` con `ls` de los 3 jars, grep del groupId, lista de
artefactos del bundle, ejecución del dry-run script, `actionlint` solo si ya está instalado (no se instala).
NO se verifica: firma GPG real, subida al portal, publicación en Central, el workflow en GitHub real.
