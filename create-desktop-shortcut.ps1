$appExe = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\NilarAI\NilarAI.exe'
$appDir = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\NilarAI'
$icon = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\src\main\resources\nilar_ai_logo.ico'

# Ensure NilarAI.cfg exists beside NilarAI.exe and in app/
if (Test-Path "$appDir\app\NilarAI.cfg") {
    Copy-Item "$appDir\app\NilarAI.cfg" "$appDir\NilarAI.cfg" -Force -ErrorAction SilentlyContinue
}

$desktopLocations = @(
    [Environment]::GetFolderPath('Desktop'),
    'C:\Users\nayli\Desktop',
    'C:\Users\nayli\OneDrive\Desktop'
) | Select-Object -Unique

$shell = New-Object -ComObject WScript.Shell
foreach ($dir in $desktopLocations) {
    if (Test-Path $dir) {
        $shortcutPath = Join-Path $dir 'Nilar AI.lnk'
        $link = $shell.CreateShortcut($shortcutPath)
        $link.TargetPath = $appExe
        $link.WorkingDirectory = $appDir
        $link.IconLocation = "$icon,0"
        $link.Description = 'Nilar AI - Burmese AI Voice Assistant'
        $link.Save()
        Write-Output "Shortcut created at: $shortcutPath"
    }
}
