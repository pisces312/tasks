# tasks-org Build Script
# Usage: .\build.ps1 <debug|release>

param(
    [Parameter(Position=0, Mandatory=$true)]
    [ValidateSet("debug", "release")]
    [string]$BuildType,

    [string]$Flavor = "fdroid",

    # Gradle JVM args
    [string]$JvmArgs = "-Xmx8G -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
)

$ErrorActionPreference = "Stop"

# Resolve project root (script directory)
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptRoot

# Find Java
$JavaHome = if (Test-Path "D:\nili\dev\AndroidStudio\jbr") {
    "D:\nili\dev\AndroidStudio\jbr"
} elseif (Test-Path "C:\Program Files\Eclipse Adoptium\jdk-21") {
    "C:\Program Files\Eclipse Adoptium\jdk-21"
} else {
    $null
}

# Find Gradle
$GradleBat = if (Test-Path "D:\nili\dev\gradle\bin\gradle.bat") {
    "D:\nili\dev\gradle\bin\gradle.bat"
} elseif (Test-Path "D:\nili\dev\AndroidStudio\jbr\bin\java.exe") {
    # Use Gradle wrapper if available
    ".\gradlew.bat"
} else {
    "gradle.bat"
}

# Build task name
$Task = "assemble${Flavor}$(($BuildType.Substring(0,1).ToUpper())" + $BuildType.Substring(1) + "Apk"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Tasks.org Build Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Build Type : $BuildType" -ForegroundColor Yellow
Write-Host "  Flavor    : $Flavor" -ForegroundColor Yellow
Write-Host "  Task     : $Task" -ForegroundColor Yellow
Write-Host "  Java     : $($JavaHome ? $JavaHome : 'system default')" -ForegroundColor Yellow
Write-Host "  Gradle   : $GradleBat" -ForegroundColor Yellow
Write-Host ""

if (-not $JavaHome) {
    Write-Host "[WARN] JAVA_HOME not set, using system default Java" -ForegroundColor DarkYellow
}

# Set environment for Gradle
$env:JAVA_HOME = $JavaHome
$env:GRADLE_OPTS = $JvmArgs

# Run Gradle
Write-Host "[BUILD] Starting build..." -ForegroundColor Green
& $GradleBat @("--no-daemon", "--stacktrace") $Task

$exitCode = $LASTEXITCODE

if ($exitCode -eq 0) {
    Write-Host ""
    Write-Host "[OK] Build successful!" -ForegroundColor Green

    # Find APK
    $ApkDir = Join-Path $ScriptRoot "composeApp\build\outputs\apk\$Flavor\$BuildType"
    $Apk = Get-ChildItem $ApkDir -Filter "*.apk" | Select-Object -First 1

    if ($Apk) {
        Write-Host ""
        Write-Host "  APK: $($Apk.FullName)" -ForegroundColor Cyan
        Write-Host "  Size: $([math]::Round($Apk.Length / 1MB, 2)) MB" -ForegroundColor Cyan
    }
} else {
    Write-Host ""
    Write-Host "[FAIL] Build failed with exit code $exitCode" -ForegroundColor Red
}

exit $exitCode
