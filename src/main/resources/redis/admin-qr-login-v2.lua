-- Short-lived authentication truth. All validation precedes mutations; Lua errors do not roll back.
local op = ARGV[1]
local function kind(key) return redis.call('TYPE', key).ok end
local function validType(key, expected)
    local t = kind(key)
    return t == 'none' or t == expected
end
if not validType(KEYS[1], 'hash') or not validType(KEYS[2], 'string')
    or not validType(KEYS[3], 'string') or not validType(KEYS[4], 'hash')
    or not validType(KEYS[5], 'string') or not validType(KEYS[6], 'string') then
    return {'CORRUPT'}
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local function same(a, b)
    if not a or not b or #a ~= 64 or #b ~= 64 then return false end
    local different = 0
    for i = 1, 64 do
        if string.byte(a, i) ~= string.byte(b, i) then different = 1 end
    end
    return different == 0
end
if op == 'publish' then
    local ttl, retention, interval = tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7])
    if not ttl or ttl <= 0 or ttl > 300000 or not retention or retention < 300000
        or not interval or interval < 1000 or #ARGV[3] ~= 64 or #ARGV[4] ~= 64
        or #ARGV[8] < 8 or #ARGV[8] > 2097152 then return {'CORRUPT'} end
    for i = 1, 4 do if redis.call('EXISTS', KEYS[i]) == 1 then return {'COLLISION'} end end
    local expires = now + ttl
    redis.call('HSET', KEYS[1], 'schemaVersion', '1', 'status', 'PENDING',
        'createdAt', now, 'expiresAt', expires, 'pollIntervalMs', interval,
        'sceneHash', ARGV[3], 'pollSecretHash', ARGV[4])
    redis.call('PEXPIREAT', KEYS[1], expires + retention)
    redis.call('SET', KEYS[2], ARGV[2], 'PXAT', expires + retention)
    redis.call('SET', KEYS[3], ARGV[8], 'PXAT', expires)
    return {'OK', '', ARGV[2], 'PENDING', tostring(expires), tostring(interval), '', '', '', '', '', '', '', tostring(now)}
end
if redis.call('EXISTS', KEYS[1]) == 0 then return {'MISSING'} end
local values = redis.call('HMGET', KEYS[1], 'schemaVersion', 'status', 'expiresAt', 'pollIntervalMs',
    'sceneHash', 'pollSecretHash', 'boundUserId', 'confirmedAt', 'consumedAt',
    'candidateToken', 'candidateTokenHash', 'candidateProfileJson', 'tokenExpiresAt', 'createdAt', 'scannedAt')
local status, expires, interval = values[2], tonumber(values[3]), tonumber(values[4])
local known = {PENDING=true, SCANNED=true, CONFIRMED=true, CONSUMED=true, EXPIRED=true, REJECTED=true, CANCELLED=true}
if values[1] ~= '1' or not known[status] or not expires or not interval or interval < 1000
    or not values[5] or #values[5] ~= 64 or not values[6] or #values[6] ~= 64
    or not tonumber(values[14]) or expires <= tonumber(values[14]) then return {'CORRUPT'} end
if values[7] and (not tonumber(values[7]) or not tonumber(values[15])) then return {'CORRUPT'} end
if (status == 'SCANNED' or status == 'CONFIRMED' or status == 'CONSUMED') and not values[7] then return {'CORRUPT'} end
if (status == 'CONFIRMED' or status == 'CONSUMED') and not tonumber(values[8]) then return {'CORRUPT'} end
if values[10] or values[11] or values[12] or values[13] then
    if not values[10] or #values[10] == 0 or not values[11] or #values[11] ~= 64
        or not values[12] or #values[12] == 0 or not tonumber(values[13]) then return {'CORRUPT'} end
end
if status == 'CONSUMED' and (not tonumber(values[9]) or not values[10]) then return {'CORRUPT'} end
local mobile = op == 'scan' or op == 'inspect' or op == 'confirm' or op == 'reject' or op == 'denyMobile'
if mobile then
    if not same(values[5], ARGV[3]) or redis.call('GET', KEYS[2]) ~= ARGV[2] then return {'MISSING'} end
    if values[7] and values[7] ~= ARGV[4] then return {'SUBJECT_CONFLICT'} end
else
    if not same(values[6], ARGV[3]) then return {'MISSING'} end
