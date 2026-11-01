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

CI run #3 (51 min, got much further: apt, bash, libbz2 etc. built) failed on `command-not-found`: its build downloads Contents-aarch64.gz from our repository -> 404 (repository does not exist yet). Fix: configure-termux-packages.sh drops it from the bootstrap package list (optional "Recommends" only). Can be added back to packages.list later once the repository has been published once.

## 2026-10-31 — Stage 1 verified on a device (ASUS AI2201)
- Package com.agentdroid; own bootstrap (CI run #13) and signed apt repository on GitHub Pages work:
  `pkg update`, `pkg install`, `pkg upgrade`, git, node 26, tmux, jq.
- Own ADB client (no Shizuku): pairing by code, mDNS port discovery, shell as uid=2000, switch to local
  TCP 5555 — works with Wi-Fi turned off (until the next reboot).
- Test console: second launcher icon "AgentDroid ADB". App renamed to AgentDroid.
- Permanent APK signing key via the secret AGENTDROID_KEYSTORE_B64 (workflow "Build APK").
- Known leftovers: X11/graphics payloads are still built for com.termux (X11 native runtime is skipped in CI);
  tests under app/src/test still assert com.termux; update checker points at the fork's releases;
  shell survival with the screen off is not tested yet; clang is not in our repository.
- Next: Stage 2 — Compose chat + agent loop with one provider.

## 2026-10-31 — Stage 2 started: chat + agent loop
- `:agent` (pure Java, 6 JUnit tests with mock HTTP server): Json, OpenAI-compatible provider (also DeepSeek), Anthropic provider, AgentLoop with approvals and step limit, tools shell/read_file/write_file/list_dir through ExecBackend.
- App: `ChatActivity` (second launcher icon "AgentDroid"), provider/key settings (key encrypted by Android Keystore), backend picker (root / ADB 127.0.0.1:5555 / Termux).
- Not yet compiled on CI at the time of writing — verify with the APK workflow.

## 2026-10-31 — one icon, Russian UI (user request)
- Single launcher icon "AgentDroid" = chat. Terminal (TermuxActivity) and ADB console are no longer launcher entries; they open from the chat menu (⋮) and are mainly tools for the agent.
- Chat UI strings moved to resources (values / values-ru, `agent_strings.xml`); Russian by default on a Russian device; agent replies in the user's language.
- Planned next: accounts screen. Facts to respect: consumer subscriptions (ChatGPT, Claude, Gemini) do not give API access to third-party apps; real sign-in routes are (1) OpenRouter OAuth (PKCE) -> API key, (2) official CLIs (Claude Code, Codex, Gemini CLI, OpenCode) with their own login inside the built-in terminal, (3) API keys.

## 2026-10-31 — MCP server + built-in browser (user: "реализуй оба варианта")
- `:agent` `McpServer` (plain sockets, MCP Streamable HTTP, endpoint `/mcp/<secret>` or Bearer): tools shell/read_file/write_file/list_dir through the selected backend; risky calls need approval on the phone (dialog, or notification if the app is in background). 5 JUnit tests.
- App: `McpService` (foreground), `McpActivity` (start/stop, local URL, secret rotation, cloudflared tunnel instructions), `WebBrowserActivity` (WebView, "; wv" removed from UA for sign-ins), menu items in chat.
- Path A (claude.ai chat uses the phone): needs public tunnel (cloudflared in Termux — package availability in our apt repo NOT verified) + custom connector in claude.ai (plan availability NOT verified).
- Path B: our own chat (API key). Path C: external agents (Claude Code, Codex, OpenCode…) connect to local MCP URL. Not tested on a device yet.

## 2026-11-01 — verified: claude.ai custom connector -> phone
- Tunnel (cloudflared from our apt repo) + MCP server + claude.ai custom connector verified end to end: shell tool ran on the phone (ASUS_AI2201_A, Android 14). Quick-tunnel address changes on every restart (update connector); named tunnel needs a domain.
- apt repo hygiene: newest version only; dpkg-perl repacked (no clang dep, no CPAN postinst, version +ad2).
- Built-in browser: pop-ups supported (Google sign-in); 8 profiles (separate process + data dir suffix per profile => separate cookies/accounts), renameable by long press. Not yet verified on device.
- Main screen "web mode" (menu ⋮ -> "Режим: Claude в браузере"): claude.ai fills the area under the top bar (profile 0); file/photo picker, pop-ups and back button handled by `BrowserHelper`. Not verified on device.

## Accounts and sign-in (Stage 3, step 1)
- `AccountsActivity`: cards for Claude Code, Codex, Gemini CLI, OpenCode; install via a Termux session (`npm install -g`), status via `command -v`.
- Multiple named accounts per agent (`AccountsStore`), each with its own data dir (`~/.agentdroid/accounts/<agent>/<name>`) and its own browser profile slot.
- Login runs in a visible Termux session with `BROWSER=agentdroid-open`, which opens the login URL in the account's browser profile.
- Unverified on device: npm install of each CLI under Termux, login redirects/callbacks.
- Agents run inside a proot-distro Debian (verified by the developer on the device: Claude Code 2.1.316 installs and runs there; Termux/Android has no native Claude Code build). Install/check/run/login all go through `proot-distro login debian`, account dir is bind-mounted and used as CLAUDE_CONFIG_DIR etc.
- Unverified: login flow in Debian (URL must be copied manually, no BROWSER bridge into proot yet), Codex/Gemini/OpenCode installs.
- MCP screen: built-in tunnel control (`TunnelManager` runs cloudflared as a child process, parses the trycloudflare URL and "Registered tunnel connection"), button to copy the full connector address. Unverified on device. Note: port 7844 is blocked on the developer's home Wi-Fi; works on mobile data.
