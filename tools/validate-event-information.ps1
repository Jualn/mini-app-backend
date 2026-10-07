param(
    [string]$Maven = "mvn.cmd",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JdbcUrl = "",
    [string[]]$TestNames = @()
)
$ErrorActionPreference = "Stop"
# Only a disposable local database with V1..V7 applied, root and empty password.
# OperationsEventDatabaseTest commits synthetic fixtures; never use a business database.
if ($JdbcUrl -and $JdbcUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/[a-zA-Z0-9_]+$') {
    throw "JdbcUrl must identify the dedicated disposable local test database."
}
$projectDirectory = Split-Path $PSScriptRoot -Parent
$temporaryPom = Join-Path $projectDirectory (".event-validation-" + [guid]::NewGuid().ToString('N') + ".pom.xml")
$tests = @('ActivityFormPolicyTest','ActivityRegistrationDatabaseTest','EventContentServiceImplTest','MediaAttachmentIdentityTest','TimelineIdentityTest',
    'AdminActivityConverterTest','MediaServiceImplTest','TimelineServiceImplTest',
    'EventTimePolicyTest','EventInformationDatabaseTest','OperationsPublishingTest','OperationsRequestContractTest','OperationsReminderTest','ActivityAuditCallbackTest','OperationsEventDatabaseTest',
    'ActivityServiceImplTest','ExamServiceImplTest','ActivityEnrollmentServiceImplTest','ExamSubscriptionServiceImplTest')
if ($TestNames.Count -gt 0) {
    foreach ($testName in $TestNames) {
        if ($testName -notmatch '^[A-Za-z][A-Za-z0-9_]*Tests?$') {
            throw "TestNames must contain simple Java test class names."
        }
    }
    $tests = $TestNames
} else {
    $tests += 'ActivitySubscriptionControllerTest'
}
$previousJavaHome = $env:JAVA_HOME
Push-Location $projectDirectory
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    [xml]$pom = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $projectDirectory 'pom.xml')
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    # Keep selected-test artifacts separate from the normal Maven build.
    $build = $pom.SelectSingleNode('/m:project/m:build', $ns)
    $directory = $build.SelectSingleNode('m:directory', $ns)
    if ($null -eq $directory) {
        $directory = $pom.CreateElement('directory', $pom.DocumentElement.NamespaceURI)
        [void]$build.AppendChild($directory)
    }
    $directory.InnerText = '${project.basedir}/target/event-validation'
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
    if ($JdbcUrl) { $arguments += "-Devent.test.jdbcUrl=$JdbcUrl" }
    $arguments += 'test'
    & $Maven @arguments
    if ($LASTEXITCODE -ne 0) { throw "Event information validation failed ($LASTEXITCODE)." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    if (Test-Path -LiteralPath $temporaryPom) { Remove-Item -LiteralPath $temporaryPom }
    Pop-Location
}
