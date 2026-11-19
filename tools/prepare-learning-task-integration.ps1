param([string]$OutputPath = 'target/learning-task-integration-snapshot.json')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repo
try {
    function Hash-Entry([string]$Path, [string]$Label) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing input: $Label" }
        [ordered]@{ path = $Label; sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    }
    $contractFiles = @('api/openapi.yaml', 'api/paths/learning-task.yaml', 'api/schemas/learning-task.yaml',
        'docs/coordination/learning-task.md', 'docs/coordination/learning-task-v1-closeout.md', 'api/catalogs/rbac-v1.json')
    $contracts = @($contractFiles | ForEach-Object { Hash-Entry "../contracts/$_" $_ })
    $migrations = @(Get-ChildItem src/main/resources/db/migration -File -Filter 'V*.sql' |
        Sort-Object Name | ForEach-Object { Hash-Entry $_.FullName $_.Name })
    $sources = @(Get-ChildItem src/main -Recurse -File | Sort-Object FullName | ForEach-Object {
        Hash-Entry $_.FullName $_.FullName.Substring($repo.Length + 1).Replace('\','/')
    })
    $evidenceFiles = @('learning-task-vertical-version.json', 'learning-task-vertical-exchanges.json',
        'learning-task-vertical-facts.json', 'learning-task-vertical-run.log',
        'learning-task-vertical-v44-replay.log', 'learning-task-vertical-v44-validate.log',
        'learning-task-integration-environment.txt')
    $evidence = @($evidenceFiles | ForEach-Object { Hash-Entry "target/$_" "target/$_" })
    $previous = Get-Content target/learning-task-vertical-version.json -Raw | ConvertFrom-Json
    $drift = @($previous.sources | Where-Object {
        -not (Test-Path -LiteralPath $_.path) -or
        (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256
    } | ForEach-Object { $_.path })
    $catalog = Get-Content ../contracts/api/catalogs/rbac-v1.json -Raw | ConvertFrom-Json
    $role = @($catalog.roles | Where-Object code -eq 'LEARNING_MANAGER')
    if ($role.Count -ne 1 -or $role[0].permissionCodes.Count -ne 6) { throw 'Unexpected LEARNING_MANAGER contract' }
    $runtimeCatalog = Hash-Entry src/main/resources/rbac/rbac-v1.json 'src/main/resources/rbac/rbac-v1.json'
    if ($runtimeCatalog.sha256 -ne $contracts[-1].sha256) { throw 'Runtime catalog differs from canonical catalog' }
    $clients = @('admin-web', 'mini-program') | ForEach-Object {
        [ordered]@{ workspace = $_; head = (& git -C "../$_" rev-parse HEAD); dirty = [bool](& git -C "../$_" status --porcelain) }
    }
    $snapshot = [ordered]@{
        recordedAt = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).ToString('o')
        scope = 'Read-only preparation snapshot; no target deployment or real-client success claimed'
        backendHead = (& git rev-parse HEAD)
        backendDirty = [bool](& git status --porcelain)
        releaseArtifact = $null
        contracts = $contracts
        runtimeCatalog = $runtimeCatalog
        requestedRole = [ordered]@{ code = 'LEARNING_MANAGER'; namespace = 'LEARNING_TASK'; scopeType = 'GLOBAL'; permissions = $role[0].permissionCodes }
        migrations = $migrations
        productionSources = $sources
        previousIsolatedEvidence = $evidence
        initialMigrationEvidence = @(@('approval.txt','plan.json','before.json','receipt.json','replayed-receipt.json','after-verification.json','ddl-execution.txt') | ForEach-Object {
            $item = "target/rbac-initial-migration/$_"
            if (Test-Path -LiteralPath $item) { Hash-Entry $item $item }
        })
        initialCorrectionEvidence = @(@('approval.txt','plan.json','before.json','receipt.json','replayed-receipt.json','after-verification.json','ddl-execution.txt') | ForEach-Object {
            $item = "target/rbac-initial-correction/$_"
            if (Test-Path -LiteralPath $item) { Hash-Entry $item $item }
        })
        changedSinceIsolatedSourceSnapshot = $drift
        clients = @($clients)
        realClientAcceptance = 'NOT RUN'
        realWechatSafetyAcceptance = 'NOT RUN'
        environmentEvidence = 'target/learning-task-integration-environment.txt (saved read-only probe; collector does not re-probe services or database)'
        note = 'No secrets read from .env by this collector. Source/config hashes identify local files, not deployed artifacts. Environment probe uses repository dev defaults, not verified runtime configuration.'
    }
    $fullOutput = [IO.Path]::GetFullPath((Join-Path $repo $OutputPath))
    $parent = Split-Path -Parent $fullOutput
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $snapshot | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $fullOutput -Encoding utf8
    Write-Output "Snapshot written: $fullOutput"
    Write-Output "Canonical/runtime catalog match; migrations=$($migrations.Count); source drift=$($drift.Count); real acceptance=NOT RUN"
} finally { Pop-Location }
