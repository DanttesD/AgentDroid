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

## 2026-11-01 — web accounts replace "Браузер" and "Режим Claude в браузере"
- Menu ⋮: "Веб-аккаунт" entries (long press: rename / change service / delete) + "Добавить веб-аккаунт". Tapping an account without a service opens the service window (Claude, ChatGPT, DeepSeek, Gemini, Grok, Perplexity, Mistral, Qwen, Copilot + user-added services). The page opens in the main window; each account keeps its own cookies via androidx.webkit profiles (first account = default profile, so existing logins stay). If the device WebView lacks MULTI_PROFILE, other accounts open in the separate profile screens instead. Not compiled or tested yet (androidx.webkit 1.12.1 added).
- Permanent tunnel address: ngrok (free static dev domain, port 443) runs inside the Debian proot (`/opt/ngrok/ngrok`, verified on the device: ngrok 3.39.13 starts); `TunnelManager` uses it when a token is saved (encrypted in SecretStore) and ngrok is installed, otherwise cloudflared. Setup buttons on the MCP screen. Not yet verified end to end (needs the user's ngrok token and domain).

## Коннектор: экран, Android, разработка, безопасность (2026-11-02)
Не проверено на устройстве; модуль :agent собран и протестирован (19 JUnit), приложение собирается только в CI.
- Экран через root/ADB: screenshot (картинка), ui_tree (координаты), tap, swipe, type_text (кириллица через буфер), key, open_app, list_apps, current_app, wait.
- Экран через службу специальных возможностей (`AgentAccessibilityService`, без root): ui_read (нумерованные элементы, пароли маскируются), click(id), long_click, set_text, scroll, global_action, wait_for; скриншот через службу как запасной вариант (Android 11+). Включение: настройки или кнопка «через root/ADB» на экране MCP.
- Android: device_info, intent, settings, logcat, dumpsys, app_install/uninstall, toggle (wifi/bt/data/nfc/экран), notifications (через dumpsys), sms_read, contacts, calendar, location, notify, clipboard_get/set.
- Разработка: edit, glob, grep.
- Безопасность: `ActionGuard` — аварийная остановка (кнопка и действие в уведомлении), список запрещённых приложений, журнал действий (agent-actions.log, стирается кнопкой); «чувствительные» вызовы (звонки/отправка, SMS, контакты, календарь, геолокация, установка/удаление) всегда спрашивают, даже в режиме «всегда разрешать».
- Среда для агентов: текст `instructions` в ответе initialize + файл ~/.agentdroid/ENVIRONMENT.md.
- Автозапуск сервера/туннеля после перезагрузки и обновления (BootReceiver, кнопка на экране MCP).
- Ещё не сделано: управление браузером (DOM/CDP), камера, уведомления через NotificationListenerService, шифрование журнала, подробные уровни доверия по приложениям, режим «человек и агент вместе», оверлей.

## 2026-11-03 — X11-рабочий стол: включение в сборку
- Вывод из анализа исходников апстрима: libXlorie.so не нужно пересобирать под com.agentdroid. Жёстко заданные пути com.termux — только запасные; X11Service задаёт TMPDIR и XKB_CONFIG_ROOT из нашего префикса. Все 34 JNI-метода нашего Java-кода есть в свежем nightly.
- Апстримный тег `nightly` плавающий (меняется ежедневно), поэтому libXlorie.so (nightly, коммит f1d9834) лежит в репозитории `third-party/termux-x11/libXlorie.so`, закреплена по SHA-256; prepare-x11.py берёт её оттуда, а не скачивает APK.
- CI: отдельный шаг «Prepare the X11 desktop runtime» (continue-on-error). Если он упал, APK собирается без X11 и в предупреждении указана причина.
- Меню чата: пункт «Рабочий стол (X11)» открывает терминал с подсказкой; сам переключатель рабочего стола — в старом интерфейсе терминала (WorkspaceDesktop).
- Не проверено: что prepare-x11.py проходит целиком в CI (остальные закрепы: xkeyboard-config с packages.termux.dev, openh264 и musl-dev с зеркал Debian/Alpine, стиль через cairo); запуск рабочего стола на телефоне; переименование Java-пакета com.termux.x11 не требуется (JNI привязан к имени класса, а не к applicationId).

## X11: что смягчено ради первого запуска и что нужно довести (список доработок)
Смягчено (в CI даёт предупреждение, а не остановку; после первого запуска рабочего стола вернуть строгость и проверить на телефоне):
1. Помощник совместимости для Alpine (x11-alpine-compat.so) — собирается clang+lld, при сбое пропускается.
2. Помощник для Fedora (x11-fedora-glycin.so) — кросс-компиляция aarch64-linux-gnu-gcc, при сбое пропускается.
3. Декодеры OpenH264 (x11-openh264.so, x11-gst-openh264.so) — нужны openSUSE; при сбое пропускаются, проверка хэшей в gradle для них стала «если файл есть».
4. Тема Kali (x11-style.zip) — хэш зависит от cairo/шрифтов сборочной машины; при несовпадении не готовится. Выяснить, откуда её брать для нашего пакета (подгрузка по запросу из отдельного репозитория компонентов).
5. Хэш архива раскладок теперь берётся из отчёта prepare-x11.py, а не зашит.
Не проверено на телефоне: запуск рабочего стола Termux/Ubuntu; остальные дистрибутивы; графика (Turnip, VirGL); пути в старом интерфейсе терминала, привязанные к com.termux.

Итог сборки #38 (2026-11-03): предупреждений о помощниках Alpine/Fedora/OpenH264 нет — пункты 1–3 закрыты. Пункт 4 закрыт иначе: тема Kali, драйвер Turnip и клиент VirGL скачиваются приложением во время работы (x11-extras.py, пины SHA-256, релиз x11-extras-20261025 в HezeAI/termux-jobs-components, доступен); генерация темы в CI убрана. Риск: зависимость от чужого репозитория — позже зеркалировать в свой релиз.

Итог сборки #37 (подготовка X11 прошла, предупреждения «X11 runtime not prepared» нет): библиотека X11, раскладки (296 файлов), помощник Alpine и декодеры OpenH264 собраны. Пропущены: помощник Fedora (не хватало заголовков glibc для кросс-компиляции — добавлен libc6-dev-arm64-cross, проверить в следующей сборке) и тема Kali (хэш не совпал с эталоном).

## X11: первый запуск на телефоне (2026-11-03)
- Цель «Termux» требует `pkg install x11-repo` и нативный XFCE из репозитория Termux-X11; в нашем apt-репозитории их нет, а бинарники из репозитория Termux собраны под /data/data/com.termux и под com.agentdroid не заработают. Решение: рабочий стол запускать в proot-дистрибутивах (Ubuntu/Debian…): их пакеты берутся из репозиториев дистрибутива. Нативный рабочий стол Termux потребует сборки x11-packages под наш префикс (очень долго) — отложено.
- Для proot-дистрибутивов в нашем репозитории не хватало pulseaudio (проверка jobs_audio_available требует pulseaudio и модули sles/native-protocol) и util-linux (setsid). Добавлены в bootstrap/packages.list; нужен перезапуск workflow «Build bootstrap (com.agentdroid)» (publish-apt).

## Порядок работ (решение пользователя, 2026-11-03)
1. Linux и дистрибутивы (запуск рабочего стола в proot Debian/Ubuntu и др.); после запуска вернуться к разбору того, как в Termux Job's устроены окружения.
2. Затем либо оставшийся функционал Termux, либо агенты (OpenCode, дополнительные аккаунты по образцу веб-аккаунтов) — порядок ещё не выбран.

## VirGL и пакеты (2026-11-04)
- Тема Kali и Turnip проверены на телефоне — работают. VirGL падал: в нашем apt нет mesa, angle-android, virglrenderer-android (в x11-packages их нет, они в основном packages/).
- Добавлен bootstrap/packages-optional.list и необязательный шаг в bootstrap.yml (continue-on-error, лимит 100 мин, кэш сохраняется): libc++, mesa, angle-android (собирается из исходников Chromium, может понадобиться два запуска), virglrenderer-android.
- Debian в proot уже установлен; «container is busy» — потому что в нём работает туннель ngrok для коннектора (не трогать).
- Сборка #22: основное опубликовано; необязательный шаг упёрся в 100 мин на сборке LLVM (из-за mesa). mesa убрана из списка и из проверки/установки хоста VirGL (x11-virgl-setup.sh) — это ещё одно смягчение: вернуться и проверить, нужна ли mesa хосту VirGL.

## Обновление из приложения (2026-11-04)
- WorkspaceUpdates теперь смотрит релизы DanttesD/AgentDroid (список, берётся самый новый с update.json), тег `apk-ГГГГ.ММ.ДД-N`, файл `agentdroid-debug.apk`; проверка пакета, версии, подписи и SHA-256 осталась прежней.
- CI (apk.yml): versionCode = 2000 + номер запуска, versionName = 2.1.<номер>; к релизу прикладывается update.json.
- Не проверено на телефоне (нужна сборка, потом ещё одна, чтобы увидеть предложение обновиться). Юнит-тесты WorkspaceUpdatesTest/WorkspaceUpdateInstallTest всё ещё написаны под com.termux и старые теги — переписать.

## Работа при выключенном экране (2026-11-04)
- На телефоне уже: приложение в белом списке Doze, standby-bucket 5 (active), phantom-processes без лимита (2147483647) — убийца дочерних процессов Android 12+ не мешает.
- Добавлено: McpService держит PARTIAL_WAKE_LOCK, пока работает туннель; в экране коннектора кнопка «Не засыпать при выключенном экране» (whitelist Doze, RUN_ANY_IN_BACKGROUND, bucket active через root/ADB).
- Не проверено на практике: многочасовой простой с выключенным экраном (проверить: туннель и сессия терминала живы утром).

## clang (2026-11-04)
- clang/lld/llvm — подпакеты libllvm; сборка LLVM ≈ 2,5–3 ч и не возобновляется (каталог сборки не кэшируется), поэтому не в обычном списке.
- В workflow «Build bootstrap» добавлен вход `heavy_packages`: запуск с `clang` собирает его одним шагом (лимит 280 мин, continue-on-error); готовые deb остаются в кэше и публикуются в apt при следующих запусках.
- Пока clang нет, компилировать можно внутри proot Ubuntu/Debian (`apt install clang`).

## Тесты (2026-11-04)
- Python-тесты (`python3 -m unittest discover -s scripts/tests`): 688 тестов, раньше 64 падения. Пути /data/data/com.termux в тестах X11/окружений заменены на com.agentdroid — эти тесты теперь проходят.
- Оставшиеся падения зависят от окружения песочницы, а не от кода: нужны arm64-хост (test_workspace_backup/selection, архитектура), собранные компоненты `.tools/x11-components` и `.tools/x11-runtime` (style, scale, virgl-client, openh264, extras), jniLibs из gradle, кросс-компилятор (garcon), точный тайминг групп процессов (environment_install).
- Java-тесты обновления (WorkspaceUpdatesTest, WorkspaceUpdateInstallTest) переписаны под com.agentdroid и теги apk-*; не запускались (нет Android SDK). Остальные Java-тесты с "com.termux" используют его как произвольное имя пакета.

## droidclaw ideas, step 1–2 (screen shortcuts)
- MCP instructions (McpHost.environmentText) now carry seven screen-work rules (shortcuts first, programmatic over taps, coordinates only from the tree, Back is destructive, silent successes, wait 5–15 s for chatbots, stop on repetition).
- New tools in `agent/.../ScreenFlows.java`: `find_and_tap`, `read_screen`, `submit_message`, `wait_for_content`. `find_and_tap` and `submit_message` go through ActionGuard (pause, blocked apps, log) and ask for confirmation when the label looks risky (send/pay/delete…).
- Tests: `ScreenFlowsTest` (7 cases) run on x86 with plain javac+JUnit; the 27 tests of the agent module pass. The Android side (McpHost wiring) is not compiled here (no SDK).
- Next: step 3 (stuck/repetition/drift detection and screenshot fallback in the native loop), then the voice overlay, then notification triggers.

## droidclaw ideas, step 3 (stuck detection)
- `agent/.../StuckDetector.java` is used by AgentLoop: same call 3×, same result 4×, or 5 observation-only calls → a `[hint]` is appended to the tool result; after 3 hints the loop stops with an error. Covers the native chat and anything built on AgentLoop. 33 agent tests pass (x86).
- Not done: screenshot fallback for the native loop (the native chat has no screen tools yet; MCP clients already have `screenshot`).

## Voice assistant overlay (step 5, written without an Android SDK: compile-check pending in CI)
- `PhoneAgent` builds the full phone toolset + guard for the MCP server, the native chat and the overlay. AgentLoop got `setGuard` and now forces confirmation for `sensitive` tool calls ("!" prefix), as the MCP server does. 34 agent tests pass.
- `AssistantOverlayService`: draggable ✦ bubble → panel (text + 🎤 speech via SpeechRecognizer ru-RU) → agent runs with a rotating colour glow around the screen and a "stop" pill; confirmations appear inside the overlay (`ApprovalCenter.setOverlay`).
- `AssistantActivity` (chat menu → «Голосовой помощник»): overlay permission, microphone, accessibility shortcut, on/off.
- Risks to check on the phone: microphone while another app is in front (needs visible overlay), recognition service present, keyboard in the overlay panel, glow cost.

## Notification rules (step 6, Android side not compiled here)
- Pure logic in `agent/.../Triggers.java` (rules, matching by package and keywords, prompt that fences the notification as data, cooldown, JSON); 39 agent tests pass.
- App side: `AgentNotificationListener` (needs notification access) → `TriggerRunner` (confirm mode = notification with a «Выполнить» button via `TriggerActionReceiver`; auto mode runs at once; one run at a time; skipped while the emergency stop is on) → result notification. Sensitive actions inside a run still ask (overlay or notification).
- UI: chat menu → «Правила по уведомлениям» (`TriggersActivity`).
- Not done: local time schedules (AlarmManager), per-rule app picker, running rules while the screen is locked.

## OpenCode integration, step 1 (MCP wiring)
- The fork's native OpenCode chat (Ubuntu via proot, pinned v2.0.28, per-connection XDG roots) is already in the code; unverified under com.agentdroid.
- New: `assets/agentdroid-mcp.cjs` + `AiAgents.writeMcpDescriptor`: when the OpenCode server starts, the connection's own `config/opencode/opencode.json` gets `mcp.agentdroid` (remote, loopback URL with the app's secret) and an `instructions` entry pointing to an environment file ("you run on the user's phone"). User keys are preserved; invalid user config is never overwritten. Test: `scripts/tests/test_agentdroid_mcp.cjs` (node).
- Needs the MCP server running (start it on the MCP screen) for OpenCode to see the phone tools.
- Next: device check of the OpenCode install/launch, OpenRouter free models, the Accounts card (Debian npm path) vs the fork's AI Agents path.

