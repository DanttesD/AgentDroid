#!/data/data/com.termux/files/usr/bin/bash
# B4 verification on the phone, isolated in /data/local/tmp/agentdroid-verify (nothing of Termux Job's is touched).
#   usage: phone_b4.sh <stage...>      stages: setup | compile | shape | all
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
B=$W/b4
SRC=$W/src
OUT=$B/out
mkdir -p "$B/agp" "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*"; }
free_gb() { df -k /data | awk 'NR==2{printf "%d", $4/1048576}'; }

gr() {   # gr <logfile> <gradle args...>
    local lf=$1; shift
    [ "$(free_gb)" -lt 4 ] && { log "ABORT: less than 4 GB free"; return 9; }
    ( cd "$B/agp" && $W/gradle-7.6.4/bin/gradle --no-daemon --console=plain --max-workers=2 --stacktrace \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Pandroid.aapt2FromMavenOverride=$W/aapt2/aapt2 \
        "$@" ) > "$lf" 2>&1
    local rc=$?
    log "gradle rc=$rc  ($lf; free $(free_gb) GB)"
    return $rc
}

stage_setup() {
    cp "$SRC/b4v/agp/settings.gradle" "$SRC/b4v/agp/build.gradle" "$B/agp/"
    cp "$W/b2/agp/gradle.properties" "$B/agp/gradle.properties"
    log "setup done: $(ls $B/agp | tr '\n' ' ')"
}

stage_compile() {
    log "compile (assembleDebug of the Kotlin port and of the Java original of termux-shared)"
    gr "$OUT/compile.log" :termux-shared:assembleDebug :ts-java:assembleDebug
    local rc=$?
    grep -E "^e: |warning:|^w: |error:|BUILD|FAILED|What went wrong" "$OUT/compile.log" | head -60
    grep -cE "^w: " "$OUT/compile.log" | sed 's/^/kotlin warnings: /'
    return $rc
}

stage_kt() {
    log "compileDebugKotlin of the Kotlin port only"
    gr "$OUT/kt.log" :termux-shared:compileDebugKotlin
    local rc=$?
    grep -E "^e: |BUILD|FAILED|What went wrong" "$OUT/kt.log" | sed "s#$SRC/##" | cut -c1-300 | head -60
    grep -cE "^w: " "$OUT/kt.log" | sed 's/^/kotlin warnings: /'
    return $rc
}

stage_shape() {
    local JP=$W/jdk17/bin/javap
    local KT=$SRC/termux-shared/build/intermediates/aar_main_jar/debug/classes.jar
    local JV=$SRC/b4v/ts-java/build/intermediates/aar_main_jar/debug/classes.jar
    local DEPS=$SRC/terminal-view/build/intermediates/aar_main_jar/debug/classes.jar:$SRC/terminal-emulator/build/intermediates/aar_main_jar/debug/classes.jar
    ls -l "$KT" "$JV"
    for v in kt:$KT java:$JV; do
        local n=${v%%:*} j=${v#*:}
        ( cd $TMPDIR && unzip -Z1 "$j" | grep '\.class$' | sed 's/\.class$//; s|/|.|g' | sort ) > "$OUT/classes_$n.txt"
        $JP -p -cp "$j:$DEPS" $(cat "$OUT/classes_$n.txt" | tr '\n' ' ') > "$OUT/javap_$n.txt" 2>&1
    done
    log "classes kt=$(wc -l < $OUT/classes_kt.txt) java=$(wc -l < $OUT/classes_java.txt)"
}

for s in "$@"; do
    case "$s" in
        setup) stage_setup ;;
        compile) stage_compile ;;
        kt) stage_kt ;;
        shape) stage_shape ;;
        all) stage_setup; stage_compile && stage_shape ;;
        *) echo "unknown stage $s" ;;
    esac
done
log "phone_b4 finished: $*"
touch "$OUT/done_${*// /_}"
