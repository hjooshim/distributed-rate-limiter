-- ============================================================
-- REDIS LUA SCRIPT - Distributed Token Bucket Decision Engine
-- ============================================================
--
-- PURPOSE:
--   Executes the token-bucket algorithm inside Redis so refill, consume,
--   and state persistence happen as one atomic operation.
--
-- WHY LUA:
--   If application servers performed these steps with separate Redis calls,
--   two requests could race:
--     1. both read the same token count
--     2. both think a token is available
--     3. both decrement and allow the request
--
--   Redis runs a Lua script atomically, so no other client can interleave
--   reads/writes while this script is evaluating a bucket.
--
-- STATE MODEL:
--   Each Redis hash stores:
--     - tokens: remaining tokens in the bucket right now
--     - lastRefillMs: Redis timestamp of the last refill calculation
--
-- HIGH-LEVEL FLOW:
--   1. Read the current Redis time
--   2. Load the bucket state
--   3. Refill tokens based on elapsed time
--   4. Consume tokens if enough are available
--   5. Otherwise compute retry-after
--   6. Persist the updated bucket and refresh TTL
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local requestedTokens = tonumber(ARGV[3])
local ttlMs = tonumber(ARGV[4])

-- Use Redis TIME as the single source of truth for time.
-- This avoids clock-skew problems between multiple JVM instances.
local redisTime = redis.call('TIME')
local nowMs = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

-- Load the current bucket state from Redis.
local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local lastRefillMs = tonumber(redis.call('HGET', key, 'lastRefillMs'))

-- Step 1: Initialize a brand-new bucket as full.
-- A new caller should get its full burst capacity immediately.
if tokens == nil or lastRefillMs == nil then
  tokens = capacity
  lastRefillMs = nowMs
else
  -- Step 2: Refill existing buckets based on elapsed time.
  -- Refill rate = capacity / windowMs, so over a full logical window the
  -- bucket can refill back to its maximum size.
  local elapsedMs = nowMs - lastRefillMs
  if elapsedMs > 0 then
    local refillTokens = (elapsedMs * capacity) / windowMs
    -- Never allow the bucket to grow beyond its configured capacity.
    tokens = math.min(capacity, tokens + refillTokens)
    -- Record when this refill calculation happened so the next request only
    -- refills the time that has passed since now.
    lastRefillMs = nowMs
  end
end

local allowed = 0
local retryAfterSeconds = 0

-- Step 3: Spend tokens if enough are available for this request.
if tokens >= requestedTokens then
  tokens = tokens - requestedTokens
  allowed = 1
else
  -- Step 4: Not enough tokens. Estimate how long it will take for the
  -- missing amount to refill, then round up to seconds for Retry-After.
  local missingTokens = requestedTokens - tokens
  local waitMs = math.ceil((missingTokens * windowMs) / capacity)
  retryAfterSeconds = math.max(1, math.ceil(waitMs / 1000))
end

-- Step 5: Persist the latest bucket state and extend the TTL.
-- Idle buckets will disappear automatically once they have not been used
-- for long enough, which keeps Redis from storing abandoned client state.
redis.call('HSET', key, 'tokens', tokens, 'lastRefillMs', lastRefillMs)
redis.call('PEXPIRE', key, ttlMs)

-- Compact return format consumed by the Java strategy:
--   "1:0" means allowed
--   "0:3" means rejected, retry after 3 seconds
return tostring(allowed) .. ':' .. tostring(retryAfterSeconds)
