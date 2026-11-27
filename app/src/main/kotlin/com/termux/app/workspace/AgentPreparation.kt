package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Read-only inventory before a user-approved launch; installation runs in the new terminal only.*/
object AgentPreparation {
    fun interface Runner {
        @Throws(Exception::class)
        fun run(c: Context, p: WorkspaceStore.Session?): Plan
    }

    class Plan {
        @JvmField var environment: String = "ubuntu"
        @JvmField var path: String = ""
        @JvmField val ready: LinkedHashSet<String> = LinkedHashSet<String>()
        @JvmField val missing: LinkedHashSet<String> = LinkedHashSet<String>()
        @JvmField var complete: Boolean = false
        @JvmField var authNeeded: Boolean = false
        @JvmField var error: String = ""

        fun ready(): Boolean {
            return complete && error.isEmpty() && missing.isEmpty()
        }

        companion object {
            @JvmStatic
            fun parse(text: String, exit: Int): Plan {
                val p = Plan()
                if (text.length > 32768) {
                    p.error = "CHECK_TOO_LARGE"
                    return p
                }
                for (line in (text as java.lang.String).split("\\r?\\n")) {
                    if (line.equals("CHECK:done")) {
                        p.complete = true
                    } else if (line.equals("AUTH:needed")) {
                        p.authNeeded = true
                    } else {
                        for (prefix in arrayOf<String>("READY:", "MISSING:")) {
                            if (line.startsWith(prefix)) {
                                val id: String = line.substring(prefix.length)
                                if (COMPONENTS.contains(id)) {
                                    (if (prefix.equals("READY:")) p.ready else p.missing).add(id)
                                }
                            }
                        }
                    }
                }
                if (exit != 0 || !p.complete) {
                    p.error = "CHECK_FAILED"
                }
                return p
            }
        }
    }

    const val GUEST: String = "/tmp/termux-jobs-setup"
    @JvmField val ASSETS: Array<String> = arrayOf<String>("agent-dependencies.sh", "agent-host-setup.sh", "codex-bootstrap.sh", "vertex-bootstrap.sh", "vertex-project.sh", "environment-runtime.sh")
    @JvmField val COMPONENTS: MutableSet<String> = HashSet<String>(Arrays.asList("proot-distro", "ubuntu", "curl", "tar", "sha256sum", "flock", "xz", "find", "sort", "xargs", "ca-certificates", "node", "codex-cli", "claude-cli", "gemini-cli", "opencode-cli", "gcloud", "python3"))
    @JvmField val SYSTEM: Runner = Runner(AgentPreparation::query)

    @JvmStatic
    fun agent(p: WorkspaceStore.Session?): String {
        return if (p!!.geminiAgent) "gemini" else if (p.claudeAgent) "claude" else if (p.aiAgent.equals("opencode")) "opencode" else if (p.environment.equals("codex")) "codex" else ""
    }

    @JvmStatic
    fun supports(p: WorkspaceStore.Session?): Boolean {
        return !agent(p).isEmpty() && AgentEnvironments.ubuntu(p!!.codexEnvironment) && (p.environment.equals("ubuntu") || p.environment.equals("codex"))
    }

    @JvmStatic
    fun key(p: WorkspaceStore.Session?): String {
        return agent(p) + "|" + p!!.environment + "|" + p.codexEnvironment + "|" + p.geminiAuth + "|" + p.geminiCloudProject + "|" + p.geminiCloudLocation
    }

