-- Ventana deslizante exacta.
-- KEYS[1]  conjunto ordenado: una entrada por permiso aceptado, con su instante como puntuacion
-- ARGV     limite, periodo en microsegundos, permisos pedidos
-- Devuelve {aceptada, restantes, espera en ms, ms hasta quedar vacia}
local limit = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', now - period))
local count = redis.call('ZCARD', KEYS[1])

local allowed = 0
local retry = 0
if count + permits <= limit then
  allowed = 1
  local score = string.format('%.0f', now)
  for i = 1, permits do
    -- El sufijo evita que dos permisos del mismo microsegundo se pisen como un unico miembro.
    redis.call('ZADD', KEYS[1], score, score .. ':' .. (count + i))
  end
  count = count + permits
else
  -- Solo se apuntan las aceptadas: un rechazo no alarga la espera de nadie.
  -- Tienen que caducar (count + permits - limit) entradas; la ultima de ellas marca la espera.
  local index = count + permits - limit - 1
  local entry = redis.call('ZRANGE', KEYS[1], index, index, 'WITHSCORES')
  retry = math.max(1, math.ceil((tonumber(entry[2]) + period - now) / 1000))
end

local reset = 0
if count > 0 then
  local newest = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
  reset = math.max(1, math.ceil((tonumber(newest[2]) + period - now) / 1000))
  redis.call('PEXPIRE', KEYS[1], reset)
end

return {allowed, limit - count, retry, reset}
