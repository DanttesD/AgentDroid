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

## 1.5 Signed apt repository
Done
- Signing key (bootstrap/agentdroid-apt.gpg / .asc public, fingerprint in fork.env). The PRIVATE key was delivered separately to the owner and must only live in the GitHub secret APT_GPG_PRIVATE_KEY.
- bootstrap/make-apt-repo.sh: builds pool + Packages + Release + InRelease + Release.gpg; tested locally with a fake deb, signature verified with the public key only.
- configure-termux-packages.sh now also: points apt's sources.list to our repo, makes termux-keyring install only our key (checked on copies of the upstream files).
- bootstrap/packages.list + workflow job `publish-apt`: builds listed packages, signs, publishes to GitHub Pages (https://danttesd.github.io/AgentDroid/apt/main/).

One-time setup on GitHub
1. Repo secret APT_GPG_PRIVATE_KEY = contents of agentdroid-apt-PRIVATE.asc (then delete that file).
2. Settings > Pages > Source = "GitHub Actions".
3. Actions > "Build bootstrap (com.agentdroid)" > Run workflow.

Not verified: the workflow on GitHub, that apt on a device accepts the repository.

## First CI run (#1) result
Failed after 52 min: upstream build-bootstraps.sh asks for package "bzip2", which only exists as a subpackage of libbz2 at the pinned commit ("No package bzip2 found").
Fixes: configure-termux-packages.sh patches build_package() to resolve subpackages to their parent; resolve-package-names.sh does the same for bootstrap/packages.list (curl -> libcurl).
Resumable builds: /data, installed prefix and output/*.deb are cached (actions/cache); the build is stopped with `timeout` before the 6 h job limit and can be re-run to continue.

CI run #2 failed within 3 minutes: "mkdir: cannot create directory /home/builder/.termux-build/apt: Permission denied" - run-docker.sh -m created the host mount dir as root. Fixed by creating ~/.termux-build with open permissions before the build.
