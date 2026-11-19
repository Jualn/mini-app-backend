param(
    [Parameter(Mandatory)][string]$JdbcUrl,
    [Parameter(Mandatory)][string]$RuntimeClasspath,
    [ValidateSet('Inventory','Inspect','DryRun','Execute')][string]$Mode = 'DryRun',
    [ValidateSet('BOOTSTRAP','TRANSFER','RECOVERY')][string]$Action,
    [string]$EnvironmentId,
    [long]$PreviousUserId,
    [long]$NextUserId,
    [string]$ExecutorRef,
    [string]$ApprovalRef,
    [string]$ApprovalPath,
    [string]$PlanPath,
    [string]$ApprovedPlanSha256
)

$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$arguments = @('-cp', $RuntimeClasspath, 'cn.jualn.miniapp.module.authorization.service.RbacRootOperations', $JdbcUrl)
switch ($Mode) {
    'Inventory' { $arguments += 'inventory' }
    'Inspect' {
        if (!$Action -or !$EnvironmentId -or $NextUserId -le 0 -or !$ExecutorRef -or !$ApprovalRef -or !$ApprovalPath) {
            throw 'Action, environment, next User ID, executor and approval evidence are required'
        }
        $outgoing = if ($PreviousUserId -gt 0) { "$PreviousUserId" } else { '-' }
        $arguments += @('inspect', $Action, $EnvironmentId, $outgoing, "$NextUserId",
            $ExecutorRef, $ApprovalRef, (Resolve-Path -LiteralPath $ApprovalPath).Path)
    }
    default {
        if (!$PlanPath) { throw 'PlanPath is required' }
        $arguments += (Resolve-Path -LiteralPath $PlanPath).Path
        if ($Mode -eq 'Execute') {
            if ($ApprovedPlanSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Exact reviewed plan SHA-256 is required' }
            $arguments += @('--execute', $ApprovedPlanSha256)
        }
    }
}
& $java @arguments
if ($LASTEXITCODE -ne 0) { throw "ROOT $Mode exited with code $LASTEXITCODE" }
