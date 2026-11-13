#!/data/data/com.termux/files/usr/bin/bash
# B2 verification on the phone (Bionic, aarch64), isolated in /data/local/tmp/agentdroid-verify/b2.
# usage: phone_b2.sh <step> [args]
#   libs      build the C baseline (clang) and the Rust library (rustc) for the phone's own ABI
#   targets   compile+link the Rust library for the other Android targets (link check only, no run)
#   javac     compile the stubs and the Java harnesses against the Kotlin classes built by Gradle
#   ses|jnih|drv   run a harness against both libraries and diff the results
set -u
W=/data/local/tmp/agentdroid-verify
V=$W/b2
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
unset LD_LIBRARY_PATH
export HOME=$W/home TMPDIR=$W/tmp
J=$W/jdk17/bin
KC=$W/src/terminal-emulator/build/tmp/kotlin-classes/debug
STD=$(ls $W/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.7.22/*/kotlin-stdlib-1.7.22.jar | head -1)
RUSTSRC=$V/terminal-emulator/src/main/rust
RUSTFLAGS_COMMON="--edition 2021 --crate-type cdylib --crate-name termux -C opt-level=s -C panic=abort -C strip=symbols"
mkdir -p $V/out $V/cwd

case "${1:-}" in
libs)
  mkdir -p $V/lib_c $V/lib_rs $V/inc $V/tools_out
  # Android's own jni.h from the NDK sysroot (only this header; the libc headers come from the Termux clang)
  JH=$(ls $W/sdk/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h | head -1)
  echo "jni.h: $JH"; cp "$JH" $V/inc/jni.h
  clang --version | head -1
  clang -shared -fPIC -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector -I$V/inc \
      $V/terminal-emulator/src/main/jni/termux.c -o $V/lib_c/libtermux.so && echo "C lib ok"
  rustc --version
  rustc $RUSTFLAGS_COMMON $RUSTSRC/lib.rs -o $V/lib_rs/libtermux.so && echo "Rust lib ok"
  ls -l $V/lib_c/libtermux.so $V/lib_rs/libtermux.so
  for L in c rs; do echo "== $L: dynamic symbols (defined)"; llvm-nm -D --defined-only $V/lib_$L/libtermux.so | awk '{print $3}' | sort | tr '\n' ' '; echo; echo "== $L: undefined"; llvm-nm -D --undefined-only $V/lib_$L/libtermux.so | awk '{print $2}' | sort | tr '\n' ' '; echo; echo "== $L: needed"; llvm-readelf -d $V/lib_$L/libtermux.so | grep -E 'NEEDED|SONAME'; done
  clang -O1 -o $V/tools_out/drv $V/b2v/tools/drv.c && clang -O1 -o $V/tools_out/winsz $V/b2v/tools/winsz.c && echo "tools ok"
  ;;
javac)
  rm -rf $V/stubs_out $V/hc $V/jc; mkdir -p $V/stubs_out $V/hc $V/jc
  $J/javac -nowarn -d $V/stubs_out $(find $V/b2v/stubs -name '*.java') 2>&1 | grep -v '^Note'
  $J/javac -nowarn -d $V/hc -cp $KC:$V/stubs_out:$STD $V/b2v/harness/SesH.java 2>&1 | grep -v '^Note'
  $J/javac -nowarn -d $V/jc -cp $KC:$STD $V/b2v/harness/JniH.java 2>&1 | grep -v '^Note'
  ls $V/hc/com/termux/terminal | head -3; ls $V/jc/jnih | head -3
  ;;
ses)
  L=${2:-c}
  cd $V && exec $J/java ${JAVA_EXTRA:-} -XX:-OmitStackTraceInFastThrow -Xss4m -Dfile.encoding=UTF-8 --add-opens java.base/java.io=ALL-UNNAMED \
    -Djava.library.path=$V/lib_$L -Dses.cwd=$V/cwd -cp $V/hc:$KC:$V/stubs_out:$STD com.termux.terminal.SesH
  ;;
jnih)
  L=${2:-c}; RUST=${3:-false}; MODE=${4:-all}
  cd $V && exec $J/java ${JAVA_EXTRA:-} -XX:-OmitStackTraceInFastThrow -Xss4m -Dfile.encoding=UTF-8 --add-opens java.base/java.io=ALL-UNNAMED \
    -Djava.library.path=$V/lib_$L -Djnih.tools=$V/tools_out -Djnih.cwd=$V/cwd -Djnih.rust=$RUST \
    -Djnih.env=/system/bin/env -Djnih.file=/system/etc/hosts -Djnih.dir=/system/bin \
    -cp $V/jc:$KC:$STD jnih.JniH $MODE
  ;;
drv)
  $V/tools_out/drv $V/lib_${2:-c}/libtermux.so
  ;;
*)
  echo "usage: $0 libs|javac|ses|jnih|drv ..."; exit 2 ;;
esac
