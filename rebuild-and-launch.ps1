$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# Close only processes launched from this project or named for this app.
Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -match '^(java|javaw|VoiceBrainLive)\.exe$') -and
    ($_.CommandLine -match 'VoiceBrainLive|compose|gradle')
} | ForEach-Object {
    try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch { }
}

& "$root\gradlew.bat" ':desktop:compileKotlin' ':desktop:test' '--offline' '--console=plain'
if ($LASTEXITCODE -ne 0) { throw "Compile/test failed with exit code $LASTEXITCODE" }

$outLog = Join-Path $root 'fresh-launch-out.log'
$errLog = Join-Path $root 'fresh-launch-err.log'
$proc = Start-Process -FilePath "$root\gradlew.bat" -ArgumentList ':desktop:run','--offline','--console=plain' -WorkingDirectory $root -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
Start-Sleep -Seconds 10

Write-Host "Gradle launcher PID: $($proc.Id)"
Get-Process java,javaw,VoiceBrainLive -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,MainWindowTitle,Responding
Write-Host '--- launch log tail ---'
Get-Content $outLog -Tail 20 -ErrorAction SilentlyContinue

