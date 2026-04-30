$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "app\\build\\outputs\\apk\\debug\\app-debug.apk"

if (-not (Test-Path $apk)) {
  Write-Host "APK not found: $apk"
  Write-Host "Run: .\\scripts\\build-apk.ps1"
  exit 1
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
  Write-Host "adb not found in PATH. Install Android platform-tools and add adb to PATH."
  exit 1
}

& adb devices
& adb install -r $apk
Write-Host "Install complete."
