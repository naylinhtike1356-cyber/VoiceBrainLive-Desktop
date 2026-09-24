Option Explicit

Dim shell, fso, appPath, project
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

project = "C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop"
appPath = project & "\desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe"

' Stop only stale launchers from this project; do not touch unrelated Java processes.
shell.Run "powershell -NoProfile -ExecutionPolicy Bypass -Command ""Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'VoiceBrainLive-Desktop.*(gradle|MainKt|VoiceBrainLive.exe)' -and $_.ProcessId -ne $PID } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} }""", 0, True

If fso.FileExists(appPath) Then
    ' Start the packaged executable directly. This prevents Gradle/Java wrapper
    ' processes from remaining after the user closes the application window.
    shell.Run Chr(34) & appPath & Chr(34), 1, False
Else
    MsgBox "VoiceBrainLive packaged executable မတွေ့ပါ။ desktop package ကို အရင် build လုပ်ပါ။", 16, "VoiceBrainLive"
End If

Set fso = Nothing
Set shell = Nothing
