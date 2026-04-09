local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local requestedTokens = tonumber(ARGV[3])
local ttlMs = tonumber(ARGV[4])
local redisTime = redis.call('TIME')
local nowMs = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local lastRefillMs = tonumber(redis.call('HGET', key, 'lastRefillMs'))

if tokens == nil or lastRefillMs == nil then
  tokens = capacity
  lastRefillMs = nowMs
else
  local elapsedMs = nowMs - lastRefillMs
  if elapsedMs > 0 then
    local refillTokens = (elapsedMs * capacity) / windowMs
    tokens = math.min(capacity, tokens + refillTokens)
    lastRefillMs = nowMs
  end
end

local allowed = 0
if tokens >= requestedTokens then
  tokens = tokens - requestedTokens
  allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'lastRefillMs', lastRefillMs)
redis.call('PEXPIRE', key, ttlMs)

return allowed
