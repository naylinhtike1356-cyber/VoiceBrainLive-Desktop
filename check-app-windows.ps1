$procs = Get-Process -Name 'VoiceBrainLive', 'java', 'javaw' -ErrorAction SilentlyContinue
if ($procs) {
    $procs | ForEach-Object {
        Write-Output "PID=$($_.Id) NAME=$($_.ProcessName) TITLE=$($_.MainWindowTitle) RESPONDING=$($_.Responding)"
    }
} else {
    Write-Output "NO_PROCESSES_FOUND"
}
