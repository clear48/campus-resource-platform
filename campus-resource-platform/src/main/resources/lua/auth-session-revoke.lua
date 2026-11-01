-- 接受当前或曾经使用过的合法凭据，以覆盖刷新/退出竞争。
local current = redis.call('HGET', KEYS[1], 'currentHash')
if not current then return 0 end
if current == ARGV[1] or redis.call('HEXISTS', KEYS[1], 'used:' .. ARGV[1]) == 1 then
    return redis.call('DEL', KEYS[1])
end
return -1
