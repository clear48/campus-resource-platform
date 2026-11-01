-- 使用单个会话 Hash，退出先执行时 CAS 不能重新创建它。
local current = redis.call('HGET', KEYS[1], 'currentHash')
if not current then return 0 end
if current ~= ARGV[1] then
    -- 只有已知旧凭据的重放撤销会话，猜测 secret 不会踢掉合法用户。
    if redis.call('HEXISTS', KEYS[1], 'used:' .. ARGV[1]) == 1 then
        redis.call('DEL', KEYS[1])
    end
    return 0
end
if ARGV[3] == 'inspect' then return 1 end
-- 正常 15 分钟刷新七天约 672 次；限制 2048 次，避免恶意高频轮换撑大 Hash。
if redis.call('HLEN', KEYS[1]) >= 2050 then
    redis.call('DEL', KEYS[1])
    return 0
end
local ttl = redis.call('TTL', KEYS[1])
if ttl <= 0 then return 0 end
redis.call('HSET', KEYS[1], 'currentHash', ARGV[2], 'used:' .. ARGV[1], '1')
return ttl
