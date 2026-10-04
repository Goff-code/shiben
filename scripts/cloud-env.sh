#!/usr/bin/env bash
# Source this file for the isolated, ignored cloud build and emulator toolchain.
SHIBEN_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export HOME="$SHIBEN_ROOT/tools/home"
export XDG_CONFIG_HOME="$HOME/.config"
export XDG_RUNTIME_DIR="$HOME/.local/run"
export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_SDK_HOME="$HOME"
export ANDROID_HOME="$SHIBEN_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$SHIBEN_ROOT/tools/jdk-21.0.2"
export GRADLE_USER_HOME="$SHIBEN_ROOT/tools/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$HOME" "$XDG_CONFIG_HOME" "$XDG_RUNTIME_DIR" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"
# Gradle and sdkmanager use JVM proxy settings, not the shell's proxy variables.
# Do not inherit a stale proxy hostname from another execution workspace.
SHIBEN_PROXY_OPTS=""
if [[ -n "${https_proxy:-${HTTPS_PROXY:-}}" ]]; then
  SHIBEN_PROXY_OPTS=$(python3 - <<'PY'
import os, urllib.parse
p=urllib.parse.urlparse(os.environ.get('https_proxy') or os.environ.get('HTTPS_PROXY'))
if p.hostname and p.port:
    print(f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port} -Dhttp.nonProxyHosts=localhost|127.*')
PY
)
fi
export GRADLE_OPTS="$SHIBEN_PROXY_OPTS -Duser.home=$HOME"
export JAVA_TOOL_OPTIONS="$GRADLE_OPTS"
# The cloud JRE's trust store includes its network proxy certificate.
SHIBEN_SYSTEM_TRUST=/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts
if [[ -f "$SHIBEN_SYSTEM_TRUST" ]]; then
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=$SHIBEN_SYSTEM_TRUST"
fi
