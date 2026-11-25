#!/data/data/com.termux/files/usr/bin/bash
# usage: summarize.sh <results_dir>   -> one line per test case: "<class>#<name> <PASS|FAIL|ERROR|SKIP>" (sorted) on stdout
for f in "$1"/*.xml; do
  [ -f "$f" ] || continue
  awk '
    function emit(st) { print cls "#" nm " " st; open = 0 }
    /<testcase / {
      match($0, / name="[^"]*"/);       nm  = substr($0, RSTART + 7, RLENGTH - 8)
      match($0, /classname="[^"]*"/);   cls = substr($0, RSTART + 11, RLENGTH - 12)
      if ($0 ~ /\/>[ \t]*$/) { emit("PASS") } else { open = 1; st = "PASS" }
      next
    }
    open && /<failure/ { st = "FAIL" }
    open && /<error/   { st = "ERROR" }
    open && /<skipped/ { st = "SKIP" }
    open && /<\/testcase>/ { emit(st) }
  ' "$f"
done | sort