## Design step 1: theme layer (Android side not compiled here)
- `ThemeColors` (agent module, tested): palette from a hue, dark/light, AMOLED (pure black), contrast checked for every hue.
- `AppTheme`: mode system/dark/light, Material You (wallpaper accent on Android 12+), AMOLED, 4 fixed accents. `Ui`: cards, buttons, chips, switch rows.
- `AppearanceActivity` (chat menu → «Внешний вид»). Chat, Accounts and MCP screens now take colours from the theme.
- Next: bottom navigation shell (Чат, Агенты, Телефон, Среда), then Assistant/Triggers/overlay colours, then the Termux screens.

## Design step 2: navigation shell (Android side not compiled here)
- `NavBar` (bottom bar), `TabActivity` base; new tabs `AgentsActivity`, `PhoneActivity` (readiness, assistant, rules, MCP, emergency stop), `EnvActivity`. Chat has the bar; gear opens Appearance.
- Still old colours: Assistant, Triggers, overlay, Termux/workspace screens. The old chat menu still exists in parallel.

## Design step 3
- Assistant, Rules screens and the overlay take theme colours. The "where commands run" choice (Auto/Root/ADB/…) moved to the Environment tab; chat re-reads it on resume.
- Left: remove duplicated items from the chat ⋮ menu, Termux/workspace screens, per-account sign-in method in the Agents tab.

