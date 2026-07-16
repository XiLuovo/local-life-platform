local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local recordId = ARGV[4]
local stockAction = ARGV[5]
local releaseUser = ARGV[6]
local reason = ARGV[7]
local statusTtl = ARGV[8]
local writeDlq = ARGV[9]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local statusKey = 'seckill:order:status:' .. orderId
local retryKey = 'seckill:order:retry:' .. recordId
local compensationKey = 'seckill:order:compensated:' .. orderId
local finalizationKey = 'seckill:order:finalized:' .. recordId

if (redis.call('hget', statusKey, 'status') == 'SUCCESS') then
    redis.call('xack', 'stream.orders', 'g1', recordId)
    redis.call('del', retryKey)
    return 0
end

local shouldCompensate = stockAction ~= 'NONE' or releaseUser == '1'
local firstFinalization = redis.call('setnx', finalizationKey, '1') == 1

if (firstFinalization) then
    redis.call('expire', finalizationKey, statusTtl)
end

if (firstFinalization) then
    if (shouldCompensate) then
        redis.call('set', compensationKey, '1', 'EX', statusTtl)
    end

    if (stockAction == 'RESTORE') then
        redis.call('incrby', stockKey, 1)
    elseif (stockAction == 'ZERO') then
        redis.call('set', stockKey, 0)
    end

    if (releaseUser == '1') then
        redis.call('srem', orderKey, userId)
    end

    if (writeDlq == '1') then
        redis.call('xadd', 'stream.orders.dlq', '*',
                'originalRecordId', recordId,
                'userId', userId,
                'voucherId', voucherId,
                'id', orderId,
                'reason', reason)
    end
end

redis.call('hset', statusKey,
        'userId', userId,
        'voucherId', voucherId,
        'status', 'FAILED',
        'compensated', shouldCompensate and '1' or '0',
        'message', reason)
redis.call('expire', statusKey, statusTtl)
redis.call('xack', 'stream.orders', 'g1', recordId)
redis.call('del', retryKey)
return 1
