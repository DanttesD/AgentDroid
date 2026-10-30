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

# 5. build-bootstraps.sh asks build-package.sh for "bzip2", which is only a subpackage of
#    libbz2 at the pinned commit ("No package bzip2 found"). Resolve subpackages to their parent.
bb="$tp/scripts/build-bootstraps.sh"
if ! grep -q 'AGENTDROID: resolve subpackages' "$bb"; then
    python3 - "$bb" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
needle = '\tlocal package_name="$2"\n'
assert s.count(needle) == 1, "build_package() layout changed upstream"
patch = needle + """\t# AGENTDROID: resolve subpackages (e.g. bzip2 -> libbz2) to the package that builds them
\tif [ ! -f "$TERMUX_PACKAGES_DIRECTORY/packages/$package_name/build.sh" ]; then
\t\tlocal parent_dir
\t\tparent_dir=$(compgen -G "$TERMUX_PACKAGES_DIRECTORY/packages/*/$package_name.subpackage.sh" | head -n1 || true)
\t\tif [ -n "$parent_dir" ]; then package_name="$(basename "$(dirname "$parent_dir")")"; fi
\tfi
"""
open(p, "w").write(s.replace(needle, patch))
PY
fi

# 6. command-not-found builds a database by downloading Contents-aarch64.gz from the package
#    repository, which does not exist yet for our (new) repository: the build fails with a 404.
#    It is only an optional convenience ("Recommends" of bash), so it is left out of the bootstrap.
if grep -q '^\s*PACKAGES+=("command-not-found")' "$bb"; then
    sed -i 's|^\(\s*\)PACKAGES+=("command-not-found")|\1: # AGENTDROID: command-not-found skipped (needs a package index that does not exist yet)|' "$bb"
fi
if grep -q 'PACKAGES+=("command-not-found")' "$bb"; then
    echo "failed to drop command-not-found from build-bootstraps.sh (upstream changed?)" >&2; exit 1
fi

# 7. The final `mv` of the zip into the repository root fails with "Permission denied" inside the
#    build container (the CI checkout is not writable for the container user), after the archive was
#    built successfully. output/ is writable (the .deb files are written there), so put the zip there.
if grep -q 'mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_PACKAGES_DIRECTORY/"' "$bb"; then
    sed -i 's|mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_PACKAGES_DIRECTORY/"|mkdir -p "$TERMUX_PACKAGES_DIRECTORY/output" \&\& mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_PACKAGES_DIRECTORY/output/" # AGENTDROID: output dir is writable|' "$bb"
fi
grep -q 'AGENTDROID: output dir is writable' "$bb" \
    || { echo "failed to patch the bootstrap zip destination (upstream changed?)" >&2; exit 1; }

# 8. Several packages (termux-tools, termux-exec, openssh, ...) are autotools projects that read the
#    app package / prefix from the *environment* (TERMUX_APP_PACKAGE, TERMUX_PREFIX, ...) and fall back
#    to "com.termux" when they are not exported. properties.sh only sets them as shell variables, so
#    the scripts (chsh, login, pkg, termux-reset, ...) ended up with /data/data/com.termux/...
if ! grep -q 'AGENTDROID: export' "$props"; then
    cat >> "$props" <<'EXPORTS'

# AGENTDROID: export the paths so that configure scripts of packages pick up our package name
export TERMUX_APP_PACKAGE TERMUX_BASE_DIR TERMUX_CACHE_DIR TERMUX_PREFIX TERMUX_ANDROID_HOME
EXPORTS
fi

echo "configured $tp for $AGENTDROID_PACKAGE_NAME, repo $AGENTDROID_REPO_URL/main"
