param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_]+$')][string]$DatabaseName,
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][string]$ExpectedServerUuid,
    [string]$EnvironmentFile = '.env',
    [switch]$Apply
)
$ErrorActionPreference='Stop'
$projectDirectory=Split-Path $PSScriptRoot -Parent
$mysql='D:\Jualn\MySQL\MySQL Server 8.4\bin\mysql.exe'
$values=@{}
foreach($line in (Get-Content -LiteralPath $EnvironmentFile)) {
    if($line -match '^\s*(MYSQL_USER|MYSQL_PASSWORD)\s*=(.*)$') {
        $values[$matches[1]]=$matches[2].Trim().Trim('"').Trim("'")
    }
}
if(-not $values.ContainsKey('MYSQL_USER') -or -not $values.ContainsKey('MYSQL_PASSWORD')) { throw 'Explicit local database credentials required' }
$previousPassword=$env:MYSQL_PWD
try {
    $env:MYSQL_PWD=$values['MYSQL_PASSWORD']
    function Query([string]$Sql) {
        $result=& $mysql --no-defaults --host=127.0.0.1 "--port=$Port" "--user=$($values['MYSQL_USER'])" "--database=$DatabaseName" --batch --raw --skip-column-names "--execute=$Sql"
        if($LASTEXITCODE -ne 0) { throw 'Local catalog SQL failed; preserve the release evidence' }
        return ($result -join "`n")
    }
    if((Query 'SELECT @@server_uuid') -cne $ExpectedServerUuid) { throw 'Server identity mismatch' }
    $manifestPath=Join-Path $projectDirectory 'src/main/resources/rbac/local-admin-test.json'
    $catalogPath=Join-Path $projectDirectory 'src/main/resources/rbac/rbac-v1.json'
    $release=Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $catalog=Get-Content -Raw -LiteralPath $catalogPath | ConvertFrom-Json
    $digest=(Get-FileHash -LiteralPath $catalogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach($code in $release.permissionCodes) {
        $entry=@($catalog.permissions | Where-Object code -CEQ $code)
        if($entry.Count -ne 1 -or $entry[0].lifecycle -eq 'DISABLED') { throw "Unsupported local permission $code" }
    }
    foreach($code in $release.roleCodes) {
        $entry=@($catalog.roles | Where-Object code -CEQ $code)
        if($entry.Count -ne 1 -or @($entry[0].permissionCodes | Where-Object { $_ -cnotin $release.permissionCodes }).Count) { throw "Incomplete local role $code" }
    }
    function SqlList($Codes) {
        foreach($code in $Codes) { if($code -notmatch '^[A-Za-z0-9_:-]+$') { throw 'Unsafe code in local release' } }
        return (($Codes | ForEach-Object { "'$_'" }) -join ',')
    }
    $permissions=SqlList $release.permissionCodes
    $roles=SqlList $release.roleCodes
    if([int](Query "SELECT COUNT(*) FROM rbac_permission_definition WHERE catalog_digest<>'$digest'") -ne 0 -or
       [int](Query "SELECT COUNT(*) FROM rbac_role WHERE catalog_digest<>'$digest'") -ne 0) { throw 'Catalog digest mismatch; apply reviewed migrations first' }
    if([int](Query "SELECT COUNT(*) FROM user_profile u LEFT JOIN rbac_authorization_authority a ON u.id=a.user_id WHERE a.user_id IS NULL OR a.source NOT IN ('LEGACY','ASSIGNMENT') OR a.version NOT REGEXP '[^[:space:]]'") -ne 0) { throw 'Authority coverage/corruption preflight failed' }
    if([int](Query "SELECT COUNT(*) FROM rbac_permission_definition WHERE code IN ($permissions) AND lifecycle='DISABLED'") -ne 0) { throw 'A selected permission was disabled; do not override' }
    if([int](Query "SELECT COUNT(*) FROM rbac_role WHERE code IN ($roles) AND lifecycle='DISABLED'") -ne 0) { throw 'A selected role was disabled; do not override' }
    $fingerprintSql="SET SESSION group_concat_max_len=1048576; SELECT SHA2(COALESCE(GROUP_CONCAT(SHA2(CONCAT_WS('|',id,user_id,role_code,namespace,scope_type,granted_at,COALESCE(expires_at,''),COALESCE(revoked_at,''),version,grant_change_id,COALESCE(revoke_change_id,''),COALESCE(current_slot,'')),256) ORDER BY id SEPARATOR ','),''),256) FROM rbac_user_role_assignment; SELECT SHA2(COALESCE(GROUP_CONCAT(SHA2(CONCAT_WS('|',user_id,source,version,updated_at),256) ORDER BY user_id SEPARATOR ','),''),256) FROM rbac_authorization_authority;"
    $beforeFingerprint=Query $fingerprintSql
    $permissionState=Query "SELECT JSON_ARRAYAGG(JSON_OBJECT('code',code,'lifecycle',lifecycle,'readiness',readiness,'grantable',grantable,'reason',unavailable_reason)) FROM rbac_permission_definition"
    $roleState=Query "SELECT JSON_ARRAYAGG(JSON_OBJECT('code',code,'lifecycle',lifecycle,'readiness',readiness,'grantable',grantable,'reason',unavailable_reason)) FROM rbac_role"
    [pscustomobject]@{database=$DatabaseName;serverUuid=$ExpectedServerUuid;permissionCount=$release.permissionCodes.Count;roleCount=$release.roleCodes.Count;mode= $(if($Apply){'apply'}else{'inspect'})} | ConvertTo-Json -Compress
    if(-not $Apply) { return }
    $evidence=Join-Path $projectDirectory ('target/local-admin-release-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $evidence | Out-Null
    Set-Content -LiteralPath (Join-Path $evidence 'permissions-before.json') -Value $permissionState -Encoding utf8
    Set-Content -LiteralPath (Join-Path $evidence 'roles-before.json') -Value $roleState -Encoding utf8
    Set-Content -LiteralPath (Join-Path $evidence 'authorization-fingerprints-before.txt') -Value $beforeFingerprint -Encoding utf8
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $evidence 'release-manifest.json')
    $restore=[System.Collections.Generic.List[string]]::new();$restore.Add('START TRANSACTION;');$restore.Add("SELECT code FROM rbac_role WHERE code='ROOT' FOR UPDATE;")
    foreach($pair in @(@('rbac_permission_definition',$permissionState),@('rbac_role',$roleState))) {
        foreach($row in ($pair[1] | ConvertFrom-Json)) {
            $reason=if($null -eq $row.reason){'NULL'}else{"'"+$row.reason.Replace("'","''")+"'"}
            $restore.Add("UPDATE $($pair[0]) SET lifecycle='$($row.lifecycle)',readiness='$($row.readiness)',grantable=$($row.grantable),unavailable_reason=$reason WHERE code='$($row.code)';")
        }
    }
    $restore.Add('COMMIT;');$restore | Set-Content -LiteralPath (Join-Path $evidence 'restore-catalog.sql') -Encoding utf8
    $sql="START TRANSACTION; SELECT code FROM rbac_role WHERE code='ROOT' FOR UPDATE; UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code IN ($permissions); UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code IN ($roles); COMMIT;"
    Query $sql | Out-Null
    $afterFingerprint=Query $fingerprintSql
    Set-Content -LiteralPath (Join-Path $evidence 'authorization-fingerprints-after.txt') -Value $afterFingerprint -Encoding utf8
    if($afterFingerprint -cne $beforeFingerprint) { throw 'Authorization changed during publication; inspect before continuing' }
    $result=Query "SELECT JSON_OBJECT('enabledPermissions',(SELECT COUNT(*) FROM rbac_permission_definition WHERE code IN ($permissions) AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1),'enabledRoles',(SELECT COUNT(*) FROM rbac_role WHERE code IN ($roles) AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1))"
    Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Value $result -Encoding utf8
    [pscustomobject]@{evidence=$evidence;authorizationUnchanged=$true;result=($result | ConvertFrom-Json)} | ConvertTo-Json -Compress
} finally { $env:MYSQL_PWD=$previousPassword }
