#!/usr/bin/env bash
# Run after assembling the requested variants; CI starts from a clean checkout.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "$#" -eq 0 ]]; then
  set -- release
fi

if grep -Eq '^[[:space:]]*android\.(enableR8\.fullMode|r8\.optimizedResourceShrinking)[[:space:]]*[:=][[:space:]]*false([[:space:]]|$)' \
    gradle.properties; then
  echo "R8 full mode and optimized resource shrinking must remain enabled." >&2
  exit 1
fi

for variant in "$@"; do
  reports="app/build/outputs/mapping/$variant"
  for report in mapping.txt configuration.txt resources.txt; do
    if [[ ! -s "$reports/$report" ]]; then
      echo "Missing R8/code/resource optimization report: $reports/$report" >&2
      exit 1
    fi
  done
  if ! grep -q '^# compiler: R8$' "$reports/mapping.txt" ||
     ! grep -q 'proguard-android-optimize.txt' "$reports/configuration.txt"; then
    echo "$variant must use R8 with the Android optimization defaults." >&2
    exit 1
  fi
  if grep -Eq -- '^[[:space:]]*-(dontoptimize|dontshrink|dontobfuscate|ignorewarnings)([[:space:]]|$)' \
      "$reports/configuration.txt"; then
    echo "$variant disables optimization or suppresses all R8 warnings." >&2
    exit 1
  fi
  echo "$variant: R8 code and resource optimization reports verified."
done
