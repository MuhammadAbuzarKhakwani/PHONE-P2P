#!/usr/bin/env bash
#
# One command to check, build and install the WDCable debug APK (Linux/macOS).
#
#   ./tools/build_apk.sh              build only
#   ./tools/build_apk.sh --install    build, then install on every attached device
#   ./tools/build_apk.sh --skip-tests
#
# A debug APK is signed with the standard Android debug key, so no signing
# configuration is needed. Debug builds are for testing only.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

DO_INSTALL=0
SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --install) DO_INSTALL=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

step() {
  echo
  echo "================================================================"
  echo "$1"
  echo "================================================================"
}

have() { command -v "$1" >/dev/null 2>&1; }

# --- 1. toolchain ----------------------------------------------------------
step "1/6  Checking the toolchain"
missing=()
have flutter || missing+=("Flutter 3.44.0 -> https://docs.flutter.dev/get-started/install")
have java || missing+=("JDK 17+ -> https://adoptium.net")
# A JRE has java but no javac, and Gradle needs the compiler.
if have java && ! have javac; then
  missing+=("JDK 17+ : found 'java' but not 'javac', so this is a JRE")
fi

if [ ${#missing[@]} -gt 0 ]; then
  echo "Missing prerequisites:"
  for m in "${missing[@]}"; do echo "  - $m"; done
  echo
  echo "No toolchain? Build in the cloud instead: push this repo to GitHub,"
  echo "open Actions -> 'Debug APK' -> 'Run workflow', download the APK."
  exit 1
fi
echo "flutter, java and javac found."

# --- 2. local.properties ---------------------------------------------------
step "2/6  Configuring android/local.properties"
if [ -f android/local.properties ]; then
  echo "Already present, leaving it alone."
else
  flutter_root="$(dirname "$(dirname "$(command -v flutter)")")"
  sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
  if [ ! -d "$sdk_dir" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    sdk_dir="$HOME/Library/Android/sdk"   # macOS default
  fi
  if [ ! -d "$sdk_dir" ]; then
    echo "Android SDK not found. Set ANDROID_SDK_ROOT or install Android Studio." >&2
    exit 1
  fi
  printf 'flutter.sdk=%s\nsdk.dir=%s\n' "$flutter_root" "$sdk_dir" > android/local.properties
  echo "Wrote android/local.properties:"
  sed 's/^/  /' android/local.properties
fi

# --- 3. static checks ------------------------------------------------------
step "3/6  Offline static checks"
if have python3; then
  python3 tools/static_checks/run_all.py || echo "Static checks reported findings. Continuing."
elif have python; then
  python tools/static_checks/run_all.py || echo "Static checks reported findings. Continuing."
else
  echo "Python not found, skipping. These checks are optional."
fi

# --- 4. dependencies and analysis -----------------------------------------
step "4/6  Dependencies and analysis"
flutter pub get || exit 1
flutter analyze --no-fatal-infos || \
  echo "flutter analyze reported findings. Continuing to the build so compile errors show too."

# --- 5. tests --------------------------------------------------------------
if [ "$SKIP_TESTS" -eq 1 ]; then
  step "5/6  Tests (skipped)"
else
  step "5/6  Tests"
  flutter test || echo "Dart tests failed."
  ( cd android && ./gradlew testDebugUnitTest --no-daemon ) || echo "Kotlin unit tests failed."
fi

# --- 6. build --------------------------------------------------------------
step "6/6  Building the debug APK"
if ! flutter build apk --debug; then
  echo
  echo "BUILD FAILED. The compiler errors are above."
  exit 1
fi

APK="$REPO/build/app/outputs/flutter-apk/app-debug.apk"
echo
echo "APK: $APK"
[ -f "$APK" ] && echo "Size: $(du -h "$APK" | cut -f1)"

if [ "$DO_INSTALL" -eq 1 ]; then
  step "Installing on attached devices"
  if ! have adb; then
    echo "adb not on PATH; install manually."
  else
    devices="$(adb devices | awk '/\tdevice$/ {print $1}')"
    if [ -z "$devices" ]; then
      echo "No devices. Enable USB debugging and reconnect."
    fi
    for d in $devices; do
      echo "Installing on $d ..."
      adb -s "$d" install -r "$APK"
    done
  fi
fi

echo
echo "Next: install on BOTH phones, then follow QUICKSTART.md"
