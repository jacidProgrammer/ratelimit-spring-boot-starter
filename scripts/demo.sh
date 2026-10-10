#!/usr/bin/env bash
# Demo de extremo a extremo: un Redis, dos instancias de la aplicacion y rafagas en paralelo
# repartidas entre las dos. Comprueba que la cuota es una sola y termina con error si no lo es.
#
# Uso: scripts/demo.sh        (necesita Docker arrancado y los puertos 16390, 18181 y 18182 libres)
set -euo pipefail
cd "$(dirname "$0")/.."

PORT_A=18181
PORT_B=18182
JAR=ratelimit-sample/target/ratelimit-sample-0.1.0.jar
LOGS=$(mktemp -d)
PIDS=()
FAILED=0

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  docker compose down --volumes >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() { # descripcion, obtenido, esperado
  if [ "$2" = "$3" ]; then
    echo "  OK    $1: $2"
  else
    echo "  FALLO $1: se esperaba $3 y se obtuvo $2"
    FAILED=$((FAILED + 1))
  fi
}

# Lanza N peticiones en paralelo alternando entre las dos instancias; imprime un codigo HTTP por linea.
burst() { # total, metodo, ruta, [cabecera]
  local total=$1 method=$2 path=$3 header=${4:-X-Demo: 1}
  seq 1 "$total" | xargs -P 16 -I{} sh -c \
    'if [ $(( {} % 2 )) -eq 0 ]; then port='"$PORT_A"'; else port='"$PORT_B"'; fi
     curl -s -o /dev/null -w "%{http_code} $port\n" -X '"$method"' -H "'"$header"'" "http://localhost:$port'"$path"'"'
}

count() { grep -c "^$1" || true; }

wait_for() { # puerto
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null "http://localhost:$1/free"; then return 0; fi
    sleep 0.5
  done
  echo "La instancia del puerto $1 no ha arrancado. Log: $LOGS/$1.log"
  exit 1
}

echo "== Compilando"
./mvnw -B -ntp -q -DskipTests package

echo "== Arrancando Redis y dos instancias"
docker compose up -d --wait >/dev/null
for port in $PORT_A $PORT_B; do
  java -jar "$JAR" --server.port="$port" >"$LOGS/$port.log" 2>&1 &
  PIDS+=($!)
done
wait_for $PORT_A
wait_for $PORT_B
RUN=$(date +%s)
# La conexion con Redis se abre en la primera peticion limitada. Sin este calentamiento, la instancia
# que conecta antes se lleva todas las aceptadas de la primera rafaga y parece que la otra no participa.
for port in $PORT_A $PORT_B; do
  curl -s -o /dev/null -H "X-Api-Key: calentamiento-$RUN" "http://localhost:$port/api/ping"
done

echo
echo "== 1. Ventana exacta: 40 logins en paralelo contra las dos instancias, limite 5 por minuto"
out=$(burst 40 POST /api/login)
check "aceptadas (200)" "$(echo "$out" | count 200)" 5
check "rechazadas (429)" "$(echo "$out" | count 429)" 35
echo "  aceptadas por instancia: $(echo "$out" | grep '^200' | awk '{print $2}' | sort | uniq -c | xargs)"

echo
echo "== 2. Ventana aproximada, cuota global: 300 peticiones, limite 100 por minuto"
out=$(burst 300 GET /api/reports)
check "aceptadas (200)" "$(echo "$out" | count 200)" 100
check "rechazadas (429)" "$(echo "$out" | count 429)" 200

echo
echo "== 3. Cubo de fichas por API key: rafaga de 10 y relleno de 5 por segundo"
out=$(burst 30 GET /api/ping "X-Api-Key: cliente-a-$RUN")
accepted=$(echo "$out" | count 200)
if [ "$accepted" -ge 10 ] && [ "$accepted" -le 13 ]; then
  echo "  OK    cliente A, aceptadas: $accepted (10 de rafaga mas lo rellenado mientras dura la rafaga)"
