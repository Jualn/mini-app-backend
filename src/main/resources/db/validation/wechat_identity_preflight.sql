-- Read-only preflight for V23. Any returned row requires an explicit identity-conflict decision.
SELECT `mp_openid`, COUNT(*) AS `linked_user_count`
FROM `user_profile`
WHERE `mp_openid` IS NOT NULL
  AND `mp_openid` <> ''
GROUP BY `mp_openid`
HAVING COUNT(*) > 1;

-- Empty strings are not valid identities and would also collapse under a unique key.
SELECT `id`, `deleted_at`
FROM `user_profile`
WHERE `mp_openid` = ''
ORDER BY `id`;