## Design step 4: workspace screens on the theme palette (not compiled here)
- `workspace/Pal` holds the colours of all workspace screens (terminal shell, servers, files, dialogs, agent chat, markdown); all hardcoded teal literals replaced by `Pal.*`, filled from the app theme in `TermuxActivity.onCreate`. `TermuxActivity` recreates itself when the theme signature changes.
- Not touched on purpose: terminal colour scheme, red stop button, syntax highlighting colours.

## Design step 5: fixes after the first look
- Real vector icons (`ic_*`) instead of emoji/text; chat rebuilt like the mock (header pill, status chips, round bubbles, tool cards, composer pill with round send/stop button).
- Every themed screen now recreates itself on resume when the palette signature changed (before: only Appearance did).
- Still not like the mock: approval dialog (system AlertDialog), no attach/mic buttons in the composer, tabs are simpler than boards.

## Design step 6: fidelity pass after looking at the phone
- Environment: short mode chips (Авто/Root/ADB/Termux), 2-column tiles with icons that open the matching workspace screen (`agentdroid_screen` extra → `WorkspaceController.openScreen`).
- Phone: icon rows with switches (assistant on/off), MCP status card with address, copy and new secret.
- Agents: session-style cards with state dot, account groups with avatar and method badge, floating "+ Аккаунт".
- Missing: storage card on Environment, approval card, attach/mic in composer.

