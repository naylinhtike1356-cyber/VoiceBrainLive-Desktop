# Recreate Desktop Shortcut with Fresh Icon Path and Force Windows Icon Cache Refresh

$appExe = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\NilarAI\NilarAI.exe'
$appDir = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\build\compose\binaries\main\app\NilarAI'
$sourceIco = 'C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\desktop\src\main\resources\nilar_ai_logo.ico'

# 1. Store icon in a dedicated, fresh path so Windows Explorer cannot use stale cached icon
$dedicatedIcoDir = 'C:\Users\nayli\AppData\Local\NilarAI'
if (-not (Test-Path $dedicatedIcoDir)) {
    New-Item -ItemType Directory -Path $dedicatedIcoDir -Force | Out-Null
}
$targetIco = Join-Path $dedicatedIcoDir 'nilar_ai_v3.ico'
Copy-Item $sourceIco $targetIco -Force

# Also copy into app distribution dir
$appIco = Join-Path $appDir 'nilar_ai.ico'
Copy-Item $sourceIco $appIco -Force

# Ensure NilarAI.cfg is present
if (Test-Path "$appDir\app\NilarAI.cfg") {
    Copy-Item "$appDir\app\NilarAI.cfg" "$appDir\NilarAI.cfg" -Force -ErrorAction SilentlyContinue
}

# 2. Collect desktop locations
$desktopLocations = @(
    [Environment]::GetFolderPath('Desktop'),
    'C:\Users\nayli\Desktop',
    'C:\Users\nayli\OneDrive\Desktop'
) | Select-Object -Unique

# 3. Delete old shortcuts completely
$oldShortcutNames = @('Nilar AI.lnk', 'VoiceBrainLive.lnk', 'VoiceBrain.lnk')
foreach ($dir in $desktopLocations) {
    if (Test-Path $dir) {
        foreach ($oldName in $oldShortcutNames) {
            $oldPath = Join-Path $dir $oldName
            if (Test-Path $oldPath) {
                Remove-Item $oldPath -Force -ErrorAction SilentlyContinue
                Write-Output "Deleted old shortcut: $oldPath"
            }
        }
    }
}

# Wait a brief moment to ensure filesystem delete registers
Start-Sleep -Milliseconds 300

# 4. Create fresh shortcut with new icon location
$shell = New-Object -ComObject WScript.Shell
foreach ($dir in $desktopLocations) {
    if (Test-Path $dir) {
        $shortcutPath = Join-Path $dir 'Nilar AI.lnk'
        $link = $shell.CreateShortcut($shortcutPath)
        $link.TargetPath = $appExe
        $link.WorkingDirectory = $appDir
        $link.IconLocation = "$targetIco,0"
        $link.Description = 'Nilar AI - Burmese AI Voice Assistant'
        $link.Save()
        Write-Output "Created new shortcut: $shortcutPath (Icon: $targetIco)"
    }
}

# 5. Notify Windows Shell to invalidate icon cache and refresh desktop
try {
    Add-Type -TypeDefinition @"
    using System;
    using System.Runtime.InteropServices;
    public class ShellNotification {
        [DllImport("shell32.dll")]
        public static extern void SHChangeNotify(int wEventId, uint uFlags, IntPtr dwItem1, IntPtr dwItem2);
    }
"@
    # 0x08000000 = SHCNE_ASSOCCHANGED (forces Windows Explorer to reload all icons)
    [ShellNotification]::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero)
    Write-Output "Windows Icon Cache notification sent (SHCNE_ASSOCCHANGED)."
} catch {
    Write-Output "Could not send SHChangeNotify: $($_.Exception.Message)"
}
