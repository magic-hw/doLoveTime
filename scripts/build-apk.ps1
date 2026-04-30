$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Android Gradle on Windows is fragile with non-ASCII project paths.
if ($root -match "[^\u0000-\u007F]") {
  Write-Host "Detected non-ASCII path:" $root
  Write-Host "Please move/copy project to an ASCII path, e.g. D:\\doLoveTime"
  Write-Host "Then run this script again in the new location."
  exit 1
}

$localGradle = Join-Path $root ".tools\gradle-8.7\bin\gradle.bat"
Push-Location $root
try {
  if (Test-Path ".\\gradlew.bat") {
    .\\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradlew build failed: $LASTEXITCODE" }
  } else {
    if (-not (Test-Path $localGradle)) {
      Write-Host "No gradlew.bat found, downloading local Gradle..."
      & powershell -ExecutionPolicy Bypass -File ".\\scripts\\bootstrap-wrapper.ps1"
    }

    if (-not (Test-Path $localGradle)) {
      throw "Local Gradle not found: $localGradle"
    }

    & $localGradle assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Local Gradle build failed: $LASTEXITCODE" }
  }
}
finally {
  Pop-Location
}
