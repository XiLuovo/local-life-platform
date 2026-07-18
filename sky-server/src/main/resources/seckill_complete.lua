local orderId = ARGV[1]
local userId = ARGV[2]
local voucherId = ARGV[3]
local recordId = ARGV[4]
local statusTtl = ARGV[5]

local statusKey = 'seckill:order:status:' .. orderId
local retryKey = 'seckill:order:retry:' .. recordId
local compensationKey = 'seckill:order:compensated:' .. orderId
local finalizationKey = 'seckill:order:finalized:' .. recordId

if (redis.call('exists', compensationKey) == 1) then
    redis.call('xack', 'stream.orders', 'g1', recordId)
    redis.call('del', retryKey)
    return 0
end

local firstFinalization = redis.call('setnx', finalizationKey, '1') == 1

if (firstFinalization) then
    redis.call('expire', finalizationKey, statusTtl)
end

redis.call('hset', statusKey,
        'userId', userId,
        'voucherId', voucherId,
        'status', 'SUCCESS',
        'compensated', '0',
        'message', '')
redis.call('expire', statusKey, statusTtl)
redis.call('xack', 'stream.orders', 'g1', recordId)
redis.call('del', retryKey)
if (firstFinalization) then
    return 1
end
return 0
