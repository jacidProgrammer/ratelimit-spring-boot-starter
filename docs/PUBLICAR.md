# Publicar en Maven Central

Coordenada: `io.github.jacidprogrammer:ratelimit-spring-boot-starter`. El repo ya tiene el pom, el perfil
`release` y el workflow `.github/workflows/release.yml`. Lo que falta lo haces tú, una sola vez, fuera del
repo (pasos 1-6). Después cada versión es: subir la versión en el pom, commit, tag, push (paso 7).

## Una sola vez

### 1. Cuenta en el portal de Sonatype
1. Entra en https://central.sonatype.com y pulsa **Sign in**.
2. Elige **Sign in with GitHub** y autoriza con la cuenta `jacidProgrammer`. Entrar con GitHub es lo que
   hace que el namespace `io.github.jacidprogrammer` se verifique solo en el paso 2; con email y contraseña
   habría que demostrar el dominio con un repo temporal.

### 2. Verificar el namespace `io.github.jacidprogrammer`
1. Menú de usuario (arriba a la derecha) > **View Namespaces**.
2. Si entraste con GitHub, `io.github.jacidprogrammer` aparece ya como *Verified*. Si no aparece, **Add
   Namespace**, escribe `io.github.jacidprogrammer` y sigue el aviso: te pide crear un repo público en
   GitHub con el nombre que indique (un código tipo `abcd1234`) y pulsar **Verify**. Luego borras ese repo.
3. No sigas hasta que ponga *Verified*: sin eso la subida devuelve 403.

### 3. Clave GPG
En tu máquina no hay `gpg`; instálalo (`brew install gnupg`) o usa otra máquina. Los comandos:
```bash
gpg --full-generate-key
```
Tipo `RSA and RSA` (o ECC ed25519), 4096 bits, caducidad 2y, nombre `José Antonio Cid`, email
`joseantoniocid.programmer@gmail.com`, y una passphrase que guardarás en el gestor de contraseñas.
```bash
gpg --list-secret-keys --keyid-format long
```
Apunta el id largo (16 hex tras `rsa4096/`). Después:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
gpg --keyserver keys.openpgp.org --send-keys <ID>
```
Central comprueba la clave pública en keyserver.ubuntu.com, keys.openpgp.org o pgp.mit.edu; con uno basta,
dos por si uno está caído. Comprueba que se ve:
```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <ID>
```
Exporta la clave privada en texto para el secreto de GitHub (no la pegues en ningún fichero del repo):
```bash
gpg --armor --export-secret-keys <ID> | pbcopy
```

### 4. Token del portal
1. En central.sonatype.com, menú de usuario > **View Account** > **Generate User Token**.
2. Te da un `username` y un `password` (el token). Son los dos valores que van a GitHub; no son tu usuario
   del portal. Si lo pierdes, se genera otro y el anterior deja de valer.

### 5. Secretos en GitHub
Primero existe el repo: créalo vacío en https://github.com/new como `jacidProgrammer/ratelimit-spring-boot-starter`
(público, sin README) y añade el remote:
```bash
git remote add origin git@github.com:jacidProgrammer/ratelimit-spring-boot-starter.git
git push -u origin master
```
Luego, en el repo: **Settings > Secrets and variables > Actions > New repository secret**, cuatro veces,
con estos nombres exactos (el workflow los lee tal cual):

| Secreto | Valor |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | el `username` del token del paso 4 |
| `MAVEN_CENTRAL_TOKEN` | el `password` del token del paso 4 |
| `GPG_PRIVATE_KEY` | la salida completa de `gpg --armor --export-secret-keys`, con las líneas BEGIN/END |
| `GPG_PASSPHRASE` | la passphrase de la clave |

Si prefieres la terminal: `gh secret set MAVEN_CENTRAL_USERNAME` (te pide el valor sin dejarlo en el historial).

### 6. Ensayo en seco en GitHub
**Actions > Release to Maven Central > Run workflow**, con `dry_run` marcado. Debe terminar en verde y el
paso "Artefactos generados" listar el jar, `-sources.jar`, `-javadoc.jar` y sus `.asc`. Si falta algún
`.asc`, el secreto `GPG_PRIVATE_KEY` o `GPG_PASSPHRASE` está mal.

## Cada versión

### 7. Publicar
1. Sube `<version>` en `pom.xml` (el padre; los módulos heredan) y `<tag>` en `<scm>`; actualiza la
   versión del README. Commit.
2. Tag y push:
   ```bash
   git tag v0.1.0
   git push origin master v0.1.0
   ```
3. El workflow comprueba que `v<version del pom>` == tag, compila, pasa los tests (Testcontainers con
   Docker del runner), firma, sube el bundle y espera a que Central lo valide y publique
   (`autoPublish=true`, `waitUntil=published`; 5-30 minutos). En https://central.sonatype.com/publishing
   ves el estado y, si falla la validación, el motivo.
4. La versión aparece en https://central.sonatype.com/artifact/io.github.jacidprogrammer/ratelimit-spring-boot-starter
   y en repo1.maven.org en unos minutos; en search.maven.org tarda más.

Publicar es irreversible: Central no borra versiones. Si algo sale mal, se publica una versión nueva.

## Ensayo en local
Sin secretos y sin subir nada:
```bash
scripts/release-dry-run.sh
```
Hace lo mismo que el workflow salvo subir: comprueba tag contra pom (si HEAD tiene tag `v*`), ejecuta
`./mvnw -DskipTests -Prelease -Dgpg.skip verify` y exige los 3 jars. Con `gpg` instalado y `GPG_SIGN=1`
firma de verdad y lista los `.asc`. `act` no está instalado y no hace falta para esto.

Para ver qué entraría en el bundle sin credenciales reales se puede ejecutar `deploy` con un
`settings.xml` de relleno; el plugin hace el staging en `target/central-staging/`, crea
`target/central-publishing/central-bundle.zip` y falla en la subida con 401. Es la prueba que se hizo el
2026-10-10: el bundle contenía el pom de `ratelimit-parent` y jar, pom, sources y javadoc del starter, y
nada de `ratelimit-sample`.
