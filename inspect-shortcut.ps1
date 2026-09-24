$shortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) 'VoiceBrainLive.lnk'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
Write-Output ('TARGET=' + $link.TargetPath)
Write-Output ('WORKDIR=' + $link.WorkingDirectory)
Write-Output ('ICON=' + $link.IconLocation)
Write-Output ('TARGET_EXISTS=' + (Test-Path $link.TargetPath))
Write-Output ('CFG_EXISTS=' + (Test-Path ((Split-Path $link.TargetPath) + '\VoiceBrainLive.cfg')))
