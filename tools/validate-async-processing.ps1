param(
    [string]$Maven = "mvn.cmd",
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$IncludeIntegration,
    [switch]$MysqlIntegration,
    [switch]$RedisIntegration,
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = ""
)
$ErrorActionPreference = "Stop"
$projectDirectory = Split-Path $PSScriptRoot -Parent
$temporaryPom = Join-Path $projectDirectory (".async-validation-" + [guid]::NewGuid().ToString('N') + ".pom.xml")
$tests = @(
    'MapperScanConfigurationTest',
    'RetryScheduleTest', 'JobFailureClassifierTest', 'JobHandlerRegistryTest', 'JobClaimServiceTest', 'JobExecutionContextTest',
    'EventHandlerRegistryTest', 'EventConsumptionServiceTest', 'EventStreamConsumerTest', 'OutboxPublisherTest', 'AuditCompletedEventHandlerTest', 'AuditResultPersistenceServiceTest',
    'CommentServiceImplTest', 'PostServiceImplTest', 'PostAuditCallbackTest', 'NotifyServiceImplTest',
    'NotificationDeliveryServiceImplTest', 'NotificationDeliveryMapperSqlTest', 'ActivityReminderPolicyTest', 'ActivityReminderSubjectStatusTest', 'PublicEventReminderPolicyTest',
    'ReminderReconcileServiceTest', 'ActionableTimelineChangeDetectorTest'
)
if ($IncludeIntegration) { $tests += 'AsyncInfrastructureIntegrationTest' }
$previousJavaHome = $env:JAVA_HOME
Push-Location $projectDirectory
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    [xml]$pom = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $projectDirectory 'pom.xml')
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $build = $pom.SelectSingleNode('/m:project/m:build', $ns)
    $directory = $build.SelectSingleNode('m:directory', $ns)
    if ($null -eq $directory) {
        $directory = $pom.CreateElement('directory', $pom.DocumentElement.NamespaceURI)
        [void]$build.AppendChild($directory)
    }
    $directory.InnerText = '${project.basedir}/target/async-validation'
    $config = $pom.SelectSingleNode('//m:plugin[m:artifactId="maven-compiler-plugin"]/m:configuration', $ns)
    $includes = $pom.CreateElement('testIncludes', $pom.DocumentElement.NamespaceURI)
    foreach ($testName in $tests) {
        $include = $pom.CreateElement('testInclude', $pom.DocumentElement.NamespaceURI)
        $include.InnerText = "**/$testName.java"
        [void]$includes.AppendChild($include)
    }
    [void]$config.AppendChild($includes)
    $pom.Save($temporaryPom)
    $arguments = @('-f', $temporaryPom, "-Dtest=$($tests -join ',')")
    if ($IncludeIntegration) { $arguments += '-Dasync.it=true' }
    if ($MysqlIntegration) {
        $arguments += '-Dmysql.it=true'
        $arguments += "-Dmysql.it.host=$MysqlHost"
        $arguments += "-Dmysql.it.port=$MysqlPort"
        $arguments += "-Dmysql.it.user=$MysqlUser"
        $arguments += "-Dmysql.it.password=$MysqlPassword"
    }
    if ($RedisIntegration) { $arguments += '-Dredis.it=true' }
    $arguments += 'test'
    & $Maven @arguments
    if ($LASTEXITCODE -ne 0) { throw "Async processing validation failed ($LASTEXITCODE)." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    if (Test-Path -LiteralPath $temporaryPom) { Remove-Item -LiteralPath $temporaryPom }
    Pop-Location
}
