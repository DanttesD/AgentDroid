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

# 3. apt inside the app must use our repository (sources.list is hard-coded in packages/apt/build.sh).
apt_build="$tp/packages/apt/build.sh"
sed -i "s|deb https://packages-cf.termux.dev/apt/termux-main/ stable main|deb ${AGENTDROID_REPO_URL}/main/ stable main|" "$apt_build"
sed -i '/# The main termux repository, without cloudflare cache/d; /# deb https:\/\/packages.termux.dev\/apt\/termux-main\/ stable main/d' "$apt_build"
sed -i 's|# The main termux repository, with cloudflare cache|# AgentDroid repository|' "$apt_build"
grep -q "deb ${AGENTDROID_REPO_URL}/main/ stable main" "$apt_build" \
    || { echo "failed to patch sources.list in packages/apt/build.sh (upstream changed?)" >&2; exit 1; }

# 4. termux-keyring installs only our signing key. The official .gpg files stay in the package
#    directory because build-package.sh imports some of them for its own tooling.
kr="$tp/packages/termux-keyring"
install -m 644 "$here/agentdroid-apt.gpg" "$kr/agentdroid-apt.gpg"
cat > "$kr/build.sh" <<'KEYRING'
TERMUX_PKG_HOMEPAGE=https://github.com/DanttesD/AgentDroid
TERMUX_PKG_DESCRIPTION="GPG public key of the AgentDroid package repository"
TERMUX_PKG_LICENSE="Apache-2.0"
TERMUX_PKG_MAINTAINER="@DanttesD"
TERMUX_PKG_VERSION=1.0
TERMUX_PKG_AUTO_UPDATE=false
TERMUX_PKG_SKIP_SRC_EXTRACT=true
TERMUX_PKG_PLATFORM_INDEPENDENT=true
TERMUX_PKG_ESSENTIAL=true

termux_step_make_install() {
	local GPG_SHARE_DIR="$TERMUX_PREFIX/share/termux-keyring"
	rm -rf "$GPG_SHARE_DIR"
	mkdir -p "$GPG_SHARE_DIR"
	install -Dm600 "$TERMUX_PKG_BUILDER_DIR/agentdroid-apt.gpg" "$GPG_SHARE_DIR"

	local GPG_DIR="$TERMUX_PREFIX/etc/apt/trusted.gpg.d"
	mkdir -p "$GPG_DIR"
	find "$GPG_DIR" -xtype l -delete
	ln -sf "$GPG_SHARE_DIR/agentdroid-apt.gpg" "$GPG_DIR/agentdroid-apt.gpg"
}
KEYRING

echo "configured $tp for $AGENTDROID_PACKAGE_NAME, repo $AGENTDROID_REPO_URL/main"