end
-- Validate action arguments and finalize keys before lazy expiry or any other write.
if mobile and (not tonumber(ARGV[4]) or tonumber(ARGV[4]) <= 0) then return {'CORRUPT'} end
if op == 'reserve' and (not ARGV[4] or #ARGV[4] == 0 or #ARGV[5] ~= 64
    or #ARGV[6] == 0 or not tonumber(ARGV[7]) or tonumber(ARGV[7]) <= now) then return {'CORRUPT'} end
if op == 'finalize' and (not same(values[11], ARGV[4]) or not tonumber(values[13])) then return {'CORRUPT'} end
local before = status
local terminal = status == 'CONSUMED' or status == 'EXPIRED' or status == 'REJECTED' or status == 'CANCELLED'
local function snapshot(code, image)
    return {code, before ~= status and before or '', ARGV[2], status, tostring(expires), tostring(interval),
        values[8] or '', values[9] or '', values[7] or '', values[10] or '', values[11] or '',
        values[12] or '', values[13] or '', tostring(now), image or ''}
end
local function change(target)
    status = target
    redis.call('HSET', KEYS[1], 'status', status)
end
if not terminal and now >= expires then change('EXPIRED') end
if op == 'read' or op == 'inspect' then return snapshot('OK') end
if op == 'scan' then
    if status == 'PENDING' then
        values[7] = ARGV[4]
        redis.call('HSET', KEYS[1], 'boundUserId', ARGV[4], 'scannedAt', now)
        change('SCANNED')
    end
    return snapshot('OK')
end
if op == 'cancel' then
    if not terminal and status ~= 'EXPIRED' then change('CANCELLED') end
    return snapshot('OK')
end
if op == 'reject' and status == 'REJECTED' then return snapshot('OK') end
if op == 'confirm' and status == 'CONSUMED' then return snapshot('OK') end
if op == 'finalize' and status == 'CONSUMED' then return snapshot('REPLAY') end
if op == 'reserve' and status == 'CONSUMED' then return snapshot('REPLAY') end
if op == 'denyWeb' and status == 'CONSUMED' then return snapshot('REPLAY') end
if status == 'EXPIRED' then return snapshot('EXPIRED') end
if status == 'CANCELLED' then return snapshot('CANCELLED') end
if status == 'REJECTED' then return snapshot('REJECTED') end
if op == 'code' then
    if status ~= 'PENDING' and status ~= 'SCANNED' then return snapshot('INVALID_STATE') end
    local image = redis.call('GET', KEYS[3])
    if not image or #image < 8 then return {'CORRUPT'} end
    return snapshot('OK', image)
end
if op == 'confirm' or op == 'reject' or op == 'denyMobile' then
    if not values[7] then return snapshot('INVALID_STATE') end
    if op == 'confirm' and status == 'CONFIRMED' then return snapshot('OK') end
    if status ~= 'SCANNED' then return snapshot('INVALID_STATE') end
    if op == 'confirm' then
        values[8] = tostring(now)
        redis.call('HSET', KEYS[1], 'confirmedAt', now)
        change('CONFIRMED')
    else change('REJECTED') end
    return snapshot('OK')
end
if status ~= 'CONFIRMED' then return snapshot('INVALID_STATE') end
if op == 'denyWeb' then change('REJECTED'); return snapshot('OK') end
if op == 'reserve' then
    if not values[10] then
        values[10], values[11], values[12], values[13] = ARGV[4], ARGV[5], ARGV[6], ARGV[7]
        redis.call('HSET', KEYS[1], 'candidateToken', ARGV[4], 'candidateTokenHash', ARGV[5],
            'candidateProfileJson', ARGV[6], 'tokenExpiresAt', ARGV[7])
    end
    return snapshot('OK')
end
if op == 'finalize' then
    if redis.call('EXISTS', KEYS[4]) ~= 0 then return {'CORRUPT'} end
    if tonumber(values[13]) <= now or redis.call('GET', KEYS[5]) ~= values[7]
        or redis.call('PTTL', KEYS[5]) <= 0 or redis.call('EXISTS', KEYS[6]) ~= 0 then return snapshot('NOT_READY') end
    redis.call('HSET', KEYS[4], 'tokenHash', values[11], 'userId', values[7], 'tokenExpiresAt', values[13])
    redis.call('PEXPIREAT', KEYS[4], values[13])
    values[9] = tostring(now)
    redis.call('HSET', KEYS[1], 'status', 'CONSUMED', 'consumedAt', now)
    status = 'CONSUMED'
    return snapshot('COMMITTED')
end
return {'CORRUPT'}