else
  echo "  FALLO cliente A, aceptadas: $accepted, se esperaba entre 10 y 13"
  FAILED=$((FAILED + 1))
fi
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: cliente-b-$RUN" "http://localhost:$PORT_A/api/ping")
check "cliente B, con A agotado" "$code" 200
sleep 1
out=$(burst 30 GET /api/ping "X-Api-Key: cliente-a-$RUN")
accepted=$(echo "$out" | count 200)
if [ "$accepted" -ge 4 ] && [ "$accepted" -le 8 ]; then
  echo "  OK    cliente A un segundo despues, aceptadas: $accepted (unas 5 fichas rellenadas)"
else
  echo "  FALLO cliente A un segundo despues, aceptadas: $accepted, se esperaba entre 4 y 8"
  FAILED=$((FAILED + 1))
fi

echo
echo "== 4. Cabeceras de una respuesta rechazada"
curl -s -i -X POST "http://localhost:$PORT_B/api/login" | tr -d '\r' | grep -iE '^(HTTP|x-ratelimit|retry-after)|^\{' | sed 's/^/  /'

echo
echo "== 5. Uso programatico con coste: 20 paginas por minuto, 6 por peticion, alternando de instancia"
expected_remaining=(14 8 2 2)
expected_code=(200 200 200 429)
for i in 0 1 2 3; do
  if [ $((i % 2)) -eq 0 ]; then port=$PORT_A; else port=$PORT_B; fi
  body=$(curl -s -w ' %{http_code}' "http://localhost:$port/api/export?pages=6")
  code=${body##* }
  remaining=$(echo "$body" | sed -E 's/.*"remaining":([0-9]+).*/\1/')
  check "peticion $((i + 1)) en $port, codigo" "$code" "${expected_code[$i]}"
  check "peticion $((i + 1)) en $port, quedan" "$remaining" "${expected_remaining[$i]}"
done

echo
echo "== 6. Metricas: cada instancia cuenta lo suyo, la cuota es de las dos"
metric() { # puerto, resultado
  local json
  json=$(curl -s "http://localhost:$1/actuator/metrics/ratelimit.requests?tag=policy:login&tag=outcome:$2")
  # Un contador que nunca se ha incrementado no existe: es un cero, no un error.
  echo "$json" | grep -q '"statistic":"COUNT"' \
    && echo "$json" | sed -E 's/.*"statistic":"COUNT","value":([0-9]+).*/\1/' || echo 0
}
allowed_total=0
rejected_total=0
for port in $PORT_A $PORT_B; do
  a=$(metric "$port" allowed)
  r=$(metric "$port" rejected)
  echo "  instancia $port, login: $a aceptadas, $r rechazadas"
  allowed_total=$((allowed_total + a))
  rejected_total=$((rejected_total + r))
done
check "aceptadas entre las dos" "$allowed_total" 5
check "rechazadas entre las dos" "$rejected_total" 36

echo
echo "== 7. Redis caido"
docker compose stop redis >/dev/null 2>&1
check "sin limite: no depende de Redis" "$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT_A/free")" 200
check "api (fail-open): deja pasar" "$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: c-$RUN" "http://localhost:$PORT_A/api/ping")" 200
check "login (fail-closed): 503" "$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:$PORT_A/api/login")" 503
docker compose start redis >/dev/null 2>&1
sleep 2
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: d-$RUN" -D "$LOGS/headers" "http://localhost:$PORT_A/api/ping")
check "api, con Redis de vuelta" "$code" 200
check "vuelve a anunciar la cuota" "$(tr -d '\r' <"$LOGS/headers" | grep -ic '^x-ratelimit-remaining: 9$' || true)" 1

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Demo correcta."
else
  echo "Demo con $FAILED comprobaciones fallidas. Logs en $LOGS"
  exit 1
fi
