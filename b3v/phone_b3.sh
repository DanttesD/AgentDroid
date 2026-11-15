#!/data/data/com.termux/files/usr/bin/bash
# B3 verification on the phone, isolated in /data/local/tmp/agentdroid-verify (nothing of Termux Job's is touched).
#   usage: phone_b3.sh <stage...>      stages: setup | compile | shape | tests | all
#   env:   ROBO=<version>  (Robolectric, default 4.11.1)    TESTS=<gradle test filter, optional>
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
B=$W/b3
SRC=$W/src
OUT=$B/out
mkdir -p "$B/agp" "$OUT" "$B/test-home" "$B/m2"
log() { echo "[$(date +%H:%M:%S)] $*"; }
free_gb() { df -k /data | awk 'NR==2{printf "%d", $4/1048576}'; }

gr() {   # gr <logfile> <gradle args...>
    local lf=$1; shift
    [ "$(free_gb)" -lt 4 ] && { log "ABORT: less than 4 GB free"; return 9; }
    ( cd "$B/agp" && $W/gradle-7.6.4/bin/gradle --no-daemon --console=plain --max-workers=2 --stacktrace \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Pandroid.aapt2FromMavenOverride=$W/aapt2/aapt2 \
        -ProboVersion="${ROBO:-4.11.1}" -PtraceDir="$OUT" \
        "$@" ) > "$lf" 2>&1
    local rc=$?
    log "gradle rc=$rc  ($lf; free $(free_gb) GB)"
    return $rc
}

stage_setup() {
    cp "$SRC/b3v/agp/settings.gradle" "$SRC/b3v/agp/build.gradle" "$B/agp/"
    cp "$W/b2/agp/gradle.properties" "$B/agp/gradle.properties"
    log "setup done: $(ls $B/agp | tr '\n' ' ')"
}

stage_compile() {
    log "compile (assembleDebug of the Kotlin port and of the Java original)"
    gr "$OUT/compile.log" :terminal-view:assembleDebug :tv-java:assembleDebug
    local rc=$?
    grep -E "^e: |warning:|^w: |error:|BUILD|FAILED|What went wrong" "$OUT/compile.log" | head -40
    return $rc
}

stage_shape() {
    local JP=$W/jdk17/bin/javap
    local KT=$SRC/terminal-view/build/intermediates/aar_main_jar/debug/classes.jar
    local JV=$SRC/b3v/tv-java/build/intermediates/aar_main_jar/debug/classes.jar
    ls -l "$KT" "$JV"
    for v in kt:$KT java:$JV; do
        local n=${v%%:*} j=${v#*:}
        : > "$OUT/javap_$n.txt"
        ( cd $TMPDIR && unzip -Z1 "$j" | grep '\.class$' | sed 's/\.class$//; s|/|.|g' | sort ) > "$OUT/classes_$n.txt"
        $JP -p -cp "$j:$SRC/terminal-emulator/build/intermediates/aar_main_jar/debug/classes.jar" $(cat "$OUT/classes_$n.txt" | tr '\n' ' ') > "$OUT/javap_$n.txt" 2>&1
    done
    log "classes kt=$(wc -l < $OUT/classes_kt.txt) java=$(wc -l < $OUT/classes_java.txt)"
    $JP -p -cp "$KT" com.termux.view.TerminalViewClient | head -40
}

stage_tests() {
    log "tests (Robolectric ${ROBO:-4.11.1}) for both variants"
    rm -rf "$SRC/terminal-view/build/test-results" "$SRC/b3v/tv-java/build/test-results"
    gr "$OUT/tests.log" --continue :terminal-view:testDebugUnitTest :tv-java:testDebugUnitTest ${TESTS:+--tests "$TESTS"}
    local rc=$?
    for v in terminal-view:kt b3v/tv-java:java; do
        local d=${v%%:*} n=${v#*:}
        rm -rf "$OUT/results_$n"; mkdir -p "$OUT/results_$n"
        cp "$SRC/$d/build/test-results/testDebugUnitTest/"*.xml "$OUT/results_$n/" 2>/dev/null
    done
    grep -E "PASSED|FAILED|SKIPPED|BUILD|What went wrong" "$OUT/tests.log" | head -80
    return $rc
}

for s in "$@"; do
    case "$s" in
        setup) stage_setup ;;
        compile) stage_compile ;;
        shape) stage_shape ;;
        tests) stage_tests ;;
        all) stage_setup; stage_compile && stage_shape; stage_tests ;;
        *) echo "unknown stage $s" ;;
    esac
done
log "phone_b3 finished: $*"
touch "$OUT/done_${*// /_}"