## Design step 7: after the full screenshot review
- All dialogs (`ThemedDialog`/`DialogSkin`, also the workspace builder) follow the theme: surface, text, accent buttons, tinted inputs. No more white or teal dialogs.
- ADB console, Accounts and MCP screens restyled (cards, rounded buttons); MCP grouped into four cards.
- Web chat mode hides the status chips and shows "Веб-аккаунт в браузере" in the header pill.
- Still open: chat ⋮ menu duplicates tabs, workspace screens (home/commands/terminal top bar) keep the old layout, no storage card, no approval card.

## Браузер: совместимость (шаг 8)
- UA без "; wv", "Version/4.0" и Build-id; автозапуск медиа, зум, mixed content.
- Разрешения сайта на микрофон/камеру (WebRTC) с запросом Android-разрешений; CAMERA в манифесте.
- Полноэкранное видео, загрузки через DownloadManager.
- Балл 55 на trust-ip определяется IP хостинга прокси (AS59642), а не браузером.

## Прокси для браузера (шаг 9)
- BrowserProxy: прокси только для встроенного браузера (ProxyController), логин/пароль через 407, меню ⋮ → «Прокси для браузера».
- Балл trust-ip зависит от выходного IP; параметры устройства на него не влияют (по тексту страницы).

## Проверка сети (шаг 10)
- NetCheckActivity + assets/netcheck.html: собственная проверка (IP/тип адреса, сервисы ИИ, WebRTC STUN, DNS, оценка и подсказки), работает в WebView через прокси браузера. Меню ⋮ → «Проверка сети».
- Написана с нуля по видимому поведению (подход clean-room), код чужого сайта не копировался. Craft Anything в песочнице не запускался (нет доступа к сайту).

