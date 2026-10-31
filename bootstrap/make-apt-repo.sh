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

# Cached builds can leave several versions of one package in the pool (for example an old
# termux-keyring 1.0 next to 3.14); the old one confuses apt dependency solving. Keep the newest only.
declare -A newest_ver newest_file
for f in "$pool"/*.deb; do
    name=$(dpkg-deb -f "$f" Package); ver=$(dpkg-deb -f "$f" Version)
    if [ -z "${newest_ver[$name]:-}" ] || dpkg --compare-versions "$ver" gt "${newest_ver[$name]}"; then
        [ -n "${newest_file[$name]:-}" ] && rm -f "${newest_file[$name]}"
        newest_ver[$name]=$ver; newest_file[$name]=$f
    else
        rm -f "$f"
    fi
done

# dpkg-perl depends on clang upstream; we ship no clang, so apt could not install it. Drop that dependency.
for f in "$pool"/dpkg-perl_*.deb; do
    [ -e "$f" ] || continue
    if dpkg-deb -f "$f" Depends | grep -qw clang; then
        tmp=$(mktemp -d)
        dpkg-deb -R "$f" "$tmp"
        sed -i -E '/^Depends:/ { s/(^|, )clang(, |$)/\1/; s/, ,/,/; s/, *$//; s/^Depends: *,? */Depends: / }' "$tmp/DEBIAN/control"
        # new version number so that devices which already have the broken -5 upgrade to the fixed package
        sed -i -E '0,/^Version: / s/^(Version: .*)$/\1+ad2/' "$tmp/DEBIAN/control"
        # its postinst builds Locale::gettext through CPAN, which needs a compiler we do not ship: drop it
        rm -f "$tmp/DEBIAN/postinst"
        # dpkg-deb -b insists on sane modes (Termux scripts are 0700, the temp dir 0700)
        chmod 0755 "$tmp" "$tmp/DEBIAN"
        for sc in preinst postinst prerm postrm config; do [ -f "$tmp/DEBIAN/$sc" ] && chmod 0755 "$tmp/DEBIAN/$sc"; done
        if dpkg-deb -b "$tmp" "$f.new" >/dev/null && mv -f "$f.new" "$f"; then
            echo "dpkg-perl: dropped the clang dependency and the CPAN postinst"
        else
            rm -f "$f.new"; echo "WARNING: could not repack $f" >&2
        fi
        rm -rf "$tmp"
    fi
done
n=$(find "$pool" -maxdepth 1 -name '*.deb' | wc -l)

( cd "$root" && dpkg-scanpackages pool/main /dev/null > "dists/stable/main/binary-$arch/Packages" )
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
