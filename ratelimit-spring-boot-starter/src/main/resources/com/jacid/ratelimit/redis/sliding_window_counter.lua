-- Ventana deslizante aproximada.
-- KEYS[1]  hash con 'window' (indice de la ventana actual), 'current', 'previous' y 'period'
-- ARGV     limite, periodo en microsegundos, permisos pedidos
-- Devuelve {aceptada, restantes, espera en ms, ms hasta quedar vacia}
--
-- Se estima lo gastado en el ultimo periodo como
--     previous * (parte de la ventana anterior que aun cae dentro) + current
-- lo que supone que las peticiones de la ventana anterior se repartieron de manera uniforme.
local limit = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local EPSILON = 1e-9

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
local window = math.floor(now / period)
local window_start = window * period

local state = redis.call('HMGET', KEYS[1], 'window', 'current', 'previous', 'period')
local stored_window = tonumber(state[1])
local current = tonumber(state[2]) or 0
local previous = tonumber(state[3]) or 0

if stored_window == nil or tonumber(state[4]) ~= period then
  -- Sin estado, o con ventanas de otro periodo: su indice no es comparable.
  current = 0
  previous = 0
elseif stored_window == window - 1 then
  previous = current
  current = 0
elseif stored_window ~= window then
  current = 0
  previous = 0
end

local weight = 1 - (now - window_start) / period
local used = previous * weight + current

local allowed = 0
local retry_at = now
if used + permits <= limit + EPSILON then
  allowed = 1
  current = current + permits
  used = used + permits
elseif current + permits <= limit then
  -- Cabe en esta misma ventana en cuanto la anterior pese lo bastante poco.
  local needed_weight = (limit - current - permits) / previous
  retry_at = window_start + (1 - needed_weight) * period
else
  -- No cabe ni con la anterior a cero: hay que esperar a que la actual pase a ser la anterior.
  local needed_weight = (limit - permits) / current
  retry_at = window_start + period + (1 - needed_weight) * period
end

local retry = 0
if allowed == 0 then
  retry = math.max(1, math.ceil((retry_at - now) / 1000))
end

-- Lo contado en la ventana actual pesa hasta que acaba la siguiente.
local reset = 0
if current > 0 then
  reset = math.max(1, math.ceil((window_start + 2 * period - now) / 1000))
elseif previous > 0 then
  reset = math.max(1, math.ceil((window_start + period - now) / 1000))
end

if reset > 0 then
  redis.call('HSET', KEYS[1],
    'window', string.format('%.0f', window),
    'current', current,
    'previous', previous,
    'period', string.format('%.0f', period))
  redis.call('PEXPIRE', KEYS[1], reset)
end

return {allowed, math.max(0, math.floor(limit - used + EPSILON)), retry, reset}
