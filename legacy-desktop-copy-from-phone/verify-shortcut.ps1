$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$shortcutPath = Join-Path ([Environment]::GetFolderPath("Desktop")) "VoiceBrainLive.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$exePath = $shortcut.TargetPath
Write-Host "ShortcutExists=$([bool](Test-Path $shortcutPath))"
Write-Host "Target=$exePath"
Write-Host "TargetExists=$([bool](Test-Path $exePath))"
Get-ChildItem (Join-Path $projectRoot "desktop\build\compose\binaries\main\app\VoiceBrainLive") -Filter "*.exe" -Recurse | Select-Object FullName, Length
