param(
  [string]$GradleVersion = "8.7"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root ".tools"
$zipPath = Join-Path $tools "gradle-$GradleVersion-bin.zip"
$extractDir = Join-Path $tools "gradle-$GradleVersion"
$downloadUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

New-Item -ItemType Directory -Force -Path $tools | Out-Null

if (-not (Test-Path $extractDir)) {
  Write-Host "Downloading Gradle $GradleVersion ..."
  Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
  Expand-Archive -Path $zipPath -DestinationPath $tools -Force
}

$gradleCmd = Join-Path $tools "gradle-$GradleVersion\bin\gradle.bat"
if (-not (Test-Path $gradleCmd)) {
  throw "gradle.bat not found: $gradleCmd"
}

Push-Location $root
try {
  & $gradleCmd wrapper --gradle-version $GradleVersion
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle wrapper task failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}

if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
  throw "Wrapper task finished but gradlew.bat is still missing."
}

Write-Host "Wrapper generated: .\\gradlew.bat"
