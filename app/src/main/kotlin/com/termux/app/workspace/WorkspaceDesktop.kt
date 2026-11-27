package com.termux.app.workspace

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.BuildConfig
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.x11.DesktopPrograms
import com.termux.x11.RemoteDesktopView
import com.termux.x11.VirglInstaller
import com.termux.x11.X11Environment
import com.termux.x11.X11ExtraKeys
import com.termux.x11.X11Remote
import com.termux.x11.X11ScreenSettings
import com.termux.x11.X11StartupView
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/** Main-process compositor/controller. Native X11 and desktop children stay in :jobs_x11.*/
class WorkspaceDesktop {
    private lateinit var activity: TermuxActivity
    private val selected: Supplier<WorkspaceStore.Session?>
    private val visible: BooleanSupplier
    private lateinit var changed: Runnable
    private val restore: Runnable
    private val input: View?
    private val main: Handler = Handler(Looper.getMainLooper())
    private val calls: ExecutorService = Executors.newSingleThreadExecutor()
    private val graphical: MutableSet<String> = HashSet<String>()
    private val shownTargets: MutableSet<String> = HashSet<String>()
    private val installed: MutableMap<String, Boolean> = HashMap<String, Boolean>()
    private var remote: IBinder? = null
    private var bound: Boolean = false
    private var closed: Boolean = false
    private var polling: Boolean = false
    private var inFlight: Boolean = false
    private var launchCheckAcknowledged: Boolean = false
    private var display: RemoteDesktopView? = null
    private var preparation: X11StartupView? = null
    private var preparationProfile: WorkspaceStore.Session? = null
    private var preparationNeedsInstall: Boolean = false
    private var preparationCancelling: Boolean = false
    private var preparationLaunching: Boolean = false
    private var preparationPrompt: AlertDialog? = null
    private var preparationRenderer: String = ""
    private var rendererProgressText: String? = ""
    private var rendererLaunchAfterInstall: Boolean = false
    private var preparationGpu: Boolean = false
    private var rendererPreparationFailed: Boolean = false
    private var rendererReadyForPreparation: Boolean = false
    private var rendererProgress: Int = -1
    private var rendererEpoch: Int = 0
    private var rendererCancel: Runnable? = null
    private var displayed: String = ""
    private var pending: String = ""
    private var pendingProgram: String = ""
    private var pendingProfile: WorkspaceStore.Session? = null
    private var progressOrigin: WorkspaceStore.Session? = null
    private var launchAfterCheck: WorkspaceStore.Session? = null
    private var state: Bundle = Bundle()
    private var diagnosticState: Bundle = Bundle()
    private var diagnosticAt: Long = 0L

    fun sessionDiagnostic(p: WorkspaceStore.Session?): String {
        val b: Bundle = if (diagnosticState.isEmpty()) state else diagnosticState
        val text: StringBuilder = StringBuilder("X11 transport\nTarget: ").append(target(p)).append("\nDesktop profile: ").append(desktop(p)).append("\nSelected renderer: ").append(renderer(p))
        if (b.isEmpty()) {
            return text.append("\nSnapshot: unavailable").toString()
        }
        text.append("\nRuntime renderer: ").append(b.getString("renderer", "unknown")).append("\nPhase: ").append(b.getString("phase", "unknown"))
        for (k in arrayOf<String>("started", "ready", "desktopReady", "desktopStartupTimedOut", "rendererConnected", "hostAttached")) {
            text.append('\n').append(k).append(": ").append(b.getBoolean(k, false))
        }
        text.append("\nDesktop child exit: ").append(b.getInt("desktopChildExit", -1)).append("\nGuardian exit: ").append(b.getInt("desktopGuardianExit", -1)).append("\nExit -1: not observed; guardian 137 alone does not identify the cause")
        text.append("\nComponent operation: ").append(b.getString("componentOperation", "unknown")).append("\nComponent result: ").append(b.getString("componentResult", "not-started"))
        text.append("\nComponent stage: ").append(b.getString("installStage", "unknown"))
        text.append("\nComponent child exit: ").append(b.getInt("componentChildExit", -1)).append("\nComponent guardian exit: ").append(b.getInt("componentGuardianExit", -1))
        text.append("\nComponent categories (raw output omitted):\n").append(b.getString("componentDiagnostic", "unavailable"))
        text.append("\nStartup elapsed ms: ").append(b.getLong("startupElapsedMs", 0L))
        text.append("\nStartup step: ").append(b.getInt("startupStep", 0)).append("\nSnapshot age ms: ").append(Math.max(0L, SystemClock.elapsedRealtime() - diagnosticAt))
        text.append("\nStartup categories (raw output omitted):\n").append(b.getString("startupDiagnostic", "unavailable"))
        val failures: String = b.getString("componentErrors", "")
        if (!failures.isEmpty()) {
            text.append("\nRedacted installer transfer errors (review before sharing):\n").append(failures)
        }
        val previous: String = b.getString("previousStartupDiagnostic", "")
        if (!previous.isEmpty()) {
            text.append("\nPrevious startup categories:\n").append(previous)
        }
        return text.toString()
    }

    fun refreshDiagnostic(done: Runnable) {
        val binder: IBinder? = remote
        if (closed || binder == null) {
            done.run()
            return
        }
        calls.execute(Runnable {
            var answer: Bundle? = null
            try {
                answer = X11Remote.call(binder, X11Remote.command("state"))
            } catch (ignored: Exception) {
            }
            val result: Bundle? = answer
            main.post(Runnable {
                if (!closed && remote === binder && result != null) {
                    diagnosticState = result
                    diagnosticAt = SystemClock.elapsedRealtime()
                }
                done.run()
            })
        })
    }

    private var rendererRow: TextView? = null
    private var wrapperFolder: TextView? = null
    private var wrapperDriver: TextView? = null
    private var styleCheck: CheckBox? = null

    private fun styleEnabled(p: WorkspaceStore.Session?): Boolean {
        return styleSupported(p) && activity.getSharedPreferences("workspace-x11", 0).getBoolean(styleKey(p), false)
    }

    private fun migrateRetiredStyle() {
        val prefs: SharedPreferences = activity.getSharedPreferences("workspace-x11", 0)
        if (prefs.contains("touch-workspace")) {
            val update: SharedPreferences.Editor = prefs.edit()
            if (prefs.getBoolean("touch-workspace", false)) {
                update.putBoolean("termux-style", true)
            }
            update.remove("touch-workspace").apply()
        }
    }

    private val wrapper: WorkspaceDesktopWrapper

    private fun rendererBusy(): Boolean {
        return wrapper.busy() || preparationGpu || !preparationRenderer.isEmpty() && !rendererPreparationFailed || rendererSettingsBusy()
    }

    private fun rendererSettingsBusy(): Boolean {
        return state.getBoolean("started", false) || Arrays.asList("checking", "installing", "cancelling", "preparing", "stopping").contains(state.getString("phase", "")) || programs.busy()
    }

    private fun rendererBackendBusy(): Boolean {
        return remote == null || rendererSettingsBusy()
    }

    private fun styleBusy(): Boolean {
        return remote == null || rendererBusy()
    }

    private var progress: DesktopInstallUi? = null
    private lateinit var programs: WorkspaceDesktopPrograms
    private val keyboardState: DesktopKeyboardState = DesktopKeyboardState()
    private val fullscreen: DesktopFullscreen
    private val extraKeys: DesktopExtraKeys
    private var keyboardChanged: Runnable = Runnable {  }
    private val imeObserver: ViewTreeObserver.OnPreDrawListener = ViewTreeObserver.OnPreDrawListener(this::observeIme)

    private fun observeIme(): Boolean {
        if (!closed && Build.VERSION.SDK_INT >= 30 && hasSurface()) {
            if (keyboardState.visibility(display!!.imeVisible())) {
                display!!.keyboard(false)
                keyboardChanged.run()
            }
        }
        return true
    }

    fun onKeyboardChanged(callback: Runnable) {
        keyboardChanged = callback
    }

    fun keyboardEnabled(): Boolean {
        return keyboardState.enabled
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, binder: IBinder) {
            if (closed) {
                return
            }
            remote = binder
            val op: String = pending
            pending = ""
            if (!op.isEmpty()) {
                send(op, pendingProfile, pendingProgram)
            }
            pendingProfile = null
            pendingProgram = ""
            poll()
            sync()
        }

        override fun onServiceDisconnected(n: ComponentName) {
            if (closed) {
                return
            }
            if (bound) {
                activity.unbindService(this)
                bound = false
            }
            remote = null
            launchAfterCheck = null
            clearPreparation()
            state = Bundle()
            installed.clear()
            graphical.clear()
            shownTargets.clear()
            dismissProgress()
            programs.failed()
            sync()
            changed.run()
            activity.showToast(tr("X11 остановлен. Терминальная сессия продолжает работу.", "X11 stopped. The terminal session is still running."), true)
        }

