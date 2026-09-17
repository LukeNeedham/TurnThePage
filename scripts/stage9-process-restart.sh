#!/usr/bin/env bash
# Run after installing app-debug.apk and app-debug-androidTest.apk on a QA emulator.
set -euo pipefail

serial="${1:?Usage: bash scripts/stage9-process-restart.sh SERIAL [OUTPUT_DIR]}"
artifact_dir="${2:-$(mktemp -d /tmp/listai-process-restart-XXXXXX)}"
adb_bin="${ADB:-adb}"
package=com.avstaim.listai
runner=com.avstaim.listai.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$artifact_dir"

run_phase() {
    local phase="$1"
    "$adb_bin" -s "$serial" shell am instrument -w -r \
        -e class "com.avstaim.listai.Stage9PersistenceTest#$phase" \
        -e stage9ProcessRestart true "$runner" | tee "$artifact_dir/$phase.txt"
    # am instrument can exit zero even if JUnit reports a failure.
    if ! grep -Eq '^OK \(1 test\)' "$artifact_dir/$phase.txt"; then
        printf 'Process-restart phase failed: %s\n' "$phase" >&2
        exit 1
    fi
}

run_phase prepareProcessRestart
"$adb_bin" -s "$serial" shell am force-stop "$package"
run_phase verifyAfterProcessRestart
