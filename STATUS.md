# Status

Done
- 1.1 Fork copied, applicationId and prefix paths switched to `com.agentdroid`.
- 1.2 `:adb` module: ADB protocol, pairing, mDNS, keys (from Shizuku adb package, Apache-2.0), `AgentAdb` facade, `AdbShellTransport`.
- 1.3 `:exec` module: ExecBackend, ProcessBackend, RootBackend, AdbBackend, ShellFiles, BackendSelector. 17 unit tests pass (run with plain JUnit).

Not verified (no Android SDK / Maven access in the authoring sandbox)
- Gradle build of :app and :adb, JNI build of libagentadb.so, anything on a device.

Open before the terminal works
- Own bootstrap and package repo for the `com.agentdroid` prefix (replaces the downloaded official bootstrap).
- X11/graphics payloads in the fork are pinned archives built for `com.termux`; they need rebuilding for the new prefix.
- Update checker in WorkspaceUpdates points at the fork's releases; disable or point to our own.
- Tests in app/src/test that assert `com.termux` as package id still need updating.

## 1.4 Bootstrap pipeline
Done
- bootstrap/fork.env: package name, pinned termux-packages commit, apt repo URL, extra packages.
- bootstrap/configure-termux-packages.sh: patches only upstream-documented fork variables; checked locally: PREFIX becomes /data/data/com.agentdroid/files/usr.
- .github/workflows/bootstrap.yml: builds bootstrap-aarch64.zip with scripts/build-bootstraps.sh in docker, fails if the archive still contains /data/data/com.termux/, publishes a release and prints the url/sha256 lines for bootstrap/bootstrap.properties.
- app/build.gradle: official bootstrap download removed; reads bootstrap/bootstrap.properties, verifies SHA-256, clear error if not configured; -Pagentdroid.localBootstrap=true accepts a self-built archive. Download logic tested with Gradle for the 5 cases (no config, local archive, matching hash, wrong hash, unreachable URL).

Not verified: the workflow itself (needs GitHub; build takes hours), and that the resulting terminal starts on a device.

Open (next: 1.5)
- Own apt repository + signing key + termux-keyring, otherwise `pkg install` inside the app has no source (only packages baked into the bootstrap work).
- (done) repo set to DanttesD/AgentDroid.
