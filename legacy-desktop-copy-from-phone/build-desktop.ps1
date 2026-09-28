$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$jdkCandidates = @(
    "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot",
    "C:\Program Files\Java\jdk-17"
)
$jdk = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
if (-not $jdk) {
    $jdk = (Get-ChildItem "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-17*" } |
        Select-Object -First 1).FullName
}
if (-not $jdk) { throw "JDK 17 not found. Install Microsoft.OpenJDK.17 first." }

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
& "$jdk\bin\java.exe" -version
& ".\gradlew.bat" ":desktop:createDistributable" "--stacktrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& ".\desktop\create-desktop-shortcut.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Desktop shortcut build completed."
