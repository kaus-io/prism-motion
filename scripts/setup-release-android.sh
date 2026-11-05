#!/usr/bin/env bash
# 使用 Runner SDK，避免旧 Node Action 参与签名发布。 / Uses the runner SDK without a legacy Node setup action.
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo '::error::The runner Android SDK directory is unavailable'
  exit 1
fi
SDK_MANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDK_MANAGER" ]]; then
  echo '::error::The runner sdkmanager executable is unavailable'
  exit 1
fi
TOOLS_JAVA="${JAVA_HOME_17_X64:-${JAVA_HOME:-}}"
if [[ -z "$TOOLS_JAVA" || ! -x "$TOOLS_JAVA/bin/java" ]]; then
  echo '::error::A Java runtime for Android command-line tools is unavailable'
  exit 1
fi
LOG="$(mktemp "${TMPDIR:-${RUNNER_TEMP:-/tmp}}/prism-sdk-setup.XXXXXX")"
report_failure() {
  python3 - "$LOG" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(errors='replace')[-3000:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::error::Android SDK setup failed: ' + text)
PY
}
if ! JAVA_HOME="$TOOLS_JAVA" "$SDK_MANAGER" --version > "$LOG" 2>&1; then
  report_failure
  exit 1
fi
if [[ "${1:-}" == '--check-only' ]]; then
  echo 'Runner sdkmanager executes successfully'
  exit 0
fi
set +e
set +o pipefail
yes | JAVA_HOME="$TOOLS_JAVA" "$SDK_MANAGER" --licenses >> "$LOG" 2>&1
LICENSE_STATUS="${PIPESTATUS[1]}"
set -e
set -o pipefail
if [[ "$LICENSE_STATUS" != 0 ]]; then
  report_failure
  exit "$LICENSE_STATUS"
fi
PLATFORM="$(python3 - "${BASH_SOURCE[0]}" <<'PY'
from pathlib import Path
import sys
import tomllib
catalog = Path(sys.argv[1]).resolve().parents[1] / 'gradle/libs.versions.toml'
api = str(tomllib.loads(catalog.read_text())['versions']['android-compileSdk'])
if api.isdigit() and int(api) >= 37:
    api += '.0'
print('platforms;android-' + api)
PY
)"
if ! JAVA_HOME="$TOOLS_JAVA" "$SDK_MANAGER" "$PLATFORM" 'cmake;3.22.1' >> "$LOG" 2>&1; then
  report_failure
  exit 1
fi
if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "ANDROID_HOME=$SDK_ROOT" >> "$GITHUB_ENV"
  echo "ANDROID_SDK_ROOT=$SDK_ROOT" >> "$GITHUB_ENV"
fi
echo 'Android SDK platform and native-build prerequisites are ready'
