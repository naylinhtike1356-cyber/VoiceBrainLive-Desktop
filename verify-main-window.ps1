Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -match 'VoiceBrainLive-Desktop' } | ForEach-Object {
    Write-Output ("PID=" + $_.ProcessId + " NAME=" + $_.Name + " CMD=" + $_.CommandLine)
}
Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output ("JAVA PID=" + $_.Id + " TITLE=" + $_.MainWindowTitle + " HANDLE=" + $_.MainWindowHandle + " RESPONDING=" + $_.Responding)
}
