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
