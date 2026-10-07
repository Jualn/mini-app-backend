local t = redis.call('TYPE', KEYS[1]).ok
if t ~= 'none' and t ~= 'string' then return {'CORRUPT'} end
local current = redis.call('GET', KEYS[1])
if current and (not tonumber(current) or tonumber(current) < 0 or redis.call('PTTL', KEYS[1]) <= 0) then return {'CORRUPT'} end
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end
return {tostring(count), tostring(redis.call('PTTL', KEYS[1]))}
