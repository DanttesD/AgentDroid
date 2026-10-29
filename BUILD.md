# Building (agentdroid)

Requirements: Android SDK (compileSdk 35), NDK + CMake 3.22+, JDK 17, Gradle wrapper from the repo.

    ./gradlew :exec:test          # pure Java, runs without Android SDK
    ./gradlew :app:assembleDebug

Notes
- applicationId is `com.agentdroid` (see app/build.gradle, TermuxConstants.TERMUX_PACKAGE_NAME).
- Bootstrap: the build still downloads the official `bootstrap-*.zip` (built for the
  `com.termux` prefix). With the new package name it must be replaced by our own bootstrap
  built from termux-packages with `TERMUX_APP__PACKAGE_NAME=com.agentdroid`. Until then the
  terminal will not start. See STATUS.md.
- `:adb` needs the Kotlin plugin (added to the root build.gradle, Kotlin 1.7.22 for AGP 4.2.2).
