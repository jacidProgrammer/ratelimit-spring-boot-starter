#!/usr/bin/env bash
# Ensayo local de .github/workflows/release.yml, sin secretos y sin subir nada a Central.
# Reproduce los mismos pasos: comprobar tag contra pom, build con -Prelease y listar artefactos.
#
# Uso: scripts/release-dry-run.sh [vX.Y.Z]
#   Sin argumento usa el tag que apunte a HEAD (si lo hay); con argumento compara contra ese tag.
#   Si gpg esta instalado y GPG_SIGN=1, firma de verdad; si no, -Dgpg.skip.
set -euo pipefail
cd "$(dirname "$0")/.."

version="$(./mvnw -q -ntp help:evaluate -Dexpression=project.version -DforceStdout)"
tag="${1:-$(git tag --points-at HEAD | grep '^v' | head -1 || true)}"
echo "pom=$version tag=${tag:-<ninguno>}"
if [ -n "$tag" ] && [ "v$version" != "$tag" ]; then
  echo "ERROR: el tag $tag no coincide con la version del pom ($version)" >&2
  exit 1
fi

gpg_flag="-Dgpg.skip"
if [ "${GPG_SIGN:-0}" = "1" ] && command -v gpg >/dev/null; then gpg_flag=""; fi
echo "mvn: ./mvnw -B -ntp -DskipTests -Prelease $gpg_flag verify"
./mvnw -B -ntp -q -DskipTests -Prelease $gpg_flag verify

echo "Artefactos del starter:"
ls -1 ratelimit-spring-boot-starter/target/*.jar
ls -1 ratelimit-spring-boot-starter/target/*.asc 2>/dev/null || echo "(sin firmas .asc: gpg saltado)"
n="$(ls -1 ratelimit-spring-boot-starter/target/*.jar | wc -l | tr -d ' ')"
[ "$n" -eq 3 ] || { echo "ERROR: se esperaban 3 jars (normal, sources, javadoc) y hay $n" >&2; exit 1; }
echo "OK: dry-run completo ($n jars). Sin subida a Central."
