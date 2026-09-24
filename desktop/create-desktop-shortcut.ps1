param(
    [string]$AppExe = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($AppExe)) {
    $AppExe = Join-Path $projectRoot "desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe"
}

if (-not (Test-Path $AppExe)) {
    throw "VoiceBrainLive.exe was not found at: $AppExe. Build with :desktop:createDistributable first."
}

$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "VoiceBrainLive.lnk"
$workingDirectory = Split-Path -Parent $AppExe
$iconPath = $AppExe

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $AppExe
$shortcut.WorkingDirectory = $workingDirectory
$shortcut.IconLocation = "$iconPath,0"
$shortcut.Description = "VoiceBrainLive Windows Desktop Assistant"
$shortcut.Save()

Write-Host "Desktop shortcut created: $shortcutPath"
