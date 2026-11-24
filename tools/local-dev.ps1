[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status', 'Logs', 'Migrate', 'Info', 'Validate', 'Baseline')]
    [string]$Action = 'Start',
    [ValidateSet('All', 'Redis', 'Flyway')]
    [string]$Service = 'All',
    [ValidatePattern('^[0-9]+$')]
    [string]$BaselineVersion
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $repo '.env.dev'
$composeFile = Join-Path $repo 'compose.local.yaml'
$logDirectory = Join-Path $repo 'target/local-dev'
$flywayContainer = 'jualncampus-flyway'
$redisContainer = 'redis'

function Invoke-Docker([string[]]$Arguments)
{
    & docker @Arguments
    if ($LASTEXITCODE -ne 0)
    {
        throw "Docker command failed (exit $LASTEXITCODE)."
    }
}
function Container-State([string]$Name)
{
    # Only request ASCII state fields. Docker's full JSON row contains truncated Unicode
    # command/mount strings that Windows PowerShell 5.1 can misdecode before JSON parsing.
    $raw = & docker container ls -a --filter "name=^/$Name`$" --format '{{.State}}|{{.Status}}'
    if ($LASTEXITCODE -ne 0)
    {
        throw 'Cannot read Docker container state.'
    }
    if (!$raw)
    {
        return $null
    }
    $fields = $raw -split '\|', 2
    return [pscustomobject]@{ State = $fields[0]; Status = $fields[1] }
}
function Assert-OwnedFlyway
{
    $state = Container-State $flywayContainer
    if ($state)
    {
        # A label filter avoids embedded template quotes, which Windows PowerShell 5.1
        # strips when passing arguments to native programs.
        $owner = & docker container ls -a --filter "name=^/$flywayContainer`$" --filter 'label=com.docker.compose.project=jualncampus-local' --format '{{.ID}}'
        if ($LASTEXITCODE -ne 0 -or !$owner)
        {
            throw "$flywayContainer already exists outside this local Compose project; it was not changed."
        }
        if ($state.State -eq 'running')
        {
            throw 'A Flyway command is already running. Use Logs/Status and wait for it to finish.'
        }
    }
}
function Invoke-LocalFlyway([string]$Command)
{
    if (!(Test-Path -LiteralPath $envFile))
    {
        throw 'Missing .env.dev; use the existing local MYSQL_USER / MYSQL_PASSWORD settings.'
    }
    Assert-OwnedFlyway
    # Serialize invocations before Compose can recreate the same one-shot container.
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $lock = $null
    $previousAction = $env:LOCAL_FLYWAY_ACTION
    $previousVersion = $env:LOCAL_FLYWAY_BASELINE_VERSION
    try
    {
        $lock = [IO.File]::Open((Join-Path $logDirectory 'flyway.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        Assert-OwnedFlyway
        $env:LOCAL_FLYWAY_ACTION = $Command
        if ($Command -eq 'baseline')
        {
            if (!$BaselineVersion)
            {
                throw 'Baseline requires -BaselineVersion after checking the existing schema, data migrations and backup. Never use the latest repository version as a guess.'
            }
            $env:LOCAL_FLYWAY_BASELINE_VERSION = $BaselineVersion
        }
        else
        {
            $env:LOCAL_FLYWAY_BASELINE_VERSION = '0'
        }
        $stamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff')
        $log = Join-Path $logDirectory "flyway-$Command-$stamp.log"
        Write-Host "Local MySQL target: Windows localhost:3306/jualncampus; Flyway command: $Command"
        Write-Host "Log: $log"
        $arguments = @('compose', '--env-file', $envFile, '-f', $composeFile,
        'up', '--force-recreate', '--abort-on-container-exit', '--exit-code-from', 'flyway', 'flyway')
        # Compose writes normal progress to stderr. In Windows PowerShell 5.1,
        # Stop would turn that progress into a terminating NativeCommandError.
        $previousErrorPreference = $ErrorActionPreference
        try
        {
            $ErrorActionPreference = 'Continue'
            & docker @arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log
            $result = $LASTEXITCODE
        }
        finally
        {
            $ErrorActionPreference = $previousErrorPreference
        }
        if ($result -ne 0)
        {
            throw "Flyway failed (exit $result). See $log. If this is a manually managed nonempty database, review its actual version before an explicit Baseline; no automatic baseline/repair/clean is performed."
        }
        Write-Host 'Flyway completed successfully. An Exited (0) container is normal; it is not a daemon.'
    }
    finally
    {
        $env:LOCAL_FLYWAY_ACTION = $previousAction
        $env:LOCAL_FLYWAY_BASELINE_VERSION = $previousVersion
        if ($lock)
        {
            $lock.Dispose()
        }
    }
}

Push-Location $repo
try
{
    Invoke-Docker -Arguments @('info', '--format', 'Docker server {{.ServerVersion}}')
    if ($Action -in @('Info', 'Validate', 'Migrate', 'Baseline'))
    {
        if ($Service -eq 'Redis')
        {
            throw "$Action applies to Flyway, not Redis."
        }
        Invoke-LocalFlyway $Action.ToLowerInvariant()
        return
    }
    if ($Action -eq 'Start')
    {
        if ($Service -ne 'Flyway')
        {
            if (!(Container-State $redisContainer))
            {
                throw 'Existing redis container was not found. This script does not replace it or create an empty Redis volume.'
            }
            Invoke-Docker -Arguments @('start', $redisContainer)
            Invoke-Docker -Arguments @('exec', $redisContainer, 'redis-cli', 'ping')
        }
        if ($Service -ne 'Redis')
        {
            Invoke-LocalFlyway 'migrate'
        }
    }
    elseif ($Action -eq 'Stop')
    {
        if ($Service -ne 'Redis')
        {
            Assert-OwnedFlyway
        }
        if ($Service -ne 'Flyway' -and (Container-State $redisContainer))
        {
            Invoke-Docker -Arguments @('stop', $redisContainer)
        }
        Write-Host 'Flyway is a completed one-shot task. The Windows MySQL service and application were not stopped.'
    }
    elseif ($Action -eq 'Status')
    {
        foreach ($name in @($redisContainer, $flywayContainer))
        {
            if (($Service -eq 'Redis' -and $name -ne $redisContainer) -or ($Service -eq 'Flyway' -and $name -ne $flywayContainer))
            {
                continue
            }
            $state = Container-State $name
            if ($state)
            {
                Write-Host "$name : $( $state.Status )"
            }
            else
            {
                Write-Host "$name : not created"
            }
        }
        Write-Host 'Use -Action Info to read the database migration versions.'
    }
    elseif ($Action -eq 'Logs')
    {
        foreach ($name in @($redisContainer, $flywayContainer))
        {
            if (($Service -eq 'Redis' -and $name -ne $redisContainer) -or ($Service -eq 'Flyway' -and $name -ne $flywayContainer))
            {
                continue
            }
            if (Container-State $name)
            {
                Invoke-Docker -Arguments @('logs', '--tail', '80', $name)
            }
        }
        Write-Host "Previous Flyway outputs: $logDirectory"
    }
}
finally
{
    Pop-Location
}
