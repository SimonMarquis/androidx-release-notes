#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Compile the production definitions and fixture checks together without invoking the CLI.
script=$(mktemp --suffix=.main.kts)
trap 'rm -f "$script"' EXIT
sed '$d' scripts/androidx-release-notes.main.kts > "$script"
cat scripts/drift.test.kts >> "$script"
kotlin "$script"