    @JvmStatic
    fun directory(files: File): File {
        return File(files, ".agent-preparation")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(c: Context) {
        val dir: File = directory(c.getFilesDir())
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create setup directory")
        }
        protect(dir)
        for (name in ASSETS) {
            val temp: File = File.createTempFile("setup-", ".tmp", dir)
            try {
                (c.getAssets().open(name)).use { `in` ->
                    (FileOutputStream(temp)).use { out ->
                        val b = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(b)
                            if (n == -1) break
                            out.write(b, 0, n)
                        }
                    }
                }
                protect(temp)
                if (!temp.renameTo(File(dir, name))) {
                    throw IOException("Cannot publish setup asset")
                }
            } finally {
                temp.delete()
            }
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun protect(f: File) {
        if (!f.setReadable(false, false) || !f.setWritable(false, false) || !f.setExecutable(false, false) || !f.setReadable(true, true) || !f.setWritable(true, true) || f.isDirectory() && !f.setExecutable(true, true)) {
            throw IOException("Cannot protect setup file")
        }
    }

    @JvmStatic
    fun language(c: Context): String? {
        return WorkspaceLanguage.code(c)
    }

    @JvmStatic
    fun tr(c: Context, ru: String, en: String): String {
        return if (language(c)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun environment(p: WorkspaceStore.Session?): String {
        return "export TERMUX_JOBS_AGENT_ENVIRONMENT=" + SessionShell.quote(AgentEnvironments.alias(p)) + "; export JOBS_AGENT_UBUNTU=" + SessionShell.quote(AgentEnvironments.alias(p)) + "; export JOBS_AGENT_SETUP_ALLOWED=" + (if (p!!.agentSetupAllowed) "1" else "0") + "; export JOBS_AGENT_LANGUAGE=" + (if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "ru" else "en") + "; "
    }

    @JvmStatic
    fun host(files: File, p: WorkspaceStore.Session?): String {
        if (!supports(p)) {
            return ""
        }
        return environment(p) + "source " + SessionShell.quote(File(directory(files), "agent-host-setup.sh").getAbsolutePath()) + "\njobs_agent_host_prepare || return \$?\n"
    }

    @JvmStatic
    fun wrap(p: WorkspaceStore.Session?, command: String): String {
        if (!supports(p)) {
            return command
        }
        return "( set -e; " + environment(p) + "source " + SessionShell.quote(GUEST + "/agent-dependencies.sh") + "; jobs_agent_prepare " + SessionShell.quote(agent(p)) + " " + SessionShell.quote(if (p!!.geminiAgent) p.geminiAuth else "api") + "; " + (if (p.geminiAgent && p.geminiAuth.equals("vertex")) "export JOBS_GEMINI_VERTEX_PROJECT=" + SessionShell.quote(p.geminiCloudProject) + "; export JOBS_GEMINI_VERTEX_LOCATION=" + SessionShell.quote(p.geminiCloudLocation) + "; source " + SessionShell.quote(GUEST + "/vertex-bootstrap.sh") + "; " + VertexConnection.exports(p) + "source " + SessionShell.quote(GUEST + "/vertex-project.sh") + "; " else "") + command + " )"
    }

    @JvmStatic
    fun codexPath(): String {
        return "if ! command -v node >/dev/null 2>&1 || ! node -e 'process.exit(Number(process.versions.node.split(\".\")[0])>=20?0:1)' >/dev/null 2>&1; then export PATH=\"\$HOME/.local/share/termux-jobs/agent-tools/node-v22.22.1-linux-arm64/bin:\$PATH\"; fi; if ! command -v codex >/dev/null 2>&1; then export PATH=\"\$HOME/.local/share/termux-jobs/codex-cli/0.163.0/vendor/aarch64-unknown-linux-musl/bin:\$HOME/.local/share/termux-jobs/codex-cli/0.163.0/vendor/aarch64-unknown-linux-musl/codex-path:\$PATH\"; fi; "
    }

    @Throws(Exception::class)
    @JvmStatic
    fun query(c: Context, p: WorkspaceStore.Session?): Plan {
        prepare(c)
        val shell = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash")
        val timer = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "timeout")
        if (!shell.isFile() || !timer.isFile()) {
            val plan = Plan()
            plan.error = "TERMUX_NOT_READY"
            return plan
        }
        val script: String = "exec " + SessionShell.quote(timer.getAbsolutePath()) + " -k 2 25 " + SessionShell.quote(shell.getAbsolutePath()) + " --noprofile --norc " + SessionShell.quote(File(directory(c.getFilesDir()), "agent-host-setup.sh").getAbsolutePath()) + " check " + SessionShell.quote(agent(p)) + " " + SessionShell.quote(if (p!!.geminiAgent) p.geminiAuth else "api") + " " + SessionShell.quote(AgentEnvironments.alias(p))
        val pb = ProcessBuilder(shell.getAbsolutePath(), "--noprofile", "--norc", "-c", script)
        pb.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        pb.environment().clear()
        for (item in TermuxShellUtils.buildEnvironment(c, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val i: Int = item.indexOf('=')
            if (i > 0) {
                pb.environment().put(item.substring(0, i), item.substring(i + 1))
            }
        }
        pb.redirectError(File("/dev/null"))
        val process: Process = pb.start()
        try {
            val out = ByteArrayOutputStream()
            val b = ByteArray(1024)
            var n: Int = 0
            val `in` = process.getInputStream()
            var primaryException: Throwable? = null
            try {
                while (true) {
                    n = `in`.read(b)
                    if (n == -1) break
                    if (out.size() + n > 32768) {
                        throw IOException("Check output exceeded limit")
                    }
                    out.write(b, 0, n)
                }
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        `in`.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    `in`.close()
                }
            }
            if (!process.waitFor(3L, TimeUnit.SECONDS)) {
                throw IOException("Environment check timeout")
            }
            val result: Plan = Plan.parse(out.toString("UTF-8"), process.exitValue())
            result.environment = AgentEnvironments.alias(p)
            val fs: File? = WorkspaceEnvironments.root(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), result.environment)
            result.path = if (fs == null) "" else fs.getAbsolutePath()
            return result
        } finally {
            process.destroy()
        }
    }

    @JvmStatic
    private fun componentLabels(p: Plan, ids: MutableCollection<String>): MutableList<String> {
        val labels: MutableList<String> = ArrayList<String>()
        for (id in ids) {
            labels.add(if (id.equals("ubuntu")) AgentEnvironments.label(p.environment) else id)
        }
        return labels
    }

    @JvmStatic
    fun summary(c: Context, p: Plan): String {
        var body: String = if (p.error.equals("UNSUPPORTED_ENVIRONMENT")) tr(c, "Автоподготовка доступна для AI Agents в Ubuntu. Старый профиль Codex в Termux сохранён без изменений.", "Automatic preparation is available for AI Agents in Ubuntu. The legacy Codex Termux profile is unchanged.") else if (p.ready()) tr(c, "Компоненты выбранного агента найдены.", "The selected agent components were found.") else if (!p.error.isEmpty()) tr(c, "Не удалось проверить выбранное окружение. Оно может быть занято установкой или недоступно для входа. Дождитесь завершения установки и повторите проверку. Переустановка не запускалась; действующие сессии не изменены.", "Could not check the selected environment. It may be busy installing or unavailable for login. Wait for installation to finish and retry. No reinstall was started; running sessions are unchanged.") else tr(c, "Не хватает компонентов:", "Missing components:")
        if (!p.missing.isEmpty()) {
            body += "\n• " + java.lang.String.join("\n• ", componentLabels(p, p.missing))
        }
        if (!p.ready.isEmpty()) {
            body += "\n\n" + tr(c, "Найдены: ", "Found: ") + java.lang.String.join(", ", componentLabels(p, p.ready))
        }
        return AgentEnvironments.label(p.environment) + (if (p.path.isEmpty()) "" else "\n" + p.path) + "\n\n" + body + "\n\n" + tr(c, "Это проверка компонентов, а не входа в аккаунт, доступности модели или оплаты. Установка недостающего предлагается при запуске сессии или пресета.", "This checks components, not account sign-in, model access or billing. Missing components can be installed when launching a session or preset.")
    }

    @JvmStatic
    fun check(a: Activity, p: WorkspaceStore.Session?, result: Consumer<Plan>) {
        check(a, p, SYSTEM, result)
    }

    @JvmStatic
    fun check(a: Activity, p: WorkspaceStore.Session?, runner: Runner, result: Consumer<Plan>) {
        if (!supports(p)) {
            val plan = Plan()
            plan.error = "UNSUPPORTED_ENVIRONMENT"
            result.accept(plan)
            return
        }
        val cancelled: BooleanArray = booleanArrayOf(false)
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle(tr(a, "Проверка среды запуска", "Launch environment check")).setMessage(tr(a, "Проверяем компоненты: ", "Checking components: ") + AgentEnvironments.label(p!!.codexEnvironment)).setNegativeButton(tr(a, "Отмена", "Cancel"), DialogInterface.OnClickListener { d, w -> cancelled[0] = true }).create()
        wait.setOnCancelListener(DialogInterface.OnCancelListener { d -> cancelled[0] = true })
        wait.show()
        val worker: ExecutorService = Executors.newSingleThreadExecutor()
        worker.execute(Runnable {
            var plan: Plan
            try {
                plan = runner.run(a, p)
            } catch (e: Exception) {
                plan = Plan()
                plan.error = "CHECK_FAILED"
            }
            val checked: Plan = plan
            a.runOnUiThread(Runnable {
                val active: Boolean = wait.isShowing() && !cancelled[0] && !a.isFinishing() && !a.isDestroyed()
                wait.dismiss()
                if (active) {
                    result.accept(checked)
                }
            })
            worker.shutdown()
        })
    }

    @JvmStatic
    fun beforeLaunch(a: Activity, p: WorkspaceStore.Session?, launch: Consumer<Boolean?>) {
        if (!supports(p)) {
            launch.accept(false)
            return
        }
        VertexConnection.applyDefaults(a, p)
        val captured: String = key(p)
        check(a, p, Consumer<Plan> { plan ->
            if (!captured.equals(key(p))) {
                return@Consumer
            }
            if (!plan.error.isEmpty()) {
                WorkspaceDialogs.builder(a).setTitle(tr(a, "Проверка среды запуска", "Launch environment check")).setMessage(summary(a, plan)).setPositiveButton(tr(a, "Назад", "Back"), null).show()
                return@Consumer
            }
            if (plan.ready()) {
                p!!.agentSetupAllowed = false
                launch.accept(plan.authNeeded)
                return@Consumer
            }
            WorkspaceDialogs.builder(a).setTitle(tr(a, "Подготовить среду запуска?", "Prepare the launch environment?")).setMessage(summary(a, plan) + "\n\n" + tr(a, "После подтверждения установим только недостающие пакеты Ubuntu/Termux и отдельный CLI выбранного агента. Если выбранная Ubuntu отсутствует, установим именно её независимо от других окружений. Миграция выполняется только отдельно по вашему выбору; существующие среды не заменяются. Ход установки — в терминале. Vertex потребует подтвердить вход Google Cloud в браузере. Облачный проект и оплату приложение не создаёт.", "After confirmation, install only missing Ubuntu/Termux packages and a private CLI for the selected agent. If the selected Ubuntu is absent, install that environment independently of other environments. Migration is a separate optional action; existing environments are not replaced. Progress is shown in the terminal. Vertex requires Google Cloud approval in your browser. The app does not create a cloud project or set up billing.")).setPositiveButton(tr(a, "Подготовить и запустить", "Prepare and launch"), DialogInterface.OnClickListener { d, w ->
                if (captured.equals(key(p))) {
                    p!!.agentSetupAllowed = true
                    launch.accept(true)
                }
            }).setNegativeButton(tr(a, "Назад", "Back"), null).show()
        })
    }
}