        override fun onBindingDied(n: ComponentName) {
            onServiceDisconnected(n)
            if (bound) {
                activity.unbindService(this)
                bound = false
            }
        }
    }

    constructor(a: TermuxActivity, s: Supplier<WorkspaceStore.Session?>, v: BooleanSupplier, input: View?, changed: Runnable, restore: Runnable) {
        activity = a
        selected = s
        visible = v
        this.input = input
        this.changed = changed
        this.restore = restore
        extraKeys = DesktopExtraKeys(a, Runnable { keyboardChanged.run() })
        fullscreen = DesktopFullscreen(a, Runnable(this::keyboard), BooleanSupplier(this::keyboardEnabled), Runnable(this::observeIme), Runnable(this::extraKeys), BooleanSupplier(this::extraKeysVisible))
        migrateRetiredStyle()
        wrapper = WorkspaceDesktopWrapper(a, if (WorkspaceDesktopWrapper.bridgeSupported(target(s.get()))) target(s.get()) else "termux")
        programs = WorkspaceDesktopPrograms(a, BiConsumer<String, String> { op, id -> ensure(op, programsProfile(), id) }, Runnable {  }, Consumer<String>(this::maintenance))
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(imeObserver)
        }
    }

    private fun programsProfile(): WorkspaceStore.Session? {
        return programs.profile()
    }

    private fun supported(p: WorkspaceStore.Session?): Boolean {
        return DesktopPrograms.supportsTarget(target(p))
    }

    private fun desktop(p: WorkspaceStore.Session?): String {
        return "xfce"
    }

    private fun renderer(p: WorkspaceStore.Session?): String {
        return WorkspaceDesktopGpu.mode(activity, target(p))
    }

    fun label(): String {
        return if (showing()) tr("Рабочий стол", "Desktop") else tr("Терминал", "Terminal")
    }

    fun hasSurface(): Boolean {
        return display != null && showing()
    }

    fun showing(): Boolean {
        val p: WorkspaceStore.Session? = selected.get()
        return visible.getAsBoolean() && p != null && graphical.contains(p.id) && eligible(p)
    }

    fun menu() {
        val p: WorkspaceStore.Session? = selected.get()
        if (!eligible(p)) {
            return
        }
        if (!supported(p)) {
            WorkspaceDialogs.builder(activity).setTitle("X11 · " + WorkspaceEnvironments.label(target(p))).setMessage(tr("Рабочий стол доступен для Termux, Ubuntu, Debian, Alpine, Fedora, openSUSE, Void Linux и Arch Linux. Другие системы будут подключаться к своим рабочим столам, без перенаправления в Ubuntu.", "The desktop is available for Termux, Ubuntu, Debian, Alpine, Fedora, openSUSE, Void Linux and Arch Linux. Other systems will use their own desktops, without redirecting to Ubuntu.")).setPositiveButton("OK", null).show()
            return
        }
        if (remote == null) {
            ensure("state", p)
        }
        val items: MutableList<String> = ArrayList<String>()
        val actions: MutableList<Int> = ArrayList<Int>()
        val rowKeys: MutableList<String> = ArrayList<String>()
        items.add(if (showing()) tr("Вернуться в терминал", "Return to terminal") else tr("Переключиться на рабочий стол", "Switch to desktop"))
        actions.add(0)
        rowKeys.add("desktop-switch")
        if (target(p).equals(state.getString("target", "")) && state.getBoolean("started", false)) {
            items.add(tr("Остановить графический рабочий стол", "Stop graphical desktop"))
            actions.add(1)
            rowKeys.add("desktop-stop")
        }
        val body: LinearLayout = AgentStatusUi.body(activity)
        val phase: String = state.getString("phase", "")
        if (target(p).equals(state.getString("target", ""))) {
            if (phase.equals("missing") || phase.equals("error") || phase.equals("cancelled")) {
                AgentStatusUi.info(body, state.getString("status", ""))
            }
        }
        val dialog = arrayOfNulls<AlertDialog>(1)

        styleCheck = null
        rendererRow = null
        wrapperFolder = null
        wrapperDriver = null
        var i = 0
        while (i < items.size) {
            val which: Int = actions.get(i)
            val row: TextView = AgentStatusUi.row(body, items.get(i), rowKeys.get(i), Runnable {
                dialog[0]!!.dismiss()
                menuAction(p, which)
            })
            if (i == 0 && styleSupported(p)) {
                val styleRow = LinearLayout(activity)
                styleRow.setGravity(Gravity.CENTER_VERTICAL)
                val label = TextView(activity)
                label.setText("Стиль Termux-job's")
                if (!WorkspaceLanguage.code(activity)!!.equals("ru")) {
                    label.setText("Termux-job's style")
                }
                label.setTextColor(Pal.TEXT)
                label.setTextSize(13.0f)
                styleRow.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
                val check = CheckBox(activity)
                styleCheck = check
                check.setTag("desktop-termux-style")
                check.setContentDescription(label.getText())
                check.setChecked(styleEnabled(p))
                check.setEnabled(!styleBusy())
                styleRow.addView(check)
                body.addView(styleRow)
                check.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { button, value ->
                    if (styleBusy()) {
                        check.setChecked(styleEnabled(p))
                        return@OnCheckedChangeListener
                    }
                    activity.getSharedPreferences("workspace-x11", 0).edit().putBoolean(styleKey(p), value).apply()
                })
                label.setOnClickListener(View.OnClickListener { v ->
                    if (check.isEnabled()) {
                        check.performClick()
                    }
                })
                AgentStatusUi.info(body, tr("Kali-стиль: тёмные окна, синие акценты, крупные значки и фон с драконом. Применяется при следующем запуске. Для смены сначала остановите рабочий стол.", "Kali-inspired style: dark windows, blue accents, large icons and dragon wallpaper. Applied on next launch. Stop the desktop before changing this setting."))
            }
            i++
        }
        addX11Renderer(body, p, dialog)
        addX11Screen(body, p, dialog)
        AgentStatusUi.info(body, tr("Поверните телефон горизонтально: рабочий стол займёт весь экран. Поворот обратно или «Назад» вернёт его в окно приложения. Кнопка интерактивного ввода | доступна справа сверху.", "Rotate the phone to landscape for a full-screen desktop. Rotate back or press Back to return it to the app window. The Interactive input | button remains at the top right."))
        val keyboardRow = LinearLayout(activity)
        keyboardRow.setGravity(Gravity.CENTER_VERTICAL)
        val keyboardLabel = TextView(activity)
        keyboardLabel.setText(tr("Автоматическая клавиатура", "Automatic keyboard"))
        keyboardLabel.setTextColor(Pal.TEXT)
        keyboardLabel.setTextSize(13.0f)
        keyboardRow.addView(keyboardLabel, LinearLayout.LayoutParams(0, -2, 1.0f))
        val automatic = CheckBox(activity)
        automatic.setTag("desktop-automatic-keyboard")
        automatic.setContentDescription(keyboardLabel.getText())
        automatic.setChecked(automaticKeyboard(p))
        keyboardRow.addView(automatic)
        body.addView(keyboardRow)
        automatic.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { button, checked ->
            activity.getSharedPreferences("workspace-x11", 0).edit().putBoolean("automatic-keyboard:" + target(p), checked).apply()
            keyboardState.automatic = checked
        })
        keyboardLabel.setOnClickListener(View.OnClickListener { v -> automatic.performClick() })
        val keyboardHelp: TextView = AgentStatusUi.info(body, tr("Показывать клавиатуру автоматически при выборе поля ввода, где это поддерживается. Если выключено — только вручную через «Интерактивный ввод» | в верхней панели. Ручной вызов доступен всегда.", "Show the keyboard automatically when a supported text field is selected. When disabled, open it manually using Interactive input | in the top toolbar. Manual input is always available."))
        keyboardHelp.setTag("desktop-automatic-keyboard-help")
        val help = SpannableString(keyboardHelp.getText())
        val inputIcon: Int = help.toString().indexOf('|')
        val inputGlyph: Drawable = activity.getDrawable(R.drawable.workspace_interactive_input)!!.mutate()
        inputGlyph.setTint(Pal.MUTED)
        val glyphSize: Int = WorkspaceDialogs.dp(activity, 16.0f)
        inputGlyph.setBounds(0, 0, glyphSize, glyphSize)
        if (inputIcon >= 0) {
            help.setSpan(ImageSpan(inputGlyph, DynamicDrawableSpan.ALIGN_BASELINE), inputIcon, inputIcon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        keyboardHelp.setText(help)
        dialog[0] = WorkspaceDialogs.builder(activity).setTitle("X11 · " + WorkspaceEnvironments.label(target(p))).setView(AgentStatusUi.scroll(body)).setNegativeButton(tr("Назад", "Back"), null).create()
        dialog[0]!!.show()
    }

    private fun addX11Screen(body: LinearLayout, p: WorkspaceStore.Session?, dialog: Array<AlertDialog?>) {
        AgentStatusUi.row(body, WorkspaceDesktopScreen.label(activity, target(p)), "desktop-screen", Runnable {
            dialog[0]!!.dismiss()
            WorkspaceDesktopScreen.show(activity, target(p), Runnable {
                sync()
                if (!closed && sameSession(p, selected.get())) {
                    menu()
                }
            }, Runnable {
                if (!closed && sameSession(p, selected.get())) {
                    menu()
                }
            }, WorkspaceDesktopScreen.Apply { settings, done -> applyScreen(p, settings, done) })
        })
    }

    private fun applyScreen(p: WorkspaceStore.Session?, settings: X11ScreenSettings, done: Consumer<String?>) {
        val binder: IBinder? = remote
        if (binder == null) {
            done.accept(null)
            return
        }
        val request: String = UUID.randomUUID().toString()
        val deadline: Long = SystemClock.elapsedRealtime() + 60000
        screenApply(binder, p, settings, request, deadline, true, done)
    }

    private fun screenApply(binder: IBinder, p: WorkspaceStore.Session?, settings: X11ScreenSettings, request: String, deadline: Long, first: Boolean, done: Consumer<String?>) {
        if (closed || binder !== remote) {
            done.accept(tr("Служба X11 отключилась. Повторите применение.", "X11 disconnected. Apply again."))
            return
        }
        calls.execute(Runnable {
            var answer: Bundle? = null
            var failed = false
            try {
                val current: Bundle = X11Remote.call(binder, X11Remote.command("state"))
                if (first && (!current.getBoolean("started", false) || settings.scalePercent == current.getInt("interfaceScale", 100))) {
                    val result = Bundle()
                    result.putBoolean("noScaleNeeded", true)
                    answer = result
                } else if (first) {
                    val q: Bundle = X11Remote.command("screen-scale")
                    q.putString("target", target(p))
                    q.putInt("interfaceScale", settings.scalePercent)
                    q.putString("request", request)
                    answer = X11Remote.call(binder, q)
                } else {
                    answer = current
                }
            } catch (ignored: Exception) {
                failed = true
            }
            val result: Bundle? = answer
            val error: Boolean = failed
            main.post(Runnable {
                if (closed || binder !== remote || error || result == null) {
                    done.accept(tr("Не удалось применить масштаб. Дождитесь запуска X11 и повторите.", "Could not apply scale. Wait for X11 startup and retry."))
                    return@Runnable
                }
                if (result.getBoolean("noScaleNeeded", false)) {
                    done.accept(null)
                    return@Runnable
                }
                val scale: Bundle? = result.getBundle("screenScale")
                if (scale == null || !request.equals(scale.getString("request", ""))) {
                    done.accept(tr("Запрос масштаба не подтверждён. Повторите.", "Scale request was not acknowledged. Retry."))
                    return@Runnable
                }
                if (!scale.getBoolean("pending", false)) {
                    done.accept(if (scale.getBoolean("success", false)) null else tr("Масштаб не применён. Текущие настройки сохранены; повторите после запуска стола.", "Scale was not applied. Current settings are preserved; retry after desktop startup."))
                    return@Runnable
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    done.accept(tr("Применение заняло слишком много времени. Проверьте состояние X11 и повторите.", "Applying took too long. Check X11 and retry."))
                    return@Runnable
                }
                main.postDelayed(Runnable { screenApply(binder, p, settings, request, deadline, false, done) }, 250L)
            })
        })
    }

    private fun addX11Renderer(body: LinearLayout, p: WorkspaceStore.Session?, dialog: Array<AlertDialog?>) {
        rendererRow = AgentStatusUi.row(body, tr("Рендеринг: ", "Rendering: ") + WorkspaceDesktopGpu.label(activity, target(p)), "desktop-renderer", Runnable {
            if (rendererBusy()) {
                return@Runnable
            }
            dialog[0]!!.dismiss()
            WorkspaceDesktopGpu.select(activity, target(p), BooleanSupplier(this::rendererBusy), Consumer<String> { choice -> selectRenderer(p, choice) }, Runnable(this::menu))
        })
        if (WorkspaceDesktopGpu.mode(activity, target(p)).equals("turnip")) {
            AgentStatusUi.info(body, if (target(p).equals("termux")) "Termux package build. Turnip: Mesa 26.2.5; Zink uses the installed Termux Mesa." else "Mesa 26.2.5 built for AgentDroid with lfdevs Android adaptations.")
        }
        if (target(p).equals("termux") && WorkspaceDesktopGpu.mode(activity, "termux").equals("wrapper") || WorkspaceDesktopWrapper.bridgeSupported(target(p)) && WorkspaceDesktopGpu.mode(activity, target(p)).equals("bionic-glibc")) {
            body.removeView(rendererRow)
            val renderLine = LinearLayout(activity)
            renderLine.setGravity(Gravity.CENTER_VERTICAL)
            renderLine.addView(rendererRow, LinearLayout.LayoutParams(0, -2, 1.0f))
            wrapperFolder = WorkspaceDialogs.action(activity, "", 0)
            wrapperFolder!!.setTag("desktop-wrapper-folder")
            wrapperFolder!!.setCompoundDrawablesWithIntrinsicBounds(R.drawable.workspace_folder, 0, 0, 0)
            wrapperFolder!!.setGravity(Gravity.CENTER)
            wrapperFolder!!.setMinHeight(WorkspaceDialogs.dp(activity, 48.0f))
            wrapperFolder!!.setContentDescription(tr("Выбрать ZIP драйвера Bionic", "Choose Bionic driver ZIP"))
            val folderParams = LinearLayout.LayoutParams(WorkspaceDialogs.dp(activity, 52.0f), WorkspaceDialogs.dp(activity, 48.0f))
            folderParams.leftMargin = WorkspaceDialogs.dp(activity, 6.0f)
            renderLine.addView(wrapperFolder, folderParams)
            body.addView(renderLine)
            wrapperFolder!!.setOnClickListener(View.OnClickListener { v ->
                if (rendererBusy()) {
                    return@OnClickListener
                }
                dialog[0]!!.dismiss()
                wrapper.choose(BooleanSupplier { closed || rendererBusy() || !sameSession(p, selected.get()) }, Runnable {
                    if (!closed && sameSession(p, selected.get()) && visible.getAsBoolean()) {
                        menu()
                    }
                })
            })
            AgentStatusUi.available(wrapperFolder, !rendererBusy())
            AgentStatusUi.info(body, "Bionic Turnip or Qualcomm drivers (AdrenoTools ZIP format).")
            val driver: String = WorkspaceDesktopWrapper.driverLabel(activity, target(p))
            wrapperDriver = AgentStatusUi.row(body, if (driver.isEmpty()) tr("Выбрать драйвер Bionic", "Choose Bionic driver") else driver, "desktop-wrapper-driver", Runnable {
                if (rendererBusy()) {
                    return@Runnable
                }
                dialog[0]!!.dismiss()
                wrapper.showDrivers(BooleanSupplier { closed || rendererBusy() || !sameSession(p, selected.get()) }, Runnable {
                    if (!closed && sameSession(p, selected.get()) && visible.getAsBoolean()) {
                        menu()
                    }
                })
            })
            AgentStatusUi.available(wrapperDriver, !rendererBusy())
        }
        AgentStatusUi.available(rendererRow, !rendererBusy())
    }

    private fun maintenance(operation: String) {
        val p: WorkspaceStore.Session? = programs.profile()
        if (p == null || !DesktopPrograms.supportsTarget(target(p))) {
            return
        }
        if (operation.equals("repair-launchers") && state.getBoolean("started", false) || programs.busy() || Arrays.asList("checking", "installing", "cancelling", "preparing", "stopping").contains(state.getString("phase", ""))) {
            activity.showToast(tr("Сначала остановите рабочий стол и дождитесь окончания операции.", "Stop the desktop and finish the current operation first."), true)
            return
        }
        if (operation.equals("firefox-dns-enable") || operation.equals("firefox-dns-disable")) {
            if (!DesktopPrograms.supportsFirefox(target(p))) {
                return
            }
            val enable: Boolean = operation.endsWith("-enable")
            val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Firefox · DNS через HTTPS", "Firefox · DNS over HTTPS")).setMessage(tr("Закройте все окна Firefox. Настройка действует только на его профили в этом окружении. Запросы имён сайтов будут отправляться Cloudflare по HTTPS (mozilla.cloudflare-dns.com); при недоступности используется обычный DNS. Системный DNS и проверка сертификатов не меняются. Прежние user.js сохраняются для отключения; изменённые вручную файлы не перезаписываются. Сначала хотя бы раз запустите Firefox, чтобы был создан профиль.", "Close all Firefox windows. This affects only its profiles in this environment. Site-name queries go to Cloudflare over HTTPS (mozilla.cloudflare-dns.com), with ordinary DNS fallback. System DNS and certificate checks stay unchanged. Original user.js files are saved for disabling; local edits are preserved. Start Firefox once first to create its profile.")).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(if (enable) tr("Включить", "Enable") else tr("Отключить", "Disable"), DialogInterface.OnClickListener { d, w -> programs.setDns(enable) }).create()
            confirm.setCanceledOnTouchOutside(false)
            confirm.show()
            return
        }
        if (operation.equals("firefox-compat-enable") || operation.equals("firefox-compat-disable")) {
            val enable: Boolean = operation.endsWith("-enable")
            val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Firefox · Совместимость с PRoot", "Firefox · PRoot compatibility")).setMessage(tr("Сначала установите Firefox и закройте его окна. Режим устраняет проверенную ошибку общей памяти в Android/PRoot, но ослабляет защиту обмена памятью между процессами Firefox (seals). Песочница целиком не отключается. Изменение действует только для Firefox, запускаемого из меню рабочего стола. Рабочий стол останавливать не требуется. Можно отключить здесь же; собственные изменения ярлыка не перезаписываются.", "Install Firefox and close its windows first. This mode works around the confirmed Android/PRoot shared-memory error, but weakens Firefox inter-process shared-memory protection (seals). It does not disable the entire sandbox. It applies only to Firefox launched from the desktop menu. The desktop can keep running. Disable it here; local launcher edits are never overwritten.")).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(if (enable) tr("Включить", "Enable") else tr("Отключить", "Disable"), DialogInterface.OnClickListener { d, w -> programs.setCompatibility(enable) }).create()
            confirm.setCanceledOnTouchOutside(false)
            confirm.show()
            return
        }
        if (!operation.equals("repair-launchers")) {
            return
        }
        val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Исправить права ярлыков?", "Repair launcher permissions?")).setMessage(tr("В выбранном окружении файлам .desktop, совпавшим с контрольными суммами установленных пакетов, будет добавлено чтение и запись для владельца. Исходные права сохраняются в ~/.local/state/termux-jobs/launcher-permissions.json.", "Add owner read/write permission to .desktop files matching installed package checksums in the selected environment. Original permissions are saved in ~/.local/state/termux-jobs/launcher-permissions.json.")).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(tr("Исправить", "Repair"), DialogInterface.OnClickListener { d, w -> startMaintenance(operation, p) }).create()
        confirm.setCanceledOnTouchOutside(false)
        confirm.show()
    }

    private fun startMaintenance(operation: String, p: WorkspaceStore.Session) {
        programs.close()
        maintenanceReturn = true
        ensure(operation, p)
    }

    private var maintenanceReturn: Boolean = false

    fun owns(p: WorkspaceStore.Session?): Boolean {
        val owners: ArrayList<String>? = state.getStringArrayList("desktopOwners")
        return p != null && !owner(p).isEmpty() && (if (owners == null || owners.isEmpty()) owner(p).equals(state.getString("desktopOwner", "")) else owners.size == 1 && owners.contains(owner(p))) && target(p).equals(state.getString("target", "")) && state.getBoolean("started", false)
    }

    fun sessionClosed(p: WorkspaceStore.Session?) {
        if (p == null) {
            return
        }
        if (launchAfterCheck != null && p.id.equals(launchAfterCheck!!.id) && p.handle.equals(launchAfterCheck!!.handle)) {
            launchAfterCheck = null
        }
        if (sameSession(p, preparationProfile)) {
            clearPreparation()
        }
        graphical.remove(p.id)
        sync()
        if (pending.equals("launch") && owner(p).equals(owner(pendingProfile))) {
            pending = ""
            pendingProfile = null
            pendingProgram = ""
        }
        // The service checks owner+handle+environment; a delayed close cannot stop another desktop.
        // Send even before launch acknowledgement so queued launch/close retain executor ordering.
        if (remote != null && !owner(p).isEmpty() && eligible(p)) {
            send("session-close", p)
        }
    }

    private fun diagnosticText(): String {
        return "AgentDroid X11 · " + BuildConfig.VERSION_NAME + " / " + BuildConfig.VERSION_CODE + "\nTarget=" + state.getString("target", "") + "\nDesktop=" + state.getString("desktop", "xfce") + "\nGarconFilter=" + state.getBoolean("garconFilter", true) + "\n\n" + tr("Запись X11 запускается и останавливается кнопкой в диагностике, без ограничения тремя минутами. popup-new/destroy — впервые замеченные и уничтоженные всплывающие окна; popup-map/hide — показ и скрытие прежних; focus-change — смена X11-фокуса. Popup также может быть подсказкой. content-update считает обновления содержимого всплывающих окон через XDamage; pointer-position-change — изменения положения указателя между опросами. GTK file-events — уведомления GIO о файлах; changed/done/attribute/created/deleted показывают тип, menu/desktop/directory/other — категорию; garcon-tagged — мониторы с меткой Garcon (не все его мониторы имеют метку). last-file-id — хэш пути без его раскрытия. Эти события не подавляются. GTK draw — рисование меню; add/remove — добавление и удаление пунктов; reload — запрос перезагрузки Garcon; enter/leave/motion — события указателя. Начальное подключение XDamage тоже создаёт событие. Если damage=unavailable, обновления содержимого не измеряются. BACKDROP показывает рабочую область, доступность декодирования фона и применение запасного фона. Текст ввода, заголовки окон и изображения наблюдатель не записывает.", "Start and stop X11 recording using the diagnostic button, without a three-minute expiry. popup-new/destroy: first-seen/destroyed popup windows; popup-map/hide: showing/hiding existing ones; focus-change: X11 focus change. Popups can also be tooltips. content-update counts popup content changes through XDamage; pointer-position-change counts position changes between samples. GTK file-events: GIO file notifications, split by event and category. garcon-tagged: monitors carrying Garcon metadata (not all Garcon monitors do). last-file-id: path hash without disclosure. Events are not suppressed. GTK draw: menu drawing; add/remove: menu item insertion/removal; reload: Garcon reload request; enter/leave/motion: pointer events. Initial XDamage attachment also emits a notification. If damage=unavailable, content changes are not measured. BACKDROP shows workspace, image decoder status and fallback application. The observer records no input text, window titles or images.") + "\n\nPointer adapter start: " + state.getString("pointerBaseline", "unavailable") + "\nPointer adapter now: " + state.getString("pointerDiagnostic", "unavailable") + "\nRecording=" + state.getBoolean("observationActive", false) + " pending=" + state.getBoolean("observationPending", false) + " serial=" + state.getInt("observationSerial", 0) + " " + state.getString("observationError", "") + "\n\nLAST OBSERVED BACKDROP\n" + state.getString("currentBackdrop", "") + "\nCAPTURE\n" + state.getString("observationLog", "") + "\nSTARTUP SNAPSHOT\n" + state.getString("desktopMetadata", "") + "\n" + state.getString("detail", "") + "\n\n" + tr("Текстовый контекст Fcitx: ", "Fcitx text context: ") + (if (state.getBoolean("textFocus", false)) tr("обнаружен", "detected") else tr("не обнаружен", "not detected")) + " · " + state.getLong("focusSerial", 0L) + "\nXFCE ready=" + state.getBoolean("desktopReady", false) + (if (Build.VERSION.SDK_INT < 30 || display == null) "" else "\nIME " + display!!.keyboardDiagnostic())
    }

    private fun diagnostics() {
        DiagnosticsPanel.show(activity, object : DiagnosticsPanel.Source {
            override fun title(): String {
                return tr("Диагностика X11", "X11 diagnostics")
            }

            override fun summary(): String {
                return "X11 · " + BuildConfig.VERSION_NAME + "\n" + (if (state.getBoolean("observationPending", false)) tr("Изменение записи…", "Changing recording…") else if (state.getBoolean("observationActive", false)) tr("Наблюдение включено", "Observation active") else tr("Наблюдение остановлено", "Observation stopped")) + "\n" + state.getString("observationError", "") + "\n" + tr("Окружение: ", "Environment: ") + state.getString("target", "") + "\n" + tr("Графический стол: ", "Desktop: ") + (if (state.getBoolean("started", false)) tr("запущен", "started") else tr("не запущен", "not started")) + " · " + state.getString("desktop", "xfce") + " ready=" + state.getBoolean("desktopReady", false) + "\nRenderer=" + state.getBoolean("rendererConnected", false) + " · Android host=" + state.getBoolean("hostAttached", false) + "\n" + tr("Текстовый контекст: ", "Text context: ") + (if (state.getBoolean("textFocus", false)) tr("обнаружен", "detected") else tr("не обнаружен", "not detected"))
            }

            override fun events(): String {
                val lines: Array<String> = ((state.getString("detail", "") + "\n" + state.getString("observationLog", "")) as java.lang.String).split("\n")
                val result = StringBuilder()
                for (i in lines.size - 1 downTo 0) {
                    if (!lines[i].isEmpty()) {
                        result.append(lines[i]).append('\n')
                    }
                }
                return result.toString()
            }

            override fun report(): String {
                return diagnosticText()
            }

            override fun id(): String {
                return "x11-desktop"
            }

            override fun bytes(): Int {
                return ((state.getString("detail", "") + state.getString("observationLog", "")) as java.lang.String).getBytes(StandardCharsets.UTF_8).size
            }

            override fun dropped(): Int {
                return 0
            }

            override fun capacity(): Int {
                return 40960
            }

            override fun capacitySummary(): String {
                return tr("Последний вывод X11; старые строки заменяются новыми", "Recent X11 output; old lines are replaced")
            }

            override fun paused(): Boolean {
                return !state.getBoolean("observationActive", false)
            }

            override fun canPause(): Boolean {
                return state.getBoolean("observationReady", false) && !state.getBoolean("observationPending", false)
            }

            override fun pauseLabel(): String {
                return if (state.getBoolean("observationPending", false)) tr("Подождите…", "Please wait…") else if (state.getBoolean("observationActive", false)) tr("Остановить наблюдение", "Stop observation") else tr("Начать наблюдение", "Start observation")
            }

            override fun paused(value: Boolean) {
                ensure(if (value) "observe-stop" else "observe-start", selected.get())
            }

            override fun prepareExport(action: Runnable) {
                activity.requestWorkspaceStorageAccess(action)
            }

            override fun privacy(): String {
                return tr("Новая запись заменяет предыдущую в памяти. Запись продолжается до остановки; закрытие окна её не прерывает. Полный отчёт включает состояние экрана и клавиатуры. Вывод программ может содержать пути. Отправка в сеть не выполняется.", "A new capture replaces the previous in-memory capture. Recording continues until stopped; closing this dialog does not stop it. Full report includes display and keyboard state. Program output may contain paths. No network upload.")
            }
        })
    }

    private fun menuAction(p: WorkspaceStore.Session?, which: Int) {
        val active: Boolean = target(p).equals(state.getString("target", "")) && state.getBoolean("started", false)
        if (which == 0 && !graphical.contains(p!!.id) && !sameSession(p, preparationProfile) && !active && !WorkspaceStorageAccess(activity).granted()) {
            val proceed = Runnable {
                if (!closed && sameSession(p, selected.get()) && visible.getAsBoolean()) {
                    menuActionReady(p, which)
                }
            }
            val prompt: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Доступ к памяти телефона", "Phone storage access")).setMessage(tr("Разрешить доступ к файлам телефона для рабочего стола? В файловом менеджере появятся Phone storage и Downloads. Можно выбрать «Пока нет»: рабочий стол запустится, но доступ к этим папкам будет зависеть от разрешений Android. При следующем запуске без разрешения спросим снова.", "Allow the desktop to access files on your phone? The file manager will include Phone storage and Downloads. Choose Not now to start without granting access. Android permissions still apply to these folders; we will ask again on the next desktop start if access is missing.")).setPositiveButton(tr("Разрешить", "Allow"), DialogInterface.OnClickListener { d, w -> activity.requestWorkspaceStorageAccess(proceed, proceed) }).setNegativeButton(tr("Пока нет", "Not now"), DialogInterface.OnClickListener { d, w -> proceed.run() }).create()
            prompt.setCanceledOnTouchOutside(false)
            prompt.show()
            return
        }
        menuActionReady(p, which)
    }

    private fun menuActionReady(p: WorkspaceStore.Session?, which: Int) {
        if (which == 0) {
            if (graphical.remove(p!!.id)) {
                sync()
                changed.run()
            } else if (sameSession(p, preparationProfile)) {
                graphical.add(p.id)
                sync()
                changed.run()
            } else if (target(p).equals(state.getString("target", "")) && state.getBoolean("started", false)) {
                graphical.add(p.id)
                ensure("launch", p)
            } else {
                preparationRenderer = renderer(p)
                rendererLaunchAfterInstall = true
                rendererPreparationFailed = false
                rendererReadyForPreparation = false
                launchCheckAcknowledged = false
                launchAfterCheck = p
                preparationProfile = p
                preparationNeedsInstall = false
                preparationCancelling = false
                graphical.add(p.id)
                sync()
                changed.run()
                ensure("check", p)
            }
        }
        if (which == 1) {
            WorkspaceDialogs.builder(activity).setMessage(tr("Остановить только графический рабочий стол? Терминальные сессии продолжат работу.", "Stop only the graphical desktop? Terminal sessions will keep running.")).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(tr("Остановить", "Stop"), DialogInterface.OnClickListener { x, y ->
                launchAfterCheck = null
                if (remote != null) {
                    send("stop", p)
                }
            }).show()
        }
    }

    fun openPrograms() {
        val p: WorkspaceStore.Session? = selected.get()
        if (!eligible(p) || !supported(p)) {
            return
        }
        programs.open(p, state.getBundle("programs"))
        poll()
    }

    fun programsVisible(): Boolean {
        return programs.visible()
    }

    fun programStatusPolling(): Boolean {
        return visible.getAsBoolean() && programsInstalling()
    }

    fun programsInstalling(): Boolean {
        val job: Bundle? = state.getBundle("programs")
        if (job == null) {
            return false
        }
        val p: WorkspaceStore.Session? = selected.get()
        if (p == null || !target(p).equals(job.getString("target", ""))) {
            return false
        }
        val phase: String = job.getString("phase", "")
        return phase.equals("installing") || phase.equals("cancelling")
    }

    private fun clearPreparation() {
        preparationProfile = null
        preparationNeedsInstall = false
        preparationCancelling = false
        preparationLaunching = false
        preparationRenderer = ""
        preparationGpu = false
        rendererLaunchAfterInstall = false
        rendererCancel = null
        rendererPreparationFailed = false
        rendererReadyForPreparation = false
        rendererEpoch++
        if (preparationPrompt != null) {
            preparationPrompt!!.dismiss()
            preparationPrompt = null
        }
    }

    private fun confirmInstall(p: WorkspaceStore.Session?) {
        preparationNeedsInstall = true
        if (preparationPrompt != null || !sameSession(p, selected.get()) || !visible.getAsBoolean()) {
            return
        }
        preparationPrompt = WorkspaceDialogs.builder(activity).setTitle(tr("Установить рабочий стол X11?", "Install X11 desktop?")).setMessage(tr("В выбранное окружение будут установлены XFCE, файловый менеджер, терминал, шрифты, значки, D-Bus, Fcitx5 и звук PulseAudio. Потребуются интернет и свободное место.", "XFCE, file manager, terminal, fonts, icons, D-Bus, Fcitx5 and PulseAudio will be installed in the selected environment. Internet and free space are required.") + "\n" + WorkspaceEnvironments.label(target(p)) + (if (target(p).equals("archlinux")) tr("\nArch Linux: будут проверены права корневой папки окружения; владельцу при необходимости добавятся чтение, запись и вход. Также обновятся пакеты этого окружения (pacman -Syu).", "\nArch Linux: the environment root directory will be checked and missing owner read/write/search permissions restored. This environment’s packages will also be upgraded (pacman -Syu).") else "")).setNegativeButton(tr("Нет", "No"), DialogInterface.OnClickListener { d, w ->
            preparationPrompt = null
            graphical.remove(p!!.id)
            clearPreparation()
            sync()
            changed.run()
        }).setPositiveButton(tr("Да", "Yes"), DialogInterface.OnClickListener { d, w ->
            preparationPrompt = null
            preparationNeedsInstall = false
            launchCheckAcknowledged = false
            launchAfterCheck = p
            if (!preparationRenderer.isEmpty()) {
                rendererLaunchAfterInstall = true
            }
            ensure("install", p)
            sync()
        }).create()
        preparationPrompt!!.setCancelable(false)
        preparationPrompt!!.setCanceledOnTouchOutside(false)
        preparationPrompt!!.show()
    }

    private fun selectRenderer(p: WorkspaceStore.Session?, choice: String) {
        if (rendererBusy() || closed) {
            return
        }
        if (!choice.equals("cpu") && !(choice.equals("turnip") && WorkspaceDesktopGpu.supported(target(p))) && !(choice.equals("virgl") && VirglInstaller.supported(target(p))) && !(choice.equals("wrapper") && WorkspaceDesktopWrapper.supported(target(p))) && !(choice.equals("bionic-glibc") && WorkspaceDesktopWrapper.bridgeSupported(target(p)))) {
            return
        }
        activity.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target(p), choice).apply()
        if (sameSession(p, selected.get()) && visible.getAsBoolean()) {
            menu()
        }
    }

    private fun prepareSelectedRenderer(p: WorkspaceStore.Session?) {
        val choice: String = preparationRenderer
        if (!choice.equals("cpu") && !WorkspaceDesktopGpu.installed(activity, target(p), choice) && (!sameSession(p, selected.get()) || !visible.getAsBoolean())) {
            rendererReadyForPreparation = true
            return
        }
        rendererReadyForPreparation = false
        val launch: Boolean = rendererLaunchAfterInstall
        val epoch: Int = ++rendererEpoch
        preparationGpu = true
        rendererPreparationFailed = false
        rendererProgress = -1
        rendererProgressText = tr("Проверка компонентов рендера…", "Checking renderer components…")
        preparationUpdate()
        val rendererUnavailable = BooleanSupplier { rendererBackendBusy() || closed || epoch != rendererEpoch }
        val rendererUpdated = Runnable {
            if (closed || epoch != rendererEpoch || !sameSession(p, preparationProfile)) {
                return@Runnable
            }
            rendererCancel = null
            preparationGpu = false
            if (rendererPreparationFailed) {
                preparationCancelling = false
                preparationLaunching = false
                sync()
                changed.run()
                return@Runnable
            }
            if (!renderer(p).equals(choice) || !choice.equals("cpu") && !WorkspaceDesktopGpu.installed(activity, target(p), choice)) {
                graphical.remove(p!!.id)
                clearPreparation()
                sync()
                changed.run()
                if (sameSession(p, selected.get()) && visible.getAsBoolean()) {
                    menu()
                }
                return@Runnable
            }
            preparationRenderer = ""
            if (launch) {
                preparationLaunching = true
                ensure("launch", p)
                sync()
                changed.run()
            } else {
                graphical.remove(p!!.id)
                clearPreparation()
                sync()
                changed.run()
                if (sameSession(p, selected.get()) && visible.getAsBoolean()) {
                    menu()
                }
            }
        }
        val inline: WorkspaceDesktopGpu.Inline = object : WorkspaceDesktopGpu.Inline {
            override fun started(cancel: Runnable?) {
                if (epoch != rendererEpoch || !sameSession(p, preparationProfile)) {
                    return
                }
                rendererCancel = cancel
                preparationUpdate()
            }

            override fun progress(percent: Int, text: String?) {
                if (closed || epoch != rendererEpoch) {
                    return
                }
                rendererProgress = percent
                rendererProgressText = text
                preparationUpdate()
            }

            override fun finished(success: Boolean, report: String) {
                if (closed || epoch != rendererEpoch) {
                    return
                }
                rendererPreparationFailed = !success
                rendererProgressText = if (success) tr("Рендер готов", "Renderer ready") else if (report.contains("GPU_CANCELLED")) tr("Установка рендера отменена", "Renderer installation cancelled") else tr("Не удалось подготовить рендер. ", "Could not prepare renderer. ") + report
            }
        }
        if (choice.equals("wrapper") || choice.equals("bionic-glibc")) {
            wrapper.prepare(rendererUnavailable, rendererUpdated, inline)
        } else {
            WorkspaceDesktopGpu.prepare(activity, target(p), choice, rendererUnavailable, rendererUpdated, inline, state.getBoolean("rendererComponents", false) && choice.equals(state.getString("renderer", "")) && target(p).equals(state.getString("target", "")))
        }
    }

    private fun preparationAction() {
        val p: WorkspaceStore.Session? = preparationProfile
        if (p == null) {
            return
        }
        if (rendererPreparationFailed && !preparationGpu) {
            graphical.remove(p.id)
            launchAfterCheck = null
            clearPreparation()
            sync()
            changed.run()
            return
        }
        if (preparationGpu) {
            if (rendererCancel != null) {
                preparationCancelling = true
                rendererCancel!!.run()
                preparationUpdate()
            }
            return
        }
        val phase: String = if (target(p).equals(state.getString("target", ""))) state.getString("phase", "") else ""
        if (preparationNeedsInstall || phase.equals("error") || phase.equals("cancelled") || phase.equals("missing") || phase.equals("stopped") || phase.equals("ended")) {
            graphical.remove(p.id)
            launchAfterCheck = null
            clearPreparation()
            sync()
            changed.run()
            return
        }
        preparationCancelling = true
        launchAfterCheck = null
        if (remote != null) {
            send(if (preparationLaunching || state.getBoolean("started", false) || phase.equals("running") || phase.equals("preparing")) "stop" else "cancel", p)
        } else {
            pending = ""
            pendingProfile = null
            graphical.remove(p.id)
            clearPreparation()
        }
        sync()
        changed.run()
    }

    private fun preparationUpdate() {
        if (preparation == null || preparationProfile == null) {
            return
        }
        val matches: Boolean = target(preparationProfile).equals(state.getString("target", ""))
        val phase: String = if (matches) state.getString("phase", "") else "checking"
        if (rendererPreparationFailed && !preparationGpu) {
            preparation!!.details(null)
            preparation!!.preparation(tr("Подготовка рендера", "Renderer preparation"), rendererProgressText, -1, "", tr("В терминал", "Return to terminal"), true, Runnable(this::preparationAction))
            preparation!!.preparationIdle()
            return
        }
        if (preparationGpu) {
            preparation!!.details(null)
            preparation!!.preparation(tr("Установка рендера", "Installing renderer"), rendererProgressText, rendererProgress, "", if (preparationCancelling) tr("Отменяется…", "Cancelling…") else tr("Отменить установку", "Cancel installation"), rendererCancel != null && !preparationCancelling, Runnable(this::preparationAction))
            return
        }
        if (matches && state.getBoolean("preparingExtras", false) && phase.equals("preparing")) {
            val progress: Int = state.getInt("installPercent", -1)
            val done: Long = state.getLong("downloadBytes", (-1).toLong())
            val total: Long = state.getLong("downloadTotal", (-1).toLong())
            val eta: Long = state.getLong("installEta", (-1).toLong())
            var text: String = tr("Проверка и загрузка темы Kali…", "Checking and downloading Kali theme…")
            if (total > 0 && done >= 0) {
                text += "\n" + formatBytes(done) + " / " + formatBytes(total)
            }
            if (eta > 0) {
                text += "\n" + tr("Осталось: ≈ ", "Remaining: ≈ ") + eta + tr(" с", " s")
            }
            preparation!!.details(null)
            preparation!!.preparation(tr("Подготовка темы Kali", "Preparing Kali theme"), text, progress, "", if (preparationCancelling) tr("Отменяется…", "Cancelling…") else tr("Отменить установку", "Cancel installation"), !preparationCancelling, Runnable(this::preparationAction))
            return
        }
        val installing: Boolean = phase.equals("installing") || phase.equals("cancelling") && state.getString("componentOperation", "").equals("install") || preparationNeedsInstall
        val finished: Boolean = phase.equals("error") || phase.equals("cancelled") || phase.equals("stopped") || phase.equals("ended")
        val heading: String = if (preparationLaunching) tr("Запуск рабочего стола", "Starting desktop") else if (installing) tr("Установка рабочего стола", "Installing desktop") else tr("Проверка рабочего стола", "Checking desktop")
        val percent: Int = if (installing && !preparationNeedsInstall) state.getInt("installPercent", -1) else -1
        var status: String = if (preparationNeedsInstall) tr("Требуется установка", "Installation required") else if (matches) state.getString("status", "") else tr("Подключение к службе X11…", "Connecting to X11 service…")
        if (installing && !preparationNeedsInstall && !preparationCancelling && !state.getString("installItem", "").isEmpty()) {
            val measuredStage: String = state.getString("installStage", "")
            if (measuredStage.equals("downloading")) {
                status = tr("Загрузка пакетов", "Downloading packages")
            } else if (measuredStage.equals("installing")) {
                status = tr("Установка пакетов", "Installing packages")
            }
        }
        if (percent >= 0) {
            status += " · " + percent + "%"
        }
        val done: Long = state.getLong("downloadBytes", (-1).toLong())
        val total: Long = state.getLong("downloadTotal", (-1).toLong())
        val stage: String = state.getString("installStage", "")
        if (installing && !preparationNeedsInstall && (stage.equals("downloading") || stage.equals("updating")) && done >= 0 && total > 0 && done <= total) {
            status += " (" + formatBytes(done) + " / " + formatBytes(total) + ")"
        }
        val eta: Long = state.getLong("installEta", (-1).toLong())
        val left: Long = eta - Math.max(0L, (SystemClock.elapsedRealtime() - state.getLong("installSampleTime", 0L)) / 1000)
        if (installing && !preparationNeedsInstall) {
            status += "\n" + tr("Осталось на этапе: ", "Remaining in this stage: ") + (if (eta < 0 || left <= 0) tr("рассчитывается", "calculating") else "≈ " + left + tr(" с", " s"))
        }
        val detail: String = if (matches) state.getString("detail", "") else ""
        var last: String = if (matches) state.getString("installItem", "") else ""
        if (last.isEmpty()) {
            last = DesktopProgramProgress.lastLine(detail)
        }
        if (finished) {
            for (line in (detail as java.lang.String).split("\n")) {
                val reason: String = (line as java.lang.String).trim()
                if ((reason as java.lang.String).matches("(?i).*(?:no match for argument|error:|failed to|curl error|timed? out|connection reset|could not resolve|cannot download).*")) {
                    last = reason
                    break
                }
            }
        }
        preparation!!.preparation(heading, status, percent, last, if (finished || preparationNeedsInstall) tr("В терминал", "Return to terminal") else if (preparationCancelling) tr("Отменяется…", "Cancelling…") else if (installing) tr("Отменить установку", "Cancel installation") else tr("Отмена", "Cancel"), !preparationCancelling, Runnable(this::preparationAction))
        preparation!!.details(if (finished && !(detail as java.lang.String).trim().isEmpty()) Runnable { preparationDetails(detail) } else null)
        if (finished || preparationNeedsInstall) {
            preparation!!.preparationIdle()
        }
    }

    private fun preparationDetails(text: String) {
        val body: LinearLayout = AgentStatusUi.body(activity)
        val log: TextView = AgentStatusUi.info(body, text)
        log.setTag("x11-install-full-log")
        log.setTextIsSelectable(true)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Журнал установки X11", "X11 installation log")).setView(AgentStatusUi.scroll(body)).setNegativeButton(tr("Назад", "Back"), null).setNeutralButton(tr("Копировать", "Copy"), null).create()
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v ->
            val clipboard: ClipboardManager? = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("X11", text))
                activity.showToast(tr("Скопировано", "Copied"), false)
            }
        })
    }

    private fun formatBytes(value: Long): String {
        var n: Double = value.toDouble()
        val units: Array<String> = arrayOf<String>("B", "kB", "MB", "GB")
        var i = 0
        while (n >= 1000 && i < 3) {
            n /= 1000
            i++
        }
        return java.lang.String.format(Locale.ROOT, "%.1f %s", n, units[i])
    }

    private fun ensure(op: String, p: WorkspaceStore.Session?) {
        ensure(op, p, "")
    }

    private fun ensure(op: String, p: WorkspaceStore.Session?, program: String) {
        if (!op.equals("repair-launchers") && !(op.equals("firefox-compat-on") || op.equals("firefox-compat-off")) && !op.startsWith("programs-")) {
            maintenanceReturn = false
        }
        if (Build.VERSION.SDK_INT < 30) {
            launchAfterCheck = null
            if (op.startsWith("programs-")) {
                programs.failed()
            }
            activity.showToast(tr("Встроенный вид рабочего стола требует Android 11 или новее.", "Inline desktop requires Android 11 or later."), true)
            return
        }
        if (op.equals("check") || op.equals("install") || op.equals("repair-launchers") || (op.equals("firefox-compat-on") || op.equals("firefox-compat-off"))) {
            progressOrigin = p
        }
        if (remote != null) {
            send(op, p, program)
            return
        }
        pending = op
        pendingProfile = p
        pendingProgram = program
        try {
            val intent: Intent = Intent(activity, X11Environment.forTarget(target(p)).service).putExtra("controlOnly", true)
            activity.startForegroundService(intent)
            if (!bound) {
                bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
            if (!bound) {
                throw IllegalStateException("Binding failed")
            }
        } catch (e: Exception) {
            pending = ""
            launchAfterCheck = null
            if (sameSession(p, preparationProfile)) {
                state = Bundle()
                state.putString("target", target(p))
                state.putString("phase", "error")
                state.putString("status", tr("Не удалось подключиться к X11", "Could not connect to X11"))
                sync()
                changed.run()
            }
            if (op.startsWith("programs-")) {
                programs.failed()
            }
            activity.showToast(tr("Не удалось запустить X11: ", "Could not start X11: ") + e.javaClass.getSimpleName(), true)
        }
    }

    private fun send(op: String, p: WorkspaceStore.Session?) {
        send(op, p, "")
    }

    private fun send(op: String, p: WorkspaceStore.Session?, program: String) {
        val binder: IBinder? = remote
        if (binder == null || closed) {
            return
        }
        val q: Bundle = X11Remote.command(op)
        if (p != null) {
            q.putString("target", target(p))
            q.putString("desktop", desktop(p))
            q.putBoolean("garconFilter", false)
            q.putBoolean("termuxStyle", styleEnabled(p))
            q.putInt("interfaceScale", X11ScreenSettings.load(activity, target(p)).scalePercent)
            q.putString("renderer", renderer(p))
            if (op.equals("launch") || op.equals("session-close")) {
                q.putString("owner", owner(p))
            }
        }
        if (!program.isEmpty()) {
            q.putString("program", program)
        }
        calls.execute(Runnable {
            try {
                val result: Bundle = X11Remote.call(binder, q)
                main.post(Runnable {
                    if (!closed && remote === binder) {
                        if ((op.equals("check") || op.equals("install")) && p != null && launchAfterCheck != null && p.id.equals(launchAfterCheck!!.id) && p.handle.equals(launchAfterCheck!!.handle)) {
                            launchCheckAcknowledged = true
                        }
                        accept(result)
                        poll()
                        sync()
                        changed.run()
                    }
                })
            } catch (e: Exception) {
                main.post(Runnable {
                    if (!closed && remote === binder) {
                        if (op.equals("session-close")) {
                            activity.showToast(tr("Не удалось подтвердить остановку X11. Проверьте рабочий стол и остановите его вручную.", "Could not confirm X11 stop. Check the desktop and stop it manually."), true)
                            return@Runnable
                        }
                        if (op.startsWith("observe-")) {
                            activity.showToast(tr("Не удалось изменить наблюдение X11. Повторите.", "Could not change X11 observation. Try again."), true)
                            poll()
                            return@Runnable
                        }
                        if (op.startsWith("programs-")) {
                            programs.failed()
                            activity.showToast(tr("Не удалось выполнить действие с программами. Дождитесь завершения другой установки и повторите проверку.", "Program action failed. Wait for another installation to finish and check again."), true)
                            return@Runnable
                        }
                        launchAfterCheck = null
                        if (sameSession(p, preparationProfile)) {
                            state = Bundle()
                            state.putString("target", target(p))
                            state.putString("phase", "error")
                            state.putString("status", tr("Не удалось выполнить действие X11. Вернитесь в терминал и повторите.", "X11 action failed. Return to terminal and retry."))
                            preparationCancelling = false
                            sync()
                            changed.run()
                            return@Runnable
                        }
                        if (op.equals("launch") && p != null) {
                            graphical.remove(p.id)
                        }
                        sync()
                        dismissProgress()
                        val origin: WorkspaceStore.Session? = progressOrigin
                        progressOrigin = null
                        val current: WorkspaceStore.Session? = selected.get()
                        if (origin != null && current != null && current.id.equals(origin.id)) {
                            menu()
                        }
                        activity.showToast(tr("Не удалось выполнить действие X11. Проверьте, не запущен ли стол другого окружения, или остановите X11 и повторите.", "X11 action failed. Check whether another environment owns the desktop, or stop X11 and retry."), true)
                        changed.run()
                    }
                })
            }
        })
    }

    private fun programPolling(): Boolean {
        return programs.polling()
    }

    private fun poll() {
        if (polling || closed || remote == null) {
            return
        }
        polling = true
        main.postDelayed(tick, 400L)
    }

    private val tick: Runnable = Runnable {
        polling = false
        if (closed || remote == null || inFlight) {
            return@Runnable
        }
        inFlight = true
        val binder: IBinder? = remote
        calls.execute(Runnable {
            var result: Bundle? = null
            try {
                result = X11Remote.call(binder, X11Remote.command("state"))
            } catch (ignored: Exception) {
            }
            val answer: Bundle? = result
            main.post(Runnable {
                inFlight = false
                if (closed || binder !== remote) {
                    return@Runnable
                }
                if (answer != null) {
                    accept(answer)
                    sync()
                }
                if (progress != null || showing() || launchAfterCheck != null || preparationCancelling || programPolling() || programStatusPolling()) {
                    poll()
                }
            })
        })
    }

    private fun accept(b: Bundle) {
        val hadProgress: Boolean = progress != null
        val programState: Bundle? = b.getBundle("programs")
        if (programState != null) {
            programs.accept(programState)
        }
        diagnosticState = b
        diagnosticAt = SystemClock.elapsedRealtime()
        state = b
        if (styleCheck != null) {
            styleCheck!!.setEnabled(!styleBusy())
            styleCheck!!.setAlpha(if (styleBusy()) 0.4f else 1f)
        }
        if (rendererRow != null) {
            AgentStatusUi.available(rendererRow, !rendererBusy())
        }
        if (wrapperFolder != null) {
            AgentStatusUi.available(wrapperFolder, !rendererBusy())
        }
        if (wrapperDriver != null) {
            AgentStatusUi.available(wrapperDriver, !rendererBusy())
        }
        val phase: String = b.getString("phase", "")
        val t: String = b.getString("target", "")
        if ((phase.equals("installed") || phase.equals("running")) && b.getString("desktop", "xfce").equals(desktop(selected.get()))) {
            installed.put(t, true)
        }
        if (phase.equals("missing") || phase.equals("error") || phase.equals("cancelled")) {
            installed.put(t, false)
        }
        val launch: WorkspaceStore.Session? = launchAfterCheck
        if (launch != null && launchCheckAcknowledged && target(launch).equals(t) && b.getBoolean("started", false) && (phase.equals("running") || phase.equals("preparing"))) {
            launchAfterCheck = null
            progressOrigin = null
            clearPreparation()
            graphical.add(launch.id)
            ensure("launch", launch)
            sync()
            changed.run()
            return
        }
        if (launch != null && launchCheckAcknowledged && target(launch).equals(t) && (phase.equals("installed") || phase.equals("missing") || phase.equals("error") || phase.equals("cancelled"))) {
            launchAfterCheck = null
            progressOrigin = null
            maintenanceReturn = false
            dismissProgress()
            if (phase.equals("installed")) {
                if (!preparationRenderer.isEmpty()) {
                    prepareSelectedRenderer(launch)
                    return
                }
                if (preparationProfile != null) {
                    preparationLaunching = true
                    preparationNeedsInstall = false
                } else {
                    graphical.add(launch.id)
                }
                ensure("launch", launch)
                changed.run()
                return
            }
            if (phase.equals("missing") && preparationProfile != null) {
                preparationNeedsInstall = true
            }
            val current: WorkspaceStore.Session? = selected.get()
            if (current != null && current.id.equals(launch.id) && current.handle.equals(launch.handle)) {
                if (phase.equals("missing")) {
                    confirmInstall(launch)
                } else if (preparationProfile == null) {
                    menu()
                }
            }
            return
        }
        if (preparationCancelling && preparationProfile != null && target(preparationProfile).equals(t) && b.getBoolean("started", false) && (phase.equals("running") || phase.equals("preparing"))) {
            send("stop", preparationProfile)
            preparationUpdate()
            return
        }
        if (preparationLaunching && preparationProfile != null && target(preparationProfile).equals(t) && (phase.equals("preparing") || phase.equals("running"))) {
            clearPreparation()
        }
        if (preparationProfile != null && target(preparationProfile).equals(t)) {
            // Inline installation belongs to this environment, even while its tab is hidden.
            if (phase.equals("cancelled") || phase.equals("error") || phase.equals("stopped") || phase.equals("ended")) {
                preparationCancelling = false
                preparationLaunching = false
            }
            preparationUpdate()
            return
        }
        val busy: Boolean = phase.equals("checking") || phase.equals("installing") || phase.equals("cancelling")
        if (busy) {
            if (progress == null) {
                progress = DesktopInstallUi(activity, "X11 · " + WorkspaceEnvironments.label(t), Runnable {
                    graphical.clear()
                    send("cancel", if (progressOrigin != null) progressOrigin else selected.get())
                    sync()
                })
            }
            progress!!.update(b.getString("status", ""), b.getString("detail", ""), b.getInt("installPercent", -1), b.getLong("installEta", (-1).toLong()), b.getLong("installSampleTime", 0L), b.getString("installStage", "preparing"), b.getLong("downloadBytes", (-1).toLong()), b.getLong("downloadTotal", (-1).toLong()))
        } else if (progress != null) {
            val returnMenu: Boolean = progressOrigin != null
            val origin: WorkspaceStore.Session? = progressOrigin
            progressOrigin = null
            val returnPrograms: Boolean = maintenanceReturn
            maintenanceReturn = false
            dismissProgress()
            changed.run()
            val current: WorkspaceStore.Session? = selected.get()
            if (returnMenu && current != null && current.id.equals(origin!!.id) && target(current).equals(t)) {
                if (returnPrograms) {
                    if (phase.equals("error") || phase.equals("missing")) {
                        WorkspaceDialogs.builder(activity).setTitle(tr("Операция не завершена", "Operation incomplete")).setMessage(b.getString("status", "") + "\n" + b.getString("detail", "")).setPositiveButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w ->
                            programs.openInstalled(origin, state.getBundle("programs"))
                            poll()
                        }).show()
                    } else {
                        activity.showToast(b.getString("status", ""), false)
                        programs.openInstalled(origin, state.getBundle("programs"))
                        poll()
                    }
                } else {
                    menu()
                }
            } else if (!returnMenu && (phase.equals("error") || phase.equals("missing") || phase.equals("cancelled"))) {
                WorkspaceDialogs.builder(activity).setTitle("X11").setMessage(b.getString("status", "") + "\n" + b.getString("detail", "")).setPositiveButton("OK", null).show()
            }
        }
        if (phase.equals("stopping") || phase.equals("error") || phase.equals("ended") || phase.equals("cancelled")) {
            val wasGraphical: Boolean = showing()
            graphical.clear()
            shownTargets.clear()
            sync()
            if (wasGraphical && !hadProgress && !phase.equals("stopping")) {
                WorkspaceDialogs.builder(activity).setTitle("X11").setMessage(b.getString("status", "") + "\n" + b.getString("detail", "")).setPositiveButton("OK", null).show()
            }
        }
        if (Build.VERSION.SDK_INT >= 30 && hasSurface() && display!!.inputReady() && target(selected.get()).equals(t)) {
            keyboardState.automatic = automaticKeyboard(selected.get())
            val before: Boolean = keyboardState.enabled
            var show: Boolean? = keyboardState.focus(b.getLong("focusSerial", 0L), b.getBoolean("textFocus", false))
            val tap: Boolean? = keyboardState.tap(b.getLong("fieldTapSerial", 0L), b.getBoolean("textFocus", false))
            if (tap != null) {
                show = tap
            }
            if (show != null) {
                display!!.keyboard(show)
            }
            if (before != keyboardState.enabled) {
                keyboardChanged.run()
            }
        }
    }

    private fun automaticKeyboard(p: WorkspaceStore.Session?): Boolean {
        return activity.getSharedPreferences("workspace-x11", 0).getBoolean("automatic-keyboard:" + target(p), true)
    }

    fun desktopReady(target: String): Boolean {
        return target.equals(state.getString("target", "")) && state.getBoolean("ready", false) && (state.getBoolean("desktopReady", false) || shownTargets.contains(target))
    }

    fun sync() {
        if (Build.VERSION.SDK_INT < 30 || closed) {
            return
        }
        if (programStatusPolling()) {
            poll()
        }
        val p: WorkspaceStore.Session? = selected.get()
        val inline: Boolean = preparationProfile != null && sameSession(p, preparationProfile) && showing()
        val show: Boolean = !inline && showing() && remote != null && (state.getBoolean("ready", false) || state.getString("phase", "").equals("preparing")) && target(p).equals(state.getString("target", ""))
        val wanted: String = if (show) p!!.id else ""
        if (display != null && !displayed.equals(wanted)) {
            extraKeys.close()
            display!!.close()
            if (display!!.getParent() is ViewGroup) {
                (display!!.getParent() as ViewGroup).removeView(display)
            }
            display = null
            displayed = ""
            val enabled: Boolean = keyboardState.enabled
            keyboardState.reset()
            restore.run()
            if (enabled) {
                keyboardChanged.run()
            }
        }
        if (!inline && preparation != null) {
            if (preparation!!.getParent() is ViewGroup) {
                (preparation!!.getParent() as ViewGroup).removeView(preparation)
            }
            preparation = null
            if (!show) {
                restore.run()
            }
        }
        if (!inline && preparationPrompt != null) {
            preparationPrompt!!.dismiss()
            preparationPrompt = null
        }
        if (inline) {
            if (preparation == null) {
                val parent: FrameLayout? = activity.findViewById(R.id.workspace_terminal_surface)
                preparation = X11StartupView(activity, Runnable {  })
                parent!!.addView(preparation, FrameLayout.LayoutParams(-1, -1))
            }
            preparationUpdate()
            activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
            activity.getTerminalView()!!.setVisibility(View.INVISIBLE)
            input!!.setVisibility(View.GONE)
            activity.getTerminalToolbarViewPager()!!.setVisibility(View.GONE)
            if (preparationNeedsInstall) {
                confirmInstall(preparationProfile)
            }
            if (rendererReadyForPreparation) {
                prepareSelectedRenderer(preparationProfile)
            }
            poll()
        }
        if (show && display == null) {
            val parent: FrameLayout? = activity.findViewById(R.id.workspace_terminal_surface)
            val desktopTarget: String = target(p)
            display = RemoteDesktopView(activity, Consumer<Bundle> { b ->
                if (display != null && display!!.inputReady()) {
                    shownTargets.add(desktopTarget)
                }
            })
            display!!.screenSettings(X11ScreenSettings.load(activity, target(p)))
            display!!.onExtraKeysReset(Runnable(extraKeys::close))
            displayed = wanted
            parent!!.addView(display, FrameLayout.LayoutParams(-1, -1))
            display!!.connect(remote)
            poll()
        }
        if (show) {
            display!!.screenSettings(X11ScreenSettings.load(activity, target(p)))
            display!!.startup(desktopReady(target(p)), state.getBoolean("desktopStartupTimedOut", false), state.getInt("startupStep", 0))
            activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
            activity.getTerminalView()!!.setVisibility(View.INVISIBLE)
            input!!.setVisibility(View.GONE)
            activity.getTerminalToolbarViewPager()!!.setVisibility(View.GONE)
        }
        fullscreen.sync(if (inline) preparation else if (show) display else null)
    }

    fun fullscreenBack(): Boolean {
        if (extraKeys.showing()) {
            extraKeys.close()
            return true
        }
        return fullscreen.back()
    }

    fun extraKeysVisible(): Boolean {
        return extraKeys.showing()
    }

    fun extraKeys() {
        if (extraKeys.showing()) {
            extraKeys.close()
            return
        }
        if (Build.VERSION.SDK_INT < 30 || display == null || !display!!.inputReady()) {
            return
        }
        val target: RemoteDesktopView? = display
        extraKeys.show(target, X11ExtraKeys.Sink(target!!::extraKey), Runnable(target::releaseExtraKeys))
    }

    fun keyboard() {
        if (Build.VERSION.SDK_INT >= 30 && display != null) {
            display!!.keyboard(keyboardState.toggle())
            keyboardChanged.run()
        }
    }

    private fun dismissProgress() {
        if (progress != null) {
            progress!!.close()
            progress = null
        }
    }

    fun close() {
        if (closed) {
            return
        }
        graphical.clear()
        shownTargets.clear()
        sync()
        extraKeys.close()
        fullscreen.close()
        closed = true
        clearPreparation()
        rendererRow = null
        wrapperFolder = null
        wrapperDriver = null
        styleCheck = null
        wrapper.close()
        dismissProgress()
        programs.close()
        if (Build.VERSION.SDK_INT >= 30) {
            val observer: ViewTreeObserver = activity.getWindow().getDecorView().getViewTreeObserver()
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(imeObserver)
            }
        }
        main.removeCallbacksAndMessages(null)
        if (bound) {
            activity.unbindService(connection)
            bound = false
        }
        remote = null
        calls.shutdown()
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    companion object {
        @JvmStatic
        private fun styleSupported(p: WorkspaceStore.Session?): Boolean {
            return DesktopPrograms.supportsStyle(target(p))
        }

        @JvmStatic
        private fun styleKey(p: WorkspaceStore.Session?): String {
            return if (target(p).equals("termux")) "termux-style" else "termux-style:" + target(p)
        }

        @JvmStatic
        fun eligible(p: WorkspaceStore.Session?): Boolean {
            if (p == null || p.environment.equals("codex") || !p.aiAgent.isEmpty() || p.claudeAgent || p.geminiAgent) {
                return false
            }
            return p.environment.equals("termux") || p.environment.equals("ubuntu") || p.environment.equals("legacy") || p.environment.startsWith("proot:")
        }

        @JvmStatic
        fun target(p: WorkspaceStore.Session?): String {
            if (!eligible(p)) {
                return ""
            }
            if (!p!!.prootAlias.isEmpty()) {
                return p.prootAlias
            }
            return if (p.environment.startsWith("proot:")) p.environment.substring(6) else if (p.environment.equals("ubuntu")) "ubuntu" else "termux"
        }

        @JvmStatic
        private fun owner(p: WorkspaceStore.Session?): String {
            return if (p == null || p.handle.isEmpty()) "" else p.id + ":" + p.handle
        }

        @JvmStatic
        private fun sameSession(a: WorkspaceStore.Session?, b: WorkspaceStore.Session?): Boolean {
            return a != null && b != null && a.id.equals(b.id) && a.handle.equals(b.handle)
        }
    }
}
