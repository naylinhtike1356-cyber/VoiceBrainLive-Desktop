[CmdletBinding()]
param(
    [ValidateSet('native','pyinstaller','electron','all')]
    [string]$Mode = 'native',
    [switch]$Clean,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

function Invoke-Gradle([string[]]$Tasks, [switch]$Packaging) {
    $args = @('--console=plain')
    if ($Packaging) { $args += '-Dorg.gradle.configuration-cache=false' }
    $args += $Tasks
    if ($Offline -and -not $Packaging) { $args += '--offline' }
    & "$Root\gradlew.bat" @args
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}

if ($Clean) {
    Write-Host 'Stopping Gradle daemons and cleaning build outputs...' -ForegroundColor Yellow
    & "$Root\gradlew.bat" --stop
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop Gradle daemons.' }
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue "$Root\desktop\build\compose\binaries"
}

Write-Host "Building VoiceBrainLive: $Mode" -ForegroundColor Cyan
Invoke-Gradle @(':desktop:compileKotlin', ':desktop:test')

switch ($Mode) {
    'native' {
        Invoke-Gradle @(':desktop:createDistributable') -Packaging
        Invoke-Gradle @(':desktop:runDistributable') -Packaging
    }
    'pyinstaller' {
        if (-not (Get-Command pyinstaller -ErrorAction SilentlyContinue)) {
            throw 'PyInstaller is not installed. Run: py -m pip install pyinstaller'
        }
        Invoke-Gradle @(':desktop:createDistributable') -Packaging
        & pyinstaller --noconfirm --clean "$Root\packaging\pyinstaller\VoiceBrainLive.spec"
        if ($LASTEXITCODE -ne 0) { throw 'PyInstaller build failed.' }
    }
    'electron' {
        if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'Node.js/npm is required for Electron Builder.' }
        Invoke-Gradle @(':desktop:createDistributable') -Packaging
        Push-Location "$Root\packaging\electron"
        npm install
        npm run dist:win
        if ($LASTEXITCODE -ne 0) { throw 'Electron Builder failed.' }
        Pop-Location
    }
    'all' {
        Invoke-Gradle @(':desktop:createDistributable') -Packaging
        Write-Host 'Native Compose distribution is ready.' -ForegroundColor Green
        Write-Host 'Run -Mode pyinstaller or -Mode electron separately to build an optional wrapper.' -ForegroundColor Yellow
    }
}

Write-Host 'Build completed.' -ForegroundColor Green
