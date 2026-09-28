Option Explicit

Dim shell, fso, appPath, project
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

project = "C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop"
appPath = project & "\desktop\build\compose\binaries\main\app\NilarAI\NilarAI.exe"

' Stop any stale launchers or previous instances from this project cleanly
shell.Run "powershell -NoProfile -ExecutionPolicy Bypass -Command ""Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'VoiceBrainLive-Desktop.*(NilarAI|VoiceBrainLive|MainKt)' -and $_.ProcessId -ne $PID } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} }""", 0, True

If fso.FileExists(appPath) Then
    shell.CurrentDirectory = fso.GetParentFolderName(appPath)
    shell.Run Chr(34) & appPath & Chr(34), 1, False
Else
    MsgBox "Nilar AI packaged executable မတွေ့ပါ။ desktop package ကို အရင် build လုပ်ပါ။", 16, "Nilar AI"
End If

Set fso = Nothing
Set shell = Nothing
