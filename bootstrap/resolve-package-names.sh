#!/usr/bin/env bash
# Maps package names to the termux-packages directory that builds them.
# Some packages are subpackages of another build script (bzip2 -> libbz2, curl -> libcurl);
# build-package.sh only accepts the parent. Reads names from stdin (one per line, # comments
# allowed), prints unique build names. Usage: resolve-package-names.sh <termux-packages dir> < list
set -euo pipefail
tp="${1:?usage: $0 <termux-packages dir>}"
declare -A seen=()
while read -r name _; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    if [ -f "$tp/packages/$name/build.sh" ]; then
        build="$name"
    else
        sub=$(compgen -G "$tp/packages/*/$name.subpackage.sh" | head -n1 || true)
        [ -n "$sub" ] || { echo "unknown package: $name" >&2; exit 1; }
        build="$(basename "$(dirname "$sub")")"
    fi
    if [ -z "${seen[$build]:-}" ]; then seen[$build]=1; echo "$build"; fi
done
