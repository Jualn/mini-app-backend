[CmdletBinding()]
param([string]$Image = 'redis:7-alpine')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$evidence = Join-Path $repo 'target/logging-docker'
[void](New-Item -ItemType Directory -Force -Path $evidence)
$project = 'logging-v1-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$overlay = Join-Path $repo 'config/logging/compose.logging.yaml'
$base = Join-Path $evidence 'compose.fixture.yaml'
$rotation = Join-Path $evidence 'compose.rotation.yaml'
$resultPath = Join-Path $evidence 'result.json'
$created = $false

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE): $($Arguments[0])" }
    return $output
}

# No business service, network access, host mounts, ports or database connection.
# Require a locally available image rather than downloading a new runtime implicitly.
try {
[void](Invoke-Docker @('image', 'inspect', $Image, '--format', '{{.Id}}'))
@"
services:
  backend:
    image: $Image
    network_mode: none
    entrypoint: ["/bin/sh", "-c"]
    command: ["echo '{\"event\":\"logging-driver-probe\"}'; sleep 1"]
"@ | Set-Content -Encoding utf8 $base
# Blocking delivery here makes old-record disappearance attributable to retention rather than buffer loss.
# The separate probe above uses the unmodified production non-blocking options.
@'
services:
  backend:
    logging:
      options: !override
        max-size: "20m"
        max-file: "3"
        compress: "true"
        mode: blocking
    command:
      - >-
        awk 'BEGIN { p=""; for(j=0;j<990;j++) p=p "x"; for(i=1;i<=85000;i++) printf "{\"sequence\":%d,\"padding\":\"%s\"}\n",i,p; }'
'@ | Set-Content -Encoding utf8 $rotation

$compose = @('compose', '-p', $project, '-f', $base, '-f', $overlay)
    [void](Invoke-Docker ($compose + @('config', '--quiet')))
    $created = $true
    [void](Invoke-Docker ($compose + @('up', '-d', '--no-build', '--pull', 'never')))
    $id = Invoke-Docker ($compose + @('ps', '-a', '-q', 'backend'))
    $options = (Invoke-Docker @('inspect', $id, '--format', '{{json .HostConfig.LogConfig}}')) | ConvertFrom-Json
    if ($options.Type -ne 'local' -or $options.Config.'max-size' -ne '20m' -or
        $options.Config.'max-file' -ne '3' -or $options.Config.mode -ne 'non-blocking' -or
        $options.Config.'max-buffer-size' -ne '4m') { throw 'Production logging options did not apply.' }
    [void](Invoke-Docker @('wait', $id))
    $probe = Invoke-Docker @('logs', $id)
    if (($probe -join "`n") -notmatch 'logging-driver-probe') { throw 'docker logs did not return the probe.' }

    [void](Invoke-Docker ($compose + @('-f', $rotation, 'up', '-d', '--no-build', '--pull', 'never', '--force-recreate')))
    $id = Invoke-Docker ($compose + @('ps', '-a', '-q', 'backend'))
    $exitCode = Invoke-Docker @('wait', $id)
    if ("$exitCode" -ne '0') { throw "Rotation producer failed: $exitCode" }
    # Use Docker's supported read API; never touch daemon-owned log files.
    $retained = @(Invoke-Docker @('logs', $id))
    $first = ($retained[0] | ConvertFrom-Json).sequence
    $last = ($retained[-1] | ConvertFrom-Json).sequence
    if ($first -le 1 -or $last -ne 85000 -or $retained.Count -ge 85000 -or $retained.Count -lt 35000) {
        throw "Unexpected retained range: first=$first last=$last count=$($retained.Count)"
    }
    $previous = $first - 1
    foreach ($line in $retained) {
        $sequence = ($line | ConvertFrom-Json).sequence
        if ($sequence -ne $previous + 1) { throw 'Retained records are not a contiguous tail.' }
        $previous = $sequence
    }
    [pscustomobject]@{
        result = 'PASS'; image = $Image; productionOptions = $options
        rotationDelivery = 'blocking (excludes non-blocking drops from retention proof)'
        emitted = 85000; retained = $retained.Count; first = $first; last = $last
        createdProject = $project; resourcesRemoved = $false
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $resultPath
} catch {
    [pscustomobject]@{
        result = 'FAIL'; image = $Image; createdProject = $project
        failure = $_.Exception.Message; resourcesRemoved = !$created
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $resultPath
    throw
} finally {
    if ($created) {
        [void](Invoke-Docker ($compose + @('down', '--remove-orphans')))
        if (Test-Path -LiteralPath $resultPath) {
            $result = Get-Content -Raw $resultPath | ConvertFrom-Json
            if ($result.createdProject -eq $project) {
                $result.resourcesRemoved = $true
                $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $resultPath
            }
        }
    }
}
Get-Content -Raw $resultPath

