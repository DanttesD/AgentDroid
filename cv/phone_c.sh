#!/data/data/com.termux/files/usr/bin/bash
# Stage C verification on the phone, isolated in /data/local/tmp/agentdroid-verify (nothing of Termux Job's is touched).
# The REAL project tree ($W/src) is built, the same way as in the first verification runs (AGENTDROID_SKIP_X11=1,
# -x :app:downloadBootstraps): the real AGP, the real SDK platform, the real androidx jars.
#   usage: phone_c.sh <stage> <label>
#     setup          copy the pack (this script, init.gradle, summarize.sh) from $W/src/cv to $W/c (survives checkouts)
#     build <label>  :app:compileDebugJavaWithJavac (+ Kotlin of the port) and :app:compileDebugUnitTestJavaWithJavac
#     tests <label>  :app:testDebugUnitTest --continue; XML results are copied to $W/c/out/results_<label>
#     sum   <label>  per-test-case list $W/c/out/summary_<label>.txt and the counts
#     clean          delete app/build (so that the next variant is compiled from scratch)
#   env: ROBO=<Robolectric version>, TESTS=<gradle --tests filter>
set -u
W=/data/local/tmp/agentdroid-verify
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
unset LD_LIBRARY_PATH
export HOME=$W/home TMPDIR=$W/tmp
export JAVA_HOME=$W/jdk17
export ANDROID_HOME=$W/sdk ANDROID_SDK_ROOT=$W/sdk
export GRADLE_USER_HOME=$W/gradle-home
export AGENTDROID_SKIP_X11=1
export TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
C=$W/c
SRC=$W/src
OUT=$C/out
mkdir -p "$C" "$OUT" "$W/b3/test-home" "$W/b3/m2"
log() { echo "[$(date +%H:%M:%S)] $*"; }
free_gb() { df -k /data | awk 'NR==2{printf "%d", $4/1048576}'; }

gr() {   # gr <logfile> <gradle args...>
    local lf=$1; shift
    [ "$(free_gb)" -lt 3 ] && { log "ABORT: less than 3 GB free"; return 9; }
    ( cd "$SRC" && $W/gradle-7.6.4/bin/gradle --no-daemon --console=plain --max-workers=2 --stacktrace \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Pandroid.aapt2FromMavenOverride=$W/aapt2/aapt2 \
        -I "$C/init.gradle" -Pcv.robo="${ROBO:-4.11.1}" -Pcv.m2="$W/b3/m2" -Pcv.home="$W/b3/test-home" \
        -x :app:downloadBootstraps \
        "$@" ) > "$lf" 2>&1
    local rc=$?
    log "gradle rc=$rc  ($lf; free $(free_gb) GB)"
    return $rc
}

stage_setup() {
    cp "$SRC/cv/phone_c.sh" "$SRC/cv/init.gradle" "$SRC/cv/summarize.sh" "$C/"
    chmod +x "$C/phone_c.sh" "$C/summarize.sh"
    log "setup done: $(ls $C | tr '\n' ' ')"
}

stage_build() {
    local L=$1
    log "build [$L] HEAD: $(cd $SRC && git log --oneline -1)"
    gr "$OUT/build_$L.log" :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac
    local rc=$?
    grep -E "^e: |error:|BUILD|FAILED|What went wrong|OutOfMemory" "$OUT/build_$L.log" | sed "s#$SRC/##" | cut -c1-300 | head -80
    echo "kotlin warnings: $(grep -cE '^w: ' "$OUT/build_$L.log")"
    return $rc
}

stage_tests() {
    local L=$1
    log "tests [$L] (Robolectric ${ROBO:-4.11.1})"
    rm -rf "$SRC/app/build/test-results"
    gr "$OUT/tests_$L.log" --continue :app:testDebugUnitTest ${TESTS:+--tests "$TESTS"}
    local rc=$?
    rm -rf "$OUT/results_$L"; mkdir -p "$OUT/results_$L"
    cp "$SRC/app/build/test-results/testDebugUnitTest/"*.xml "$OUT/results_$L/" 2>/dev/null
    log "xml files: $(ls $OUT/results_$L | wc -l)"
    grep -E "BUILD|What went wrong|OutOfMemory" "$OUT/tests_$L.log" | head -10
    return $rc
}

stage_sum() {
    local L=$1
    "$C/summarize.sh" "$OUT/results_$L" > "$OUT/summary_$L.txt"
    log "summary [$L]: total=$(wc -l < $OUT/summary_$L.txt) PASS=$(grep -c ' PASS$' $OUT/summary_$L.txt) FAIL=$(grep -c ' FAIL$' $OUT/summary_$L.txt) ERROR=$(grep -c ' ERROR$' $OUT/summary_$L.txt) SKIP=$(grep -c ' SKIP$' $OUT/summary_$L.txt)"
}

case "${1:-}" in
    setup) stage_setup ;;
    build) stage_build "${2:?label}" ;;
    tests) stage_tests "${2:?label}" ;;
    sum)   stage_sum "${2:?label}" ;;
    clean) rm -rf "$SRC/app/build"; log "app/build removed (free $(free_gb) GB)" ;;
    *) echo "unknown stage ${1:-}" ;;
esac
log "phone_c finished: $*"
touch "$OUT/done_${*// /_}"
