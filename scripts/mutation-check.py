#!/usr/bin/env python3
"""Rompe a proposito cada premisa del diseno y exige que su test falle.

Un test que nunca se ha visto en rojo no protege. Cada mutacion cambia una linea del codigo de
produccion, ejecuta el test que dice vigilarla y restaura el fichero. Una mutacion "sobrevive" si
el test sigue en verde: eso es un hueco en los tests, y el script termina con error.

Uso: python3 scripts/mutation-check.py [filtro]
Necesita Docker arrancado (o TEST_REDIS_URL), igual que los tests.
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODULE = "ratelimit-spring-boot-starter"
MAIN = ROOT / MODULE / "src/main"
LUA = MAIN / "resources/com/jacid/ratelimit/redis"
JAVA = MAIN / "java/com/jacid/ratelimit"

CONTRACT = "RedisRateLimiterContractTest"
BEHAVIOUR = "AlgorithmBehaviourTest"
WEB = "RateLimitWebIntegrationTest"

# (nombre, fichero, texto original, texto mutado, test que debe fallar)
MUTATIONS = [
    ("cubo: aceptar no gasta fichas", LUA / "token_bucket.lua",
     "tokens = math.max(0, tokens - permits)", "tokens = math.max(0, tokens)",
     CONTRACT + "#allowsExactlyTheLimitAndReportsWhatIsLeft"),
    ("cubo: no rellena con el tiempo", LUA / "token_bucket.lua",
     "tokens + elapsed * refill / period)", "tokens)",
     CONTRACT + "#waitingRetryAfterIsEnoughToPass"),
    ("cubo: rellena de golpe", LUA / "token_bucket.lua",
     "local elapsed = math.max(0, now - ts)", "local elapsed = (now > ts) and period or 0",
     BEHAVIOUR + "#tokenBucketRefillsGraduallyNotAllAtOnce"),
    ("cubo: sin tope de capacidad al leer", LUA / "token_bucket.lua",
     "tokens = math.min(capacity, tokens + elapsed * refill / period)",
     "tokens = tokens + elapsed * refill / period",
     BEHAVIOUR + "#tokenBucketClampsStoredTokensWhenCapacityShrinks"),
    ("cubo: la capacidad es el limite y no burst", JAVA / "redis/RedisRateLimiter.java",
     "Long.toString(policy.capacity()), Long.toString(policy.limit()), periodMicros",
     "Long.toString(policy.limit()), Long.toString(policy.limit()), periodMicros",
     BEHAVIOUR + "#tokenBucketCapsTheBurstBelowTheSustainedRate"),
    ("cubo: la clave no caduca", LUA / "token_bucket.lua",
     "redis.call('PEXPIRE', KEYS[1], reset)", "",
     CONTRACT + "#stateExpiresOnItsOwn"),
    ("log: los rechazos tambien se apuntan", LUA / "sliding_window_log.lua",
     "  local index = count + permits - limit - 1",
     "  redis.call('ZADD', KEYS[1], string.format('%.0f', now), 'rejected:' .. string.format('%.0f', now))\n"
     "  local index = count + permits - limit - 1",
     CONTRACT + "#aRejectionLeavesTheQuotaExactlyAsItWas"),
    ("log: no se olvidan las entradas viejas", LUA / "sliding_window_log.lua",
     "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', now - period))", "",
     BEHAVIOUR + "#slidingLogFreesEachSlotWhenItsOwnRequestAges"),
    ("log: la espera se calcula con la entrada mas nueva", LUA / "sliding_window_log.lua",
     "local entry = redis.call('ZRANGE', KEYS[1], index, index, 'WITHSCORES')",
     "local entry = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')",
     BEHAVIOUR + "#slidingLogFreesEachSlotWhenItsOwnRequestAges"),
    ("log: ventana fija en vez de deslizante", LUA / "sliding_window_log.lua",
     "string.format('%.0f', now - period))", "string.format('%.0f', math.floor(now / period) * period))",
     BEHAVIOUR + "#slidingLogNeverExceedsTheLimitInAnyWindow"),
    ("log: un permiso de coste N ocupa un hueco", LUA / "sliding_window_log.lua",
     "  for i = 1, permits do", "  for i = 1, 1 do",
     CONTRACT + "#aRequestCanCostSeveralPermits"),
    ("log: la clave no caduca", LUA / "sliding_window_log.lua",
     "  redis.call('PEXPIRE', KEYS[1], reset)", "",
     CONTRACT + "#stateExpiresOnItsOwn"),
    ("contador: la ventana anterior no pesa (ventana fija)", LUA / "sliding_window_counter.lua",
     "local weight = 1 - (now - window_start) / period", "local weight = 0",
     BEHAVIOUR + "#slidingCounterWeighsThePreviousWindowByElapsedTime"),
    ("contador: la ventana anterior pesa siempre entera", LUA / "sliding_window_counter.lua",
     "local weight = 1 - (now - window_start) / period", "local weight = 1",
     BEHAVIOUR + "#slidingCounterWeighsThePreviousWindowByElapsedTime"),
    ("contador: no detecta el cambio de periodo", LUA / "sliding_window_counter.lua",
     "if stored_window == nil or tonumber(state[4]) ~= period then", "if stored_window == nil then",
     BEHAVIOUR + "#slidingCounterStartsOverEvenIfTheWindowIndexHappensToMatch"),
    ("contador: arrastra contadores de una ventana que ya no cuenta", LUA / "sliding_window_counter.lua",
     "elseif stored_window ~= window then", "elseif false then",
     BEHAVIOUR + "#slidingCounterIgnoresWindowsOlderThanThePreviousOne"),
    ("cubo: los rechazos gastan fichas", LUA / "token_bucket.lua",
     "  retry = math.max(1, math.ceil((permits - tokens) * period / refill / 1000))",
     "  retry = math.max(1, math.ceil((permits - tokens) * period / refill / 1000))\n  tokens = tokens - permits",
     CONTRACT + "#aRejectionLeavesTheQuotaExactlyAsItWas"),
    ("contador: los rechazos cuentan", LUA / "sliding_window_counter.lua",
     "local retry = 0\nif allowed == 0 then", "local retry = 0\nif allowed == 0 then\n  current = current + permits",
     CONTRACT + "#aRejectionLeavesTheQuotaExactlyAsItWas"),
    ("contador: la clave no caduca", LUA / "sliding_window_counter.lua",
     "  redis.call('PEXPIRE', KEYS[1], reset)", "",
     CONTRACT + "#stateExpiresOnItsOwn"),
    ("contador: la espera ignora la ventana siguiente", LUA / "sliding_window_counter.lua",
     "retry_at = window_start + period + (1 - needed_weight) * period", "retry_at = window_start + period",
     CONTRACT + "#waitingRetryAfterIsEnoughToPass"),
    ("clave: sin el algoritmo", JAVA / "redis/RedisRateLimiter.java",
     'keyPrefix + ":" + policy.algorithm().code() + ":" + policy.name()', 'keyPrefix + ":" + policy.name()',
     CONTRACT + "#changingTheAlgorithmOfAPolicyDoesNotCollideWithOldState"),
    ("clave: sin la politica", JAVA / "redis/RedisRateLimiter.java",
     'policy.algorithm().code() + ":" + policy.name() + ":" + safe', 'policy.algorithm().code() + ":" + safe',
     CONTRACT + "#policiesDoNotShareQuota"),
    ("clave: las largas se truncan en vez de resumirse", JAVA / "redis/RedisRateLimiter.java",
     '"sha256:" + sha256(key)', "key.substring(0, MAX_KEY_LENGTH)",
     CONTRACT + "#hugeKeysAreHashedButStayDistinct"),
    ("clave: las largas no se acortan", JAVA / "redis/RedisRateLimiter.java",
     'key.length() > MAX_KEY_LENGTH ? "sha256:" + sha256(key) : key', "key",
     CONTRACT + "#hugeKeysAreHashedButStayDistinct"),
    ("estado por instancia en vez de compartido", JAVA / "redis/RedisRateLimiter.java",
     'return keyPrefix + ":" + policy', 'return System.identityHashCode(this) + keyPrefix + ":" + policy',
     CONTRACT + "#severalInstancesShareOneQuotaExactly"),
    ("permisos imposibles se aceptan", JAVA / "redis/RedisRateLimiter.java",
     "if (permits < 1 || permits > policy.capacity()) {", "if (false) {",
     CONTRACT + "#permitsThatCouldNeverPassAreAProgrammingError"),
    ("fallo: siempre fail-open", JAVA / "redis/RedisRateLimiter.java",
     "if (policy.failOpen()) {", "if (true) {",
     "FailurePolicyTest#failClosedRefusesInsteadOfLettingUncountedRequestsIn"),
    ("fallo: siempre fail-closed", JAVA / "redis/RedisRateLimiter.java",
     "if (policy.failOpen()) {", "if (false) {",
     "FailurePolicyTest#failOpenLetsTheRequestThroughAndSaysItDidNotCount"),
    ("fallo: los observadores no ven el rechazo por caida", JAVA / "redis/RedisRateLimiter.java",
     "            notifyObservers(policy, new RateLimitResult(false", "            if (false) notifyObservers(policy, new RateLimitResult(false",
     "FailurePolicyTest#observersAlsoSeeTheRefusalsOfAFailClosedPolicy"),
    ("web: el dispatch asincrono cuenta otra vez", JAVA / "web/RateLimitInterceptor.java",
     "request.getDispatcherType() != DispatcherType.REQUEST || ", "",
     WEB + "#anAsyncRequestIsCountedOnceNotOncePerDispatch"),
    ("web: la anotacion de clase se ignora", JAVA / "web/RateLimitInterceptor.java",
     ": AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimit.class);", ": null;",
     WEB + "#theClassAnnotationCoversEveryMethodAndTheMethodOneWins"),
    ("web: gana la anotacion de clase", JAVA / "web/RateLimitInterceptor.java",
     "return onMethod != null\n                ? onMethod\n                : AnnotatedElementUtils",
     "RateLimit onType = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimit.class);\n"
     "        return onType != null\n                ? onType\n                : onMethod != null ? onMethod : AnnotatedElementUtils",
     WEB + "#theClassAnnotationCoversEveryMethodAndTheMethodOneWins"),
    ("web: un rechazo llama igualmente al controlador", JAVA / "web/RateLimitInterceptor.java",
     "        rejection.onRejected(request, response, policy, result);\n        return false;",
     "        return true;",
     WEB + "#countsDownInTheHeadersAndThenAnswers429WithoutCallingTheController"),
    ("web: con Redis caido se anuncia una cuota inventada", JAVA / "web/RateLimitInterceptor.java",
     "if (result.degraded()) {", "if (false) {",
     "RedisDownWebIntegrationTest#failOpenServesTheRequestAndDoesNotInventAQuota"),
    ("web: fail-closed responde 429", JAVA / "web/RateLimitRejectionHandler.java",
     'write(response, 503, "Service Unavailable", policy, "");', 'write(response, 429, "Too Many Requests", policy, "");',
     "RedisDownWebIntegrationTest#failClosedAnswers503BecauseTheClientDidNothingWrong"),
    ("web: los segundos se redondean hacia abajo", JAVA / "web/RateLimitInterceptor.java",
     "(duration.toMillis() + 999) / 1000", "duration.toMillis() / 1000",
     "RateLimitInterceptorTest"),
    ("claves: sin origen delante", JAVA / "web/RateLimitKeyResolvers.java",
     'return value == null || value.isBlank() ? null : origin + ":" + value.trim();',
     "return value == null || value.isBlank() ? null : value.trim();",
     WEB + "#aHeaderValueCannotImpersonateAnAddress"),
    ("claves: sin cabecera, todos comparten cuota", JAVA / "web/RateLimitKeyResolvers.java",
     "return key == null ? ip(request) : key;", 'return key == null ? "anonymous" : key;',
     WEB + "#aMissingHeaderFallsBackToTheAddressInsteadOfSkippingTheLimit"),
    ("claves: la cabecera se ignora", JAVA / "web/RateLimitKeyResolvers.java",
     'return request -> prefixed("header:" + header.toLowerCase(), request.getHeader(header));',
     "return RateLimitKeyResolvers::ip;",
     WEB + "#headerKeyedPoliciesSeparateClientsBehindTheSameAddress"),
    ("claves: el principal se ignora", JAVA / "web/RateLimitKeyResolvers.java",
     'return principal == null ? null : prefixed("principal", principal.getName());', "return null;",
     WEB + "#principalKeyedPoliciesFollowTheUserAcrossAddresses"),
    ("claves: global cuenta por direccion", JAVA / "web/RateLimitKeyResolvers.java",
     'return request -> "global";', "return RateLimitKeyResolvers::ip;",
     WEB + "#globalPoliciesAreOneQuotaForEveryone"),
    ("claves: una especificacion desconocida cuenta por direccion", JAVA / "web/RateLimitKeyResolvers.java",
     'throw invalid(policy, spec, "se admite ip, principal, global, header:<nombre> o bean:<nombre>");',
     "return RateLimitKeyResolvers::ip;",
     "RateLimitAutoConfigurationTest#keySpecsThatCannotBeResolvedStopTheStartup"),
    ("arranque: no se validan las anotaciones", JAVA / "web/RateLimitAnnotationValidator.java",
     "if (!problems.isEmpty()) {", "if (false) {",
     "RateLimitAutoConfigurationTest#anAnnotationPointingToAnUndeclaredPolicyStopsTheStartup"),
    ("arranque: enabled=false no apaga nada", JAVA / "autoconfigure/RateLimitAutoConfiguration.java",
     '@ConditionalOnBooleanProperty(name = "ratelimit.enabled", matchIfMissing = true)\n', "",
     "RateLimitAutoConfigurationTest#itCanBeSwitchedOff"),
    ("arranque: el fail-open de la politica se ignora", JAVA / "autoconfigure/RateLimitProperties.java",
     "failOpen != null ? failOpen : defaultFailOpen", "defaultFailOpen",
     "RateLimitAutoConfigurationTest#perPolicyFailOpenWinsOverTheGlobalDefault"),
    ("arranque: los observadores no se conectan", JAVA / "autoconfigure/RateLimitAutoConfiguration.java",
     "observers.orderedStream().toList()", "java.util.List.of()",
     "RateLimitAutoConfigurationTest#verdictsBecomeMetricsWhenThereIsARegistry"),
    ("metricas: todo cuenta como aceptado", JAVA / "metrics/MicrometerRateLimitObserver.java",
     'result.degraded() ? "degraded" : result.allowed() ? "allowed" : "rejected"', '"allowed"',
     "RateLimitAutoConfigurationTest#verdictsBecomeMetricsWhenThereIsARegistry"),
]


def run_test(selector):
    command = ["./mvnw", "-B", "-ntp", "-pl", MODULE, "test", "-Dtest=" + selector,
               "-Dsurefire.failIfNoSpecifiedTests=true"]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    output = result.stdout + result.stderr
    totals = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", output, re.M)
    compiled = "COMPILATION ERROR" not in output
    ran = failed = 0
    if totals:
        ran, failures, errors, _ = (int(x) for x in totals[-1])
        failed = failures + errors
    return compiled, ran, failed


def main():
    wanted = sys.argv[1] if len(sys.argv) > 1 else ""
    selected = [m for m in MUTATIONS if wanted in m[0] or wanted in m[4]]
    killed, survived, broken = [], [], []
    for name, path, original, mutated, selector in selected:
        source = path.read_text()
        if source.count(original) != 1:
            broken.append((name, "el texto a mutar aparece %d veces en %s" % (source.count(original), path.name)))
            print("ROTA      %s" % name, flush=True)
            continue
        try:
            path.write_text(source.replace(original, mutated))
            compiled, ran, failed = run_test(selector)
        finally:
            path.write_text(source)
        if not compiled:
            broken.append((name, "la mutacion no compila: un error de compilacion no es un test en rojo"))
            print("ROTA      %s" % name, flush=True)
        elif ran == 0:
            broken.append((name, "no se ejecuto ningun test con el selector " + selector))
            print("ROTA      %s" % name, flush=True)
        elif failed == 0:
            survived.append((name, selector))
            print("SOBREVIVE %s  (%s sigue en verde)" % (name, selector), flush=True)
        else:
            killed.append(name)
            print("detectada %s  (%d de %d en rojo)" % (name, failed, ran), flush=True)

    print()
    print("Mutaciones: %d. Detectadas: %d. Sobreviven: %d. Rotas: %d."
          % (len(selected), len(killed), len(survived), len(broken)))
    for name, reason in broken:
        print("  ROTA: %s -> %s" % (name, reason))
    for name, selector in survived:
        print("  SOBREVIVE: %s -> %s" % (name, selector))
    sys.exit(0 if selected and not survived and not broken else 1)


if __name__ == "__main__":
    main()
