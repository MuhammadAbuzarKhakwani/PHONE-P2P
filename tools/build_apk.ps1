<#
.SYNOPSIS
    One command to check, build and install the Khakwani P2P debug APK on Windows.

.DESCRIPTION
    Verifies the toolchain, writes android/local.properties if it is missing,
    runs the offline static checks, analyses, tests, builds a debug APK, and
    optionally installs it on every attached device.

    Nothing here needs signing keys: a debug APK is signed with the standard
    Android debug key.

.EXAMPLE
    .\tools\build_apk.ps1
    .\tools\build_apk.ps1 -Install
    .\tools\build_apk.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Step($text) {
    Write-Host ""
    Write-Host ("=" * 64) -ForegroundColor Cyan
    Write-Host $text -ForegroundColor Cyan
    Write-Host ("=" * 64) -ForegroundColor Cyan
}

function Have($name) {
    $null -ne (Get-Command $name -ErrorAction SilentlyContinue)
}

# --- 1. toolchain ----------------------------------------------------------
Step "1/6  Checking the toolchain"

$missing = @()
if (-not (Have flutter)) { $missing += "Flutter 3.44.0  -> https://docs.flutter.dev/get-started/install/windows" }
if (-not (Have java))    { $missing += "JDK 17+ (a JRE is not enough) -> https://adoptium.net" }

# A JRE has java but no javac, and Gradle needs the compiler.
if ((Have java) -and -not (Have javac)) {
    $missing += "JDK 17+ : 'java' was found but 'javac' was not, so this is a JRE"
}

if ($missing.Count -gt 0) {
    Write-Host "Missing prerequisites:" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "No toolchain? Build in the cloud instead - push this repo to GitHub," -ForegroundColor Yellow
    Write-Host "open Actions -> 'Debug APK' -> 'Run workflow', and download the APK." -ForegroundColor Yellow
    exit 1
}
Write-Host "flutter, java and javac found." -ForegroundColor Green

# --- 2. local.properties ---------------------------------------------------
Step "2/6  Configuring android/local.properties"

$localProps = Join-Path $repo "android\local.properties"
if (Test-Path $localProps) {
    Write-Host "Already present, leaving it alone."
} else {
    $flutterExe = (Get-Command flutter).Source
    # <sdk>\bin\flutter.bat  ->  <sdk>
    $flutterRoot = Split-Path -Parent (Split-Path -Parent $flutterExe)

    $sdkDir = $env:ANDROID_SDK_ROOT
    if (-not $sdkDir) { $sdkDir = $env:ANDROID_HOME }
    if (-not $sdkDir) { $sdkDir = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

    if (-not (Test-Path $sdkDir)) {
        Write-Host "Android SDK not found. Set ANDROID_SDK_ROOT or install Android Studio." -ForegroundColor Red
        exit 1
    }

    # Gradle reads this file as Java properties, where backslash is an escape.
    $flutterEscaped = $flutterRoot -replace '\\', '\\\\'
    $sdkEscaped = $sdkDir -replace '\\', '\\\\'
    "flutter.sdk=$flutterEscaped`nsdk.dir=$sdkEscaped`n" |
        Set-Content -Path $localProps -Encoding utf8
    Write-Host "Wrote android\local.properties" -ForegroundColor Green
    Get-Content $localProps | ForEach-Object { Write-Host "  $_" }
}

# --- 3. static checks ------------------------------------------------------
Step "3/6  Offline static checks"
if (Have python) {
    python tools\static_checks\run_all.py
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Static checks reported findings (see above). Continuing." -ForegroundColor Yellow
    }
} else {
    Write-Host "Python not found, skipping. These checks are optional." -ForegroundColor Yellow
}

# --- 4. dependencies and analysis -----------------------------------------
Step "4/6  Dependencies and analysis"
flutter pub get

# android/gradlew and gradle-wrapper.jar are gitignored in this project, so a
# fresh clone has no wrapper. The Flutter tool injects it during configuration;
# without this, gradlew below fails with "command not found".
flutter build apk --config-only
if ($LASTEXITCODE -ne 0) {
    Write-Host "Gradle configuration failed - see the errors above." -ForegroundColor Red
    exit 1
}

flutter analyze --no-fatal-infos
if ($LASTEXITCODE -ne 0) {
    Write-Host "flutter analyze reported findings. Continuing to the build so you" -ForegroundColor Yellow
    Write-Host "can see compile errors too." -ForegroundColor Yellow
}

# --- 5. tests --------------------------------------------------------------
if ($SkipTests) {
    Step "5/6  Tests (skipped)"
} else {
    Step "5/6  Tests"
    flutter test
    if ($LASTEXITCODE -ne 0) { Write-Host "Dart tests failed." -ForegroundColor Yellow }

    if (Test-Path (Join-Path $repo "android\gradlew.bat")) {
        Push-Location android
        .\gradlew.bat testDebugUnitTest --no-daemon
        if ($LASTEXITCODE -ne 0) { Write-Host "Kotlin unit tests failed." -ForegroundColor Yellow }
        Pop-Location
    } else {
        Write-Host "android\gradlew.bat missing - skipping Kotlin tests." -ForegroundColor Yellow
    }
}

# --- 6. build --------------------------------------------------------------
Step "6/6  Building the debug APK"
flutter build apk --debug
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "BUILD FAILED. The compiler errors are above." -ForegroundColor Red
    exit 1
}

$apk = Join-Path $repo "build\app\outputs\flutter-apk\app-debug.apk"
Write-Host ""
Write-Host "APK: $apk" -ForegroundColor Green
if (Test-Path $apk) {
    $sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host "Size: $sizeMb MB" -ForegroundColor Green
}

if ($Install) {
    Step "Installing on attached devices"
    if (-not (Have adb)) {
        Write-Host "adb not on PATH; install manually." -ForegroundColor Yellow
    } else {
        $devices = adb devices | Select-Object -Skip 1 |
            Where-Object { $_ -match "\tdevice$" } |
            ForEach-Object { ($_ -split "\t")[0] }
        if (-not $devices) {
            Write-Host "No devices. Enable USB debugging and reconnect." -ForegroundColor Yellow
        }
        foreach ($d in $devices) {
            Write-Host "Installing on $d ..."
            adb -s $d install -r $apk
        }
    }
}

Write-Host ""
Write-Host "Next: install on BOTH phones, then follow QUICKSTART.md" -ForegroundColor Cyan
