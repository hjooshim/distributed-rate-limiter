-- ============================================================
-- REDIS LUA SCRIPT - Distributed Sliding Window Decision Engine
-- ============================================================
--
-- PURPOSE:
--   Executes the sliding-window algorithm inside Redis so window
--   trimming, counting, and recording all happen as one atomic operation.
--
-- WHY LUA:
--   If application servers performed these steps with separate Redis calls,
--   two requests could race:
--     1. both call ZCARD and read the same count
--     2. both decide there is capacity
--     3. both add their entry and allow the request
--
--   Redis runs a Lua script atomically, so no other client can interleave
--   reads/writes while this script is evaluating the window.
--
-- STATE MODEL:
--   Each Redis key is a Sorted Set where every member represents one past
--   request that still falls inside the sliding window:
--     - score  = request timestamp in milliseconds (used for range trimming)
--     - member = timestamp + microseconds from Redis TIME (guarantees
--                uniqueness even when two requests land in the same millisecond)
--
--   Because each entry records one real request, the set always contains the
--   exact requests that arrived within the last windowMs — no approximation.
--
-- HIGH-LEVEL FLOW:
--   1. Read the current Redis time
--   2. Remove entries that have slid out of the window
--   3. Count entries remaining inside the window
--   4. If under the limit: record this request and allow it
--   5. Otherwise: compute retry-after from the oldest entry and reject
--   6. Refresh the TTL so idle keys expire automatically
local key       = KEYS[1]
local limit     = tonumber(ARGV[1])
local windowMs  = tonumber(ARGV[2])
local ttlMs     = tonumber(ARGV[3])

-- Use Redis TIME as the single source of truth for time.
-- This avoids clock-skew problems between multiple JVM instances.
local redisTime = redis.call('TIME')
local nowMs     = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

-- Step 1: Trim the window.
-- Remove every entry whose timestamp is older than (now - windowMs).
-- After this call the sorted set contains only requests that are still
-- inside the current sliding window.
local windowStart = nowMs - windowMs
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

-- Step 2: Count requests currently inside the window.
local count = tonumber(redis.call('ZCARD', key))

local allowed          = 0
local retryAfterSeconds = 0

if count < limit then
  -- Step 3: Window has capacity. Record this request so future calls
  -- see it as part of the window.
  --
  -- Member format: "<nowMs>:<microseconds>"
  -- Appending the microsecond component from Redis TIME makes each member
  -- unique even when two requests arrive within the same millisecond.
  local member = tostring(nowMs) .. ':' .. tostring(redisTime[2])
  redis.call('ZADD', key, nowMs, member)
  allowed = 1
else
  -- Step 4: Window is full. Determine how long the caller must wait.
  --
  -- The window slides forward continuously, so the earliest slot opens up
  -- exactly when the oldest entry inside the window falls out.
  -- That happens at: oldestTimestamp + windowMs
  -- Time until then:  (oldestTimestamp + windowMs) - nowMs
  --
  -- ZRANGE with index 0 0 returns the single entry with the lowest score,
  -- which is the oldest request still inside the window.
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  if oldest and #oldest >= 2 then
    local oldestMs = tonumber(oldest[2])
    local waitMs   = (oldestMs + windowMs) - nowMs
    -- After ZREMRANGEBYSCORE, every remaining entry has score > windowStart,
    -- so (oldestMs + windowMs) > nowMs and waitMs is always positive.
    -- We still clamp to 1 second as a defensive minimum.
    retryAfterSeconds = math.max(1, math.ceil(waitMs / 1000))
  else
    -- Defensive fallback: ZCARD said the window was full but ZRANGE found
    -- nothing. Ask the caller to retry in one second.
    retryAfterSeconds = 1
  end
end

-- Step 5: Refresh the TTL.
-- Keeps the sorted set alive long enough for future requests to see the
-- entries we just wrote, then lets it expire automatically once idle.
redis.call('PEXPIRE', key, ttlMs)

-- Compact return format consumed by the Java strategy:
--   "1:0" means allowed
--   "0:3" means rejected, retry after 3 seconds
return tostring(allowed) .. ':' .. tostring(retryAfterSeconds)