## Проверка сети: пересборка из ai-trust-check (шаг 11)
- assets/trustcheck: страница из архива ai-trust-check (пересборка Craft Anything, clean room). Оценка = trust_score от ip.net.coffee, как на оригинале; регионы вне списка и РФ/CN и т.д. дают 0.
- shim.js заменяет Node-сервер внутри страницы: /api/reputation, /api/dns/*; /api/status отвечает 404, страница берёт статусы напрямую.
- NetCheckActivity: WebViewAssetLoader отдаёт файлы по https://appassets.androidplatform.net/, работает через прокси браузера; мост AgentNative.get только для dnsleak.dev.
- Старый assets/netcheck.html удалён. Проверено в headless-Chromium с подставленными ответами (оценка 55 для хостингового IP).

## Проверка сети: интеграция в интерфейс (шаг 12)
- CssTheme (agent модуль, тесты): перекраска CSS страницы под тему (инверсия светлоты в тёмной теме, зелёные оттенки берут оттенок темы).
- NetScore (agent модуль, тесты): правила балла (0 для ограниченных регионов, ярусы 75/60).
- Нативная карточка с баллом над страницей; результат сохраняется (NetCheckActivity.last) и показывается на «Телефон» (строка «Сеть для ИИ») и в «Среда» (плитки «Проверка сети» и «Прокси браузера»).

## Проверка сети: правки по скриншотам (шаг 13)
- Карточка показывает худший балл и оба сервиса отдельно («ChatGPT 40 · Claude 88»): shim сопоставляет IP из cdn-cgi/trace с сервисом.
- Короткая кнопка «Прокси», заголовок страницы скрыт (дублировал карточку).

## Прокси для приложения (шаг 14)
- NetConfig (agent, тесты): один прокси для запросов к ИИ-моделям (Http.postSse); локальные и приватные адреса всегда напрямую. Включается галочкой в диалоге «Прокси» вместе с браузерным.
- Не охвачено: команды в терминале/Termux (curl, npm, apt) и туннель ngrok; их не трогаем.

## Два независимых прокси (шаг 15)
- BrowserProxy: «Прокси браузера» (WebView и проверка сети) и «Прокси приложения» (запросы к ИИ через NetConfig). Свои адрес/логин/пароль у каждого; старые настройки переносятся при первом открытии.
- Команды терминала и ngrok по-прежнему идут напрямую.

## Структура приложения: один раз и по местам (шаг 16)
Цель — схема из макетов Map/Settings: четыре вкладки (Чат · Агенты · Телефон · Среда) и одна шестерёнка; у каждой вещи одно место, до любой функции не больше двух нажатий, подтверждения — в ленте чата.

Что изменено
- Настройки: один экран `AppSettingsActivity` (шестерёнка на всех вкладках и в рабочей области). Вид (тема, Material You, AMOLED, акцент), размер текста (85/100/115/130 %, `AppTheme.TEXT_SCALES`), язык, обновления (проверка и автопроверка), прокси (оба независимых), данные (резервные копии, истории чатов, настройки терминала, известные ошибки), «О приложении». `AppearanceActivity` и старое окно «Настройки приложения» в рабочей области удалены; действия, которым нужны данные рабочей области, открываются ссылкой `agentdroid_screen=settings:backup|histories|errors|storage` через `WorkspaceSettings`.
- Чат: плашка вверху — выбор исполнителя (встроенный агент / веб-аккаунт), ⋮ только про сам чат (новый чат и копирование переписки; в режиме веб-аккаунта — обновить страницу, сменить сервис, переименовать, удалить), шестерёнка — настройки; остановка — кнопка в строке сообщения. Меню на 11 пунктов, диалог бэкенда и все навигационные пункты убраны (они есть на вкладках). Ключ/модель/адрес — один диалог `ProviderDialog` (вкладка «Агенты» и запрос ключа в чате).
- Подтверждения действий — карточкой в ленте чата (`InlineApproval`: Разрешить / На час / Запретить, после ответа сворачивается в строку). На других экранах — прежний диалог/плашка поверх/уведомление; `ApprovalCenter` теперь работает на всех вкладках и в настройках.
- Вложения (файлы, до `Attachment.MAX_BYTES`) и голосовой ввод в строке сообщения; логика склейки текста — `agent/.../Attachment` с тестами.
- Агенты: один список аккаунтов (веб-аккаунты чата и CLI-аккаунты официальных агентов), один вход «+ Аккаунт» (веб / CLI / ключ API), долгое нажатие на веб-аккаунт — переименовать / сменить сервис / удалить (профиль браузера удаляется вместе с аккаунтом).
- Телефон: плашки готовности кликабельны и ведут в нужный раздел; вместо мёртвого переключателя — статус; MCP разделён на два экрана («Коннектор» и «Безопасность»), аварийная остановка не дублируется.
- Среда: «где выполнять команды» + плитки-назначения (терминал, сессии, команды, серверы, ADB, рабочий стол, рабочая область); проверка сети и прокси больше не дублируются (они в «Телефоне» и в «Настройках»); карточка хранилища ведёт в очистку.
- Рабочая область: стрелка в заголовке называет экран, откуда её открыли («‹ Среда», «‹ Агенты», «‹ Настройки», «‹ Коннектор MCP»; `agentdroid_from`), и возвращает туда (раньше там была подпись «AgentDroid»); без указания источника — «‹ Среда»; язык и размер текста подхватываются при возврате; настоящая иконка-шестерня.
- Убрано 41 неиспользуемая строка (остатки старых меню) в обеих локалях.

Проверено (исходники, без устройства)
- Юнит-тесты модулей agent и exec на JUnit 4: 79 пройдено, из них 10 новых на `Attachment` (границы размера, склейка, текст/не текст). Покрывают только чистую логику.
- Синтаксис и ссылки на символы: javac без Android SDK (фильтр шума SDK) для всех изменённых файлов — ошибок в новых файлах нет; ссылки на ресурсы (строки, иконки, id) сверены скриптом, отсутствующих ресурсов приложения нет (остались только системные `android.R`-ресурсы и ресурсы Termux, как и раньше); XML разобран, ru/en совпадают по набору строк.
- Сборка «Build APK» #53 на коммите bd212d5 (запуск владельца, по его скриншоту) прошла успешно: debug-APK собран и опубликован (релиз apk-2026.11.07-53, версия 2.1.53, подпись постоянным ключом). Значит, компиляция Gradle и сборка ресурсов проходят. Юнит-тесты этот workflow не запускает; предупреждения в аннотациях — старые устаревшие API в `AdbMdns.kt`, версии действий GitHub (Node 20, setup-java v4) и несохранённый кэш.
- Логический разбор вручную: цепочки чат → подтверждение → освобождение при уходе с экрана, смена веб-аккаунта (первый аккаунт использует профиль по умолчанию, остальные — свои), повторные запуски экрана при ошибке открытия, порядок `ApprovalCenter.onPause` / `releasePending`.

НЕ проверено
- Всё поведение на устройстве: вид и отступы экранов, жесты, голосовой ввод, выбор файла, карточка подтверждения в ленте, смена языка и размера текста без перезапуска, возврат из рабочей области по «‹ Среда».
- Работа подтверждений, когда агент спрашивает при свёрнутом приложении (плашка поверх / уведомление) — путь не менялся, но и не проверялся после перестройки.

Известные ограничения
- SOCKS5 с логином/паролем в WebView не поддерживается; терминал и ngrok идут мимо прокси (как раньше).
- `TermuxActivity` — singleTask: переход в рабочую область из «Среды» и назад очищает её стек.
- Первый по порядку веб-аккаунт использует профиль WebView по умолчанию (cookies общие с остальным браузером); остальные — изолированные профили.
- `AccountsActivity` (вход CLI-агентов) пока без единой шапки «назад», как остальные экраны.

## 2026-11-09 — переписывание на Kotlin и Rust: этап A (модули `exec` и `agent`)

Работа идёт в ветке `rewrite` (план и правила — `docs/rewrite/PLAN.md`); `main` не менялся и остаётся собираемым. «Build APK» на ветке `rewrite` запускает владелец: в Actions выбрать ветку `rewrite`.

Сделано
- `exec` (10 файлов) и `agent` (32 файла) полностью на Kotlin, Java-оригиналы удалены (остались в истории, коммит adb027b). Публичный API для оставшегося Java-кода тот же; плагин Kotlin 1.7.22, язык 1.7, `jvmTarget 1.8` — как в проекте.
- Найденные и закрытые расхождения с Java: `trim`/`split` (см. план), `null` в явных проверках `ShellQuote`/`ExecRequest`, имя вызова инструмента без значения (`ToolCall.name`). На каждое добавлен тест (падает на прошлом коммите, проходит на текущем).

Проверено (без устройства)
- Тесты: 82 из 82 (19 `exec` + 63 `agent`, из них 3 новых) — в песочнице (компилятор Kotlin на уровне языка 1.7, JUnit 4.13.2) и на телефоне настоящим Gradle 7.6.4 + плагин Kotlin 1.7.22 + AGP 4.2.2 + JDK 17 в изолированном каталоге; там же `:app:compileDebugJavaWithJavac` — `app` компилируется против Kotlin-классов. Коммит 5a7043b.
- Публичный API: сравнение `javap` оригинала и порта — потерянных членов нет, добавленные и отличия подстановочных знаков перечислены в плане.
- Строковые константы классов: все константы оригинала на месте, исключения объяснены (план).
- Дифференциальный прогон: один и тот же Java-код выполнялся против оригинала и против порта, результаты сравнивались построчно. Последняя серия: 127 637 случаев чистой логики (JSON, CSS-тема, цвета, сеть, детектор зацикливания, вложения, триггеры, выбор элементов — 2 набора случайных входов), 3 000 случаев цикла агента / MCP-сервера / потоков провайдеров / тел запросов (3 набора), 1 868 случаев `exec`, плюс перебор `null` во всех аргументах каждого метода; расхождений нет. До этого такие же прогоны выявили три расхождения выше.
- Чувствительность проверки: в копию кода внесены 6 заведомых ошибок — сравнение поймало 5, шестая эквивалентна по поведению; тесты JUnit поймали только 2 (подробности — в плане).

НЕ проверено
- Работа на устройстве (среда выполнения ART, чат, MCP-коннектор, инструменты экрана) — с нового кода не запускалась.
- Полная сборка APK и упаковка, GitHub Actions на ветке `rewrite` — только по запуску владельца.
- Юнит-тесты `app` (`WorkspaceDesktopUiTest`, `WorkspaceIconButtonTest`) не собираются и до переписывания (ссылаются на удалённые `WorkspaceDialogs.TEXT/ACCENT/MUTED`); этот workflow их не запускает.

Дальше: этап B1 — `terminal-emulator` на Kotlin (его 20 тестов upstream + сравнение «оригинал vs порт» на случайных управляющих последовательностях), затем `termux.c` → Rust.

## 2026-11-12 — переписывание на Kotlin и Rust: этап B1 (модуль `terminal-emulator`)

Работа идёт в ветке `rewrite` (план — `docs/rewrite/PLAN.md`); `main` не менялся. «Build APK» на ветке `rewrite` запускает владелец: в Actions выбрать ветку `rewrite`.

Сделано
- 16 Java-файлов `terminal-emulator` заменены Kotlin-кодом (`src/main/kotlin`, 5,6 тыс. строк): эмулятор, буфер, строки, цвета, клавиши, сессия `TerminalSession`, очередь байт, журнал и др. Java-оригиналы удалены (остались в истории, коммит b592a5a). Нативная часть (`termux.c`, ndkBuild) не менялась — это этап B2. 20 тестов upstream остаются на Java без правок. Публичный API для `terminal-view`, `termux-shared` и `app` сохранён; отличия (члены, которые в Java были package-private, стали public) перечислены в плане.
- Найденное при переносе: `trim`/`split` ведут себя в Kotlin иначе, чем в Java (вспомогательные `jtrim`/`jsplit`); `Logger` обязан бросать `NullPointerException` при `null`-сообщении, как нативный `println`.

Проверено (без устройства)
- Тесты: 154 из 154 — в песочнице (компилятор Kotlin на уровне языка 1.7, JUnit 4.13.2) и на телефоне настоящей цепочкой (Gradle 7.6.4, плагин Kotlin 1.7.22, AGP 4.2.2, JDK 17, изолированный каталог); там же `terminal-view`, `termux-shared` и `app` компилируются против нового модуля.
- Публичный API (`javap`): потеряны только публичные конструкторы без аргументов у четырёх служебных классов; добавленное и расширенное — в плане. Все 78 статических констант (включая приватные) совпадают по значению; строковые литералы на месте.
- Дифференциальный прогон эмулятора: один и тот же Java-код против оригинала и порта — 6 наборов по 20 000 случайных потоков управляющих последовательностей (изменения размера, вставка, мышь, запросы), группы буфера и вспомогательных классов, 19 759 точечных проб (прямоугольные операции при полях и режиме origin, заполненные строки с комбинируемыми и широкими символами, границы длины OSC/DCS, все коды SGR); расхождений нет.
- `TerminalSession` через настоящий PTY (нативная библиотека собрана из того же `termux.c` для хоста): 45 проверок × 6 запусков на оригинале и на порте, результаты совпали: коды выхода 0/7/255/−9/−15, UTF-8 ввод и `writeCodePoint`, размеры окна, окружение и каталог, ошибки `chdir`/`exec`, заголовок, звонок/цвета/буфер обмена, большие потоки, сброс, синхронные кадры, 6 параллельных сессий, смена клиента.
- Чувствительность проверки: ручные и автоматические мутанты, разбор «выживших», доработки стенда — в плане. Итог: эмулятор 87 из 105 автоматических мутантов пойманы сразу (7 не компилируются), 11 выживших разобраны (4 эквивалентны, 4 — константы, проверенные сравнением значений, 3 — пробелы стенда, закрыты точечными пробами); ручные: 56 из 58 (2 эквивалентны); `TerminalSession`: все неэквивалентные выжившие закрыты новыми сценариями.

НЕ проверено
- Сборка нативной части (ndkBuild) и упаковка `.so` в APK; работа на устройстве (ART, Bionic) — с нового кода не запускалась.
- Полная сборка APK и GitHub Actions на ветке `rewrite` — только по запуску владельца.
- Один сбой на оригинале: в 1 из 36 запусков сеансового стенда (18 на оригинале, 18 на порте) процесс завершился кодом 255 до первого вывода (в `termux.c` это либо ошибка `open` slave-устройства в дочернем процессе — `exit(-1)`, либо необработанная ошибка `waitpid` с неинициализированной переменной `status`). У порта такого сбоя не было, но по одному случаю делать вывод о частоте нельзя. Нативный код порт не затрагивает; разберём в B2.

Дальше: этап B2 — `termux.c` → Rust `cdylib` (первый Rust в проекте), затем `terminal-view`, `termux-shared`, `app`.
