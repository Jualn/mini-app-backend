param(
    [Parameter(Mandatory)][string]$JdbcUrl,
    [Parameter(Mandatory)][string]$RuntimeClasspath,
    [ValidateSet('Inspect','DryRun','Execute')][string]$Mode = 'DryRun',
    [string]$JavaHome = 'D:\Jualn\Java\jdk-17.0.18+8',
    [string]$EnvironmentFile,
    [string]$PlanPath,
    [string]$EnvironmentId,
    [long]$RootUserId,
    [string]$ExecutorRef,
    [string]$ApprovalRef,
    [string]$ApprovalPath,
    [string]$ApprovedPlanSha256
)
$ErrorActionPreference = 'Stop'
$previousUser = $env:RBAC_INITIAL_DB_USER
$previousPassword = $env:RBAC_INITIAL_DB_PASSWORD
try {
    if ($EnvironmentFile) {
        $values = @{}
        foreach ($line in (Get-Content -LiteralPath $EnvironmentFile)) {
            if ($line -match '^\s*(MYSQL_USER|MYSQL_PASSWORD)\s*=(.*)$') {
                $values[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
            }
        }
        if (-not $values.ContainsKey('MYSQL_USER') -or -not $values.ContainsKey('MYSQL_PASSWORD')) { throw 'Explicit MYSQL_USER/MYSQL_PASSWORD required' }
        $env:RBAC_INITIAL_DB_USER = $values['MYSQL_USER']
        $env:RBAC_INITIAL_DB_PASSWORD = $values['MYSQL_PASSWORD']
    }
    $arguments = @('-cp', $RuntimeClasspath, 'cn.jualn.miniapp.module.authorization.service.RbacInitialMigration', $JdbcUrl)
    if ($Mode -eq 'Inspect') {
        if (-not $EnvironmentId -or $RootUserId -le 0 -or -not $ExecutorRef -or -not $ApprovalRef -or -not $ApprovalPath) { throw 'Explicit environment, root, executor and approval evidence required' }
        $arguments += @('inspect', $EnvironmentId)
        $arguments += @("$RootUserId", $ExecutorRef, $ApprovalRef, (Resolve-Path -LiteralPath $ApprovalPath).Path)
    } else {
        $arguments += (Resolve-Path -LiteralPath $PlanPath).Path
        if ($Mode -eq 'Execute') {
            if ($ApprovedPlanSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Exact reviewed plan SHA-256 required' }
            $arguments += @('--execute', $ApprovedPlanSha256)
        }
    }
    & (Join-Path $JavaHome 'bin/java.exe') @arguments
    if ($LASTEXITCODE -ne 0) { throw "Initial RBAC migration $Mode failed ($LASTEXITCODE); preserve plan and inspect before retry" }
} finally {
    $env:RBAC_INITIAL_DB_USER = $previousUser
    $env:RBAC_INITIAL_DB_PASSWORD = $previousPassword
}
