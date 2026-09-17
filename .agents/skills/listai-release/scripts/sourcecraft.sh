#!/usr/bin/env bash
set -euo pipefail
umask 077
listai_src="${LISTAI_SOURCECRAFT_CLI:-$HOME/.local/bin/src}"
if [[ ! -x "$listai_src" ]]; then
  printf 'SourceCraft CLI is missing: %s\n' "$listai_src" >&2
  exit 1
fi
export XDG_CONFIG_HOME="${LISTAI_SOURCECRAFT_CONFIG_HOME:-$HOME/.config}"
exec "$listai_src" "$@"
