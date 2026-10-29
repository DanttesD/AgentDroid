#!/usr/bin/env bash
# Builds a signed flat-pool apt repository for our Termux packages.
# Usage: make-apt-repo.sh <debs-dir> <out-dir>
# Env:   GPG_PRIVATE_KEY (armored, required), AGENTDROID_APT_KEY_FPR (from fork.env)
# Layout (matches the sources.list line "deb <REPO_URL>/main/ stable main"):
#   <out>/main/pool/main/*.deb
#   <out>/main/dists/stable/main/binary-aarch64/Packages(.gz)
#   <out>/main/dists/stable/{Release,InRelease,Release.gpg}
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fork.env
. "$here/fork.env"

debs="${1:?usage: $0 <debs-dir> <out-dir>}"
out="${2:?usage: $0 <debs-dir> <out-dir>}"
: "${GPG_PRIVATE_KEY:?GPG_PRIVATE_KEY is not set}"

arch=aarch64
root="$out/main"
pool="$root/pool/main"
dist="$root/dists/stable"
bin="$dist/main/binary-$arch"

mkdir -p "$pool" "$bin"
n=$(find "$debs" -maxdepth 1 -name '*.deb' | wc -l)
[ "$n" -gt 0 ] || { echo "no .deb files in $debs" >&2; exit 1; }
cp -f "$debs"/*.deb "$pool"/

( cd "$root" && dpkg-scanpackages --multiversion pool/main /dev/null > "dists/stable/main/binary-$arch/Packages" )
gzip -9 -k -f "$bin/Packages"
# apt also asks for architecture-independent indexes
mkdir -p "$dist/main/binary-all"
: > "$dist/main/binary-all/Packages"; gzip -9 -k -f "$dist/main/binary-all/Packages"

checksums() { # <algo-cmd> <name>
    echo "$2:"
    ( cd "$dist" && find main -type f \( -name Packages -o -name 'Packages.gz' \) | sort | while read -r f; do
        printf ' %s %16d %s\n' "$($1 "$f" | cut -d' ' -f1)" "$(stat -c %s "$f")" "$f"
    done )
}
{
    echo "Origin: AgentDroid"
    echo "Label: AgentDroid"
    echo "Suite: stable"
    echo "Codename: stable"
    echo "Architectures: $arch all"
    echo "Components: main"
    echo "Date: $(LC_ALL=C date -Ru)"
    checksums md5sum MD5Sum
    checksums sha256sum SHA256
} > "$dist/Release"

export GNUPGHOME; GNUPGHOME="$(mktemp -d)"; chmod 700 "$GNUPGHOME"
trap 'rm -rf "$GNUPGHOME"' EXIT
printf '%s\n' "$GPG_PRIVATE_KEY" | gpg --batch --quiet --import
key="${AGENTDROID_APT_KEY_FPR:?}"
gpg --batch --yes --pinentry-mode loopback --passphrase '' -u "$key" --clearsign -o "$dist/InRelease" "$dist/Release"
gpg --batch --yes --pinentry-mode loopback --passphrase '' -u "$key" -abs -o "$dist/Release.gpg" "$dist/Release"
cp -f "$here/agentdroid-apt.gpg" "$root/agentdroid-apt.gpg"
echo "repository written to $root with $n package files"
