#!/usr/bin/env bash
# Prepares a checkout of termux/termux-packages for building under our package name.
# Usage: configure-termux-packages.sh <path-to-termux-packages>
# Changes only variables that upstream documents as safe to modify when forking
# (TERMUX_APP__PACKAGE_NAME and the repository urls in repo.json).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fork.env
. "$here/fork.env"

tp="${1:?usage: $0 <termux-packages dir>}"
props="$tp/scripts/properties.sh"
repo="$tp/repo.json"
[ -f "$props" ] && [ -f "$repo" ] || { echo "not a termux-packages checkout: $tp" >&2; exit 1; }

if ! [[ "$AGENTDROID_PACKAGE_NAME" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then
    echo "bad package name: $AGENTDROID_PACKAGE_NAME" >&2; exit 1
fi

# 1. Application package name (and, through it, data dir, rootfs and PREFIX).
sed -i "s|^TERMUX_APP__PACKAGE_NAME=\"com.termux\"|TERMUX_APP__PACKAGE_NAME=\"$AGENTDROID_PACKAGE_NAME\"|" "$props"
grep -q "^TERMUX_APP__PACKAGE_NAME=\"$AGENTDROID_PACKAGE_NAME\"" "$props" \
    || { echo "failed to patch TERMUX_APP__PACKAGE_NAME (upstream changed?)" >&2; exit 1; }

# 2. Our apt repository replaces the official ones, so that `pkg install` inside the app
#    downloads packages built for our prefix, never the official com.termux ones.
tmp="$(mktemp)"
jq --arg base "$AGENTDROID_REPO_URL" '
  del(.["root-packages"], .["x11-packages"])
  | .packages.url = ($base + "/main")
' "$repo" > "$tmp" && mv "$tmp" "$repo"

echo "configured $tp for $AGENTDROID_PACKAGE_NAME, repo $AGENTDROID_REPO_URL/main"
