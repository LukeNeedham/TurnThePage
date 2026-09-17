#!/usr/bin/env python3
"""Build and verify Listai releases using the existing permanent signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

CERT_SHA256 = "3a3154d0b98e97a93182f399539d03a96701c8319ec4d88833c0c23a090e81a8"


def run(args, env):
    return subprocess.run([str(a) for a in args], env=env, check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Overrides: JAVA_HOME, ANDROID_HOME/ANDROID_SDK_ROOT, LISTAI_BUILD_TOOLS, "
        "LISTAI_RELEASE_STORE_FILE, LISTAI_RELEASE_PASSWORD_FILE, LISTAI_RELEASE_KEY_ALIAS. "
        "Existing LISTAI_RELEASE_STORE_PASSWORD/KEY_PASSWORD are accepted via environment."))
    parser.add_argument("action", choices=["check", "build", "verify"])
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--apk", type=Path, help="APK to verify; default: app/build/outputs/apk/release/app-release.apk")
    args = parser.parse_args()
    if args.apk is not None and args.action != "verify":
        parser.error("--apk is only supported with verify")
    repo = args.repo.resolve()
    props = dict(re.findall(r"^(listai\.version(?:Name|Code))\s*=\s*(\S+)\s*$",
                            (repo / "gradle.properties").read_text(), re.M))
    version, code = props["listai.versionName"], int(props["listai.versionCode"])
    if not re.fullmatch(r"[0-9][A-Za-z0-9._-]*", version) or code <= 0:
        raise ValueError("Invalid release version or versionCode")
    env = os.environ.copy()
    java = Path(env.get("JAVA_HOME", str(Path.home() / ".local/share/jdks/travel-21")))
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
    build_tools = Path(env.get("LISTAI_BUILD_TOOLS", str(sdk / "build-tools/36.0.0")))
    env.update(JAVA_HOME=str(java), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    for tool in [java / "bin/keytool", build_tools / "aapt2", build_tools / "apksigner", build_tools / "zipalign"]:
        if not tool.is_file():
            raise ValueError(f"Required tool is missing: {tool}")
    key_dir = Path.home() / ".local/share/listai/signing"
    key = Path(env.get("LISTAI_RELEASE_STORE_FILE", str(key_dir / "listai-release.p12"))).resolve()
    alias = env.get("LISTAI_RELEASE_KEY_ALIAS", "listai-release")
    if args.action != "verify":
        password = env.get("LISTAI_RELEASE_STORE_PASSWORD")
        if password is None:
            password = Path(env.get("LISTAI_RELEASE_PASSWORD_FILE", str(key_dir / "keystore-password.txt"))).read_text().rstrip("\r\n")
        if not password or not key.is_file():
            raise ValueError("Permanent release key or password missing; do not generate a replacement")
        env.update(LISTAI_RELEASE_STORE_FILE=str(key), LISTAI_RELEASE_STORE_PASSWORD=password,
                   LISTAI_RELEASE_KEY_ALIAS=alias,
                   LISTAI_RELEASE_KEY_PASSWORD=env.get("LISTAI_RELEASE_KEY_PASSWORD", password))
        cert = run([java / "bin/keytool", "-exportcert", "-keystore", key,
                    "-storepass:env", "LISTAI_RELEASE_STORE_PASSWORD", "-alias", alias], env).stdout
        if hashlib.sha256(cert).hexdigest() != CERT_SHA256:
            raise ValueError("Keystore certificate differs from the published Listai signing identity")
    if args.action == "check":
        print(json.dumps({"version": version, "versionCode": code, "keystore": str(key),
                          "alias": alias, "certificateSha256": CERT_SHA256}, ensure_ascii=False, indent=2))
        return
    if args.action == "build":
        subprocess.run([str(repo / "gradlew"), "--console=plain", "--no-configuration-cache",
                        ":app:assembleRelease"], cwd=repo, env=env, check=True)
    apk = (args.apk or repo / "app/build/outputs/apk/release/app-release.apk").resolve()
    metadata = run([build_tools / "aapt2", "dump", "badging", apk], env).stdout.decode()
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata, re.M)
    if not match or match.groups() != ("com.avstaim.listai", str(code), version):
        raise ValueError("APK package/version/versionCode differ from the current project")
    if "application-debuggable" in metadata:
        raise ValueError("APK is debuggable; refusing to package it as a release")
    signature = run([build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk], env).stdout.decode()
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if [s.lower() for s in certificates] != [CERT_SHA256]:
        raise ValueError("APK is not signed with the permanent Listai key")
    run([build_tools / "zipalign", "-c", "-P", "16", "4", apk], env)
    if args.action == "build":
        output = repo / "out/release" / f"listai-{version}.apk"
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, output)
        apk = output
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if args.action == "build":
        apk.with_suffix(".apk.sha256").write_text(f"{digest}  {apk.name}\n")
    print(json.dumps({"apk": str(apk), "version": version, "versionCode": code,
                      "sha256": digest, "certificateSha256": CERT_SHA256}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        sys.exit(str(error))
    except subprocess.CalledProcessError as error:
        # Never echo process arguments, environment or signing-tool error details.
        sys.exit(f"Release command failed with exit code {error.returncode}; no artifact published.")
