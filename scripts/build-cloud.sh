#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export HOME="$PWD/tools/home"
export XDG_CONFIG_HOME="$HOME/.config"
export XDG_RUNTIME_DIR="$HOME/.local/run"
export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_SDK_HOME="$HOME"
export ANDROID_HOME="$PWD/tools/android-sdk"
export JAVA_HOME="$PWD/tools/jdk-21.0.2"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/tools/gradle-home"
if [[ -n "${https_proxy:-}" ]]; then
  export GRADLE_OPTS="$(python3 - <<'PY'
import os, urllib.parse
p=urllib.parse.urlparse(os.environ['https_proxy'])
print(f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port}')
PY
)"
fi
export JAVA_TOOL_OPTIONS="${GRADLE_OPTS:-} -Duser.home=$HOME -Djavax.net.ssl.trustStore=/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts"
exec "$PWD/tools/gradle-8.11.1/bin/gradle" --no-daemon "$@"
