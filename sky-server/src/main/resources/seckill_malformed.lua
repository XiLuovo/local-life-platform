local recordId = ARGV[1]
local reason = ARGV[2]
local payload = ARGV[3]
local idempotencyTtl = ARGV[4]

local malformedKey = 'seckill:order:malformed:' .. recordId

if (redis.call('setnx', malformedKey, '1') == 1) then
    redis.call('expire', malformedKey, idempotencyTtl)
    redis.call('xadd', 'stream.orders.dlq', '*',
            'originalRecordId', recordId,
            'reason', reason,
            'payload', payload)
end

redis.call('xack', 'stream.orders', 'g1', recordId)
redis.call('del', 'seckill:order:retry:' .. recordId)
return 1
