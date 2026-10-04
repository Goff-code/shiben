#!/usr/bin/env python3
"""Verify this release's APK identity and public signature; not a device test."""
from pathlib import Path
import base64
import hashlib
import os
import re
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[2]
apk = Path(sys.argv[1] if len(sys.argv) > 1 else root / "shiben-release.apk").resolve()
sdk = Path(os.environ.get("ANDROID_HOME", root / "tools/android-sdk"))
bt = sdk / "build-tools/35.0.0"
checks = 0


def check(condition, description):
    global checks
    if not condition:
        raise AssertionError(description)
    checks += 1


def run(*args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT)


signature = run(bt / "apksigner", "verify", "--verbose", "--print-certs", apk)
badging = run(bt / "aapt", "dump", "badging", apk)
manifest = run(bt / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
run(bt / "zipalign", "-c", "-p", "4", apk)
check("Verified using v2 scheme (APK Signature Scheme v2): true" in signature, "valid APK v2 signature")
check("Verified using v3 scheme (APK Signature Scheme v3): true" in signature, "valid APK v3 signature")
check("Number of signers: 1" in signature, "one release signer")
cert = (root / "docs/SHIBEN_RELEASE_CERTIFICATE.pem").read_text()
der = base64.b64decode("".join(line for line in cert.splitlines() if not line.startswith("-----")))
fingerprint = hashlib.sha256(der).hexdigest()
check("Signer #1 certificate SHA-256 digest: " + fingerprint in signature, "expected pinned public release certificate")
check(fingerprint != "21448561864130135118013f194f94187e8ad887086351c395da230193996261", "separate from unavailable old signing identity")
check("package: name='cn.goff.shiben' versionCode='2' versionName='1.0.1'" in badging, "independent package and release version")
check("application-label:'时本'" in badging, "installed app label")
check("launchable-activity: name='cn.focusledger.MainActivity'" in badging, "launcher resolves the retained Java namespace")
check("sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging, "minimum and target SDK")
check("application-debuggable" not in badging and "android:debuggable" not in manifest, "not debuggable")
check("android.permission.INTERNET" not in manifest, "no network permission")
check(re.search(r"android:allowBackup[^\n]*=\(type 0x12\)0x0\b", manifest), "automatic backup disabled")
check(re.search(r"android:fullBackupContent[^\n]*=\(type 0x12\)0x0\b", manifest), "legacy full backup disabled")
check("cn.goff.shiben.test" not in manifest, "test package excluded")
with zipfile.ZipFile(apk) as z:
    check(not any(name.lower().endswith((".jks", ".keystore", ".p12", ".pem")) for name in z.namelist()), "no key or certificate files bundled")
    dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
    check(b"LedgerInstrumentation" not in dex, "test runner excluded")
    check("今天，你把注意力投向了哪里？".encode() in dex, "approved homepage copy in delivered bytecode")
    check("记录每一份注意力，投资你想成为的人。".encode() in dex, "approved intro in delivered bytecode")
    check("专注账本".encode() not in dex, "old visible brand absent in delivered bytecode")
print(f"PASS ({checks} checks): signed release identity, public certificate, launcher, brand bytecode, release/privacy flags, no test/private-key payload")
print("APK SHA256: " + hashlib.sha256(apk.read_bytes()).hexdigest())
print("Certificate SHA256: " + fingerprint)
