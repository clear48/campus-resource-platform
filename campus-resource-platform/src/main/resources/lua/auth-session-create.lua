-- 会话创建和七天绝对 TTL 在一个原子操作中完成。
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'currentHash', ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
