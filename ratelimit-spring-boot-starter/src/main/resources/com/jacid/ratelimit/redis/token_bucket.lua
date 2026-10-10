-- Cubo de fichas.
-- KEYS[1]  hash con 'tokens' (fraccionario) y 'ts' (microsegundos del ultimo calculo)
-- ARGV     capacidad, fichas que se rellenan por periodo, periodo en microsegundos, permisos pedidos
-- Devuelve {aceptada, fichas enteras restantes, espera en ms, ms hasta llenarse}
--
-- El reloj es el de Redis, no el de quien llama: con varias instancias, relojes desajustados darian
-- a cada una un cubo distinto.
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local period = tonumber(ARGV[3])
local permits = tonumber(ARGV[4])
-- Las fichas son fraccionarias: sin este margen, esperar exactamente lo indicado podria dejar
-- 0.9999999999 fichas y rechazar otra vez.
local EPSILON = 1e-9

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * refill / period)

local allowed = 0
local retry = 0
if tokens + EPSILON >= permits then
  allowed = 1
  tokens = math.max(0, tokens - permits)
else
  retry = math.max(1, math.ceil((permits - tokens) * period / refill / 1000))
end

-- Un cubo lleno es indistinguible de uno que no existe: la clave caduca justo entonces.
local reset = math.max(1, math.ceil((capacity - tokens) * period / refill / 1000))
redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', string.format('%.0f', now))
redis.call('PEXPIRE', KEYS[1], reset)

return {allowed, math.floor(tokens + EPSILON), retry, reset}
