$appExe = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe'
$appDir = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\VoiceBrainLive'
$icon = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\src\main\resources\voicebrain_robot.ico'

# Ensure VoiceBrainLive.cfg exists beside VoiceBrainLive.exe and in app/
if (Test-Path "$appDir\app\VoiceBrainLive.cfg") {
    Copy-Item "$appDir\app\VoiceBrainLive.cfg" "$appDir\VoiceBrainLive.cfg" -Force -ErrorAction SilentlyContinue
}

$desktopLocations = @(
    [Environment]::GetFolderPath('Desktop'),
    'C:\Users\nayli\Desktop',
    'C:\Users\nayli\OneDrive\Desktop'
) | Select-Object -Unique

$shell = New-Object -ComObject WScript.Shell
foreach ($dir in $desktopLocations) {
    if (Test-Path $dir) {
        $shortcutPath = Join-Path $dir 'VoiceBrainLive.lnk'
        $link = $shell.CreateShortcut($shortcutPath)
        $link.TargetPath = $appExe
        $link.WorkingDirectory = $appDir
        $link.IconLocation = "$icon,0"
        $link.Description = 'VoiceBrainLive 3D Robot Assistant'
        $link.Save()
        Write-Output "Shortcut created at: $shortcutPath"
    }
}
