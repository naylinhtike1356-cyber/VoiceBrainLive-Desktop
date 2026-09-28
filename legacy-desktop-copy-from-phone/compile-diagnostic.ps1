$ErrorActionPreference = "Continue"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$jdk = "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
& ".\gradlew.bat" ":desktop:compileKotlin" "--stacktrace" "--console=plain" 2>&1 | Tee-Object (Join-Path $PSScriptRoot "compile-error.log")
exit $LASTEXITCODE
