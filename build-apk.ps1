$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$toolchainRoot = "C:\Progetti\family-finance-v1\android\.toolchain"
$env:JAVA_HOME = "$toolchainRoot\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$toolchainRoot\android-sdk"
$env:GRADLE_USER_HOME = "$toolchainRoot\gradle-home"

Write-Host "Compilazione APK Cassa Android Standalone con Gradle..."
& .\gradlew.bat assembleDebug

$debugApk = Join-Path $scriptDir "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $debugApk) {
    Write-Host "`n=========================================="
    Write-Host "APK GENERATO CON SUCCESSO!"
    Write-Host "=========================================="
    Write-Host "Percorso: $debugApk"
    $hash = Get-FileHash -Path $debugApk -Algorithm SHA256
    Write-Host "SHA-256:  $($hash.Hash)"
    $length = (Get-Item $debugApk).Length
    Write-Host "Dimensione: $([math]::Round($length / 1MB, 2)) MB ($length bytes)"
}
