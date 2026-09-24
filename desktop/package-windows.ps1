param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME is not set. Install JDK 17 and run: `$env:JAVA_HOME = 'C:\Path\To\JDK'"
}

$env:JAVA_HOME = $JavaHome
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $javaExe)) {
    throw "JAVA_HOME does not contain bin\java.exe: $env:JAVA_HOME"
}

Write-Host "Using Java: $(& $javaExe -version 2>&1 | Select-Object -First 1)"

# Creates the app image under desktop/build/compose/binaries/main/app.
& .\gradlew.bat :desktop:createDistributable

if (-not $SkipInstaller) {
    # Creates a Windows .exe installer and an .msi installer.
    & .\gradlew.bat :desktop:packageExe :desktop:packageMsi
}

$dist = Join-Path $projectRoot "dist\windows"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$paths = @(
    (Join-Path $projectRoot "desktop\build\compose\binaries\main\app"),
    (Join-Path $projectRoot "desktop\build\compose\binaries\main\exe"),
    (Join-Path $projectRoot "desktop\build\compose\binaries\main\msi")
)

foreach ($path in $paths) {
    if (Test-Path $path) {
        Copy-Item -Path $path -Destination $dist -Recurse -Force
    }
}

$portableExe = Join-Path $projectRoot "desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe"
if (Test-Path $portableExe) {
    & powershell.exe -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "create-desktop-shortcut.ps1") -AppExe $portableExe
}

Write-Host "Packaging complete. Review: $dist"
Get-ChildItem -Path $dist -Recurse -File | Select-Object FullName, Length
