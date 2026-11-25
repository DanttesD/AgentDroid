package com.termux.app.workspace

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.x11.DesktopPrograms
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer
import org.json.JSONException
import org.json.JSONObject

/** Local navigation and paged catalog; search updates only the program list.*/
class WorkspaceDesktopPrograms {
    private val activity: TermuxActivity
    private val request: BiConsumer<String, String>
    private val back: Runnable
    private val maintenance: Consumer<String>
    private var repairButton: TextView? = null
    private var activeCard: TextView? = null
    private val dnsChecks: MutableSet<CheckBox> = Collections.newSetFromMap(WeakHashMap<CheckBox, Boolean>())
    private val compatChecks: MutableSet<CheckBox> = Collections.newSetFromMap(WeakHashMap<CheckBox, Boolean>())
    private val main: Handler = Handler(Looper.getMainLooper())
    private val buttons: MutableMap<String, TextView> = LinkedHashMap<String, TextView>()
    private val inline: MutableMap<TextView, DesktopProgramProgress> = WeakHashMap<TextView, DesktopProgramProgress>()
    private val trashButtons: MutableMap<TextView, ImageButton> = WeakHashMap<TextView, ImageButton>()
    private val cardIds: MutableMap<TextView, String> = WeakHashMap<TextView, String>()
    private val rows: MutableList<Bundle> = ArrayList<Bundle>()
    private var dialog: AlertDialog? = null
    private var progress: DesktopInstallUi? = null
    private var message: TextView? = null
    private var errorDetails: TextView? = null
    private var refresh: TextView? = null
    private var recommendedButton: TextView? = null
    private var installedButton: TextView? = null
    private var catalogButton: TextView? = null
    private var pageLabel: TextView? = null
    private var previous: ImageButton? = null
    private var next: ImageButton? = null
    private var search: EditText? = null
    private var adapter: BaseAdapter? = null
    private var state: Bundle = Bundle()
    private var items: Bundle = Bundle()
    private var profile: WorkspaceStore.Session? = null
    private var recommended: Boolean = false
    private var installedOnly: Boolean = false
    private var catalogView: Boolean = false
    private var awaiting: Boolean = false
    private var expectedJob: Int = 0
    private var query: String = ""
    private var installName: String = ""
    private var offset: Int = 0
    private var total: Int = 0
    private var revision: Int = -1
    private val searchTask: Runnable = Runnable { loadPage() }

    constructor(activity: TermuxActivity, request: BiConsumer<String, String>, back: Runnable) : this(activity, request, back, Consumer<String> { op ->  })

    constructor(activity: TermuxActivity, request: BiConsumer<String, String>, back: Runnable, maintenance: Consumer<String>) {
        this.activity = activity
        this.request = request
        this.back = back
        this.maintenance = maintenance
    }

    fun openInstalled(p: WorkspaceStore.Session?, current: Bundle?) {
        close()
        awaiting = false
        installName = ""
        profile = p
        state = Bundle()
        items = Bundle()
        query = ""
        offset = 0
        revision = -1
        if (current != null && target().equals(current.getString("target", ""))) {
            state = Bundle(current)
        }
        if (state.getBundle("items") != null) {
            items = Bundle(state.getBundle("items"))
        }
        show(false, true)
        if (!busy()) {
            loadInstalled()
        }
    }

    fun profile(): WorkspaceStore.Session? {
        return profile
    }

    private fun target(): String {
        return if (profile == null) "" else WorkspaceDesktop.target(profile)
    }

    fun busy(): Boolean {
        val p: String = state.getString("phase", "")
        return p.equals("checking") || p.equals("installing") || p.equals("cancelling") || p.equals("cataloguing") || p.equals("removing")
    }

    fun visible(): Boolean {
        return dialog != null && dialog!!.isShowing()
    }

    fun polling(): Boolean {
        return visible() && busy()
    }

    fun open(p: WorkspaceStore.Session?, current: Bundle?) {
        close()
        awaiting = false
        installName = ""
        profile = p
        state = Bundle()
        items = Bundle()
        query = ""
        offset = 0
        revision = -1
        if (current != null && target().equals(current.getString("target", ""))) {
            state = Bundle(current)
        }
        if (state.getBundle("items") != null) {
            items = Bundle(state.getBundle("items"))
        }
        show(false)
        // Opening the menu is navigation, not a catalog scan.
        render()
    }

    private fun show(recommended: Boolean) {
        show(recommended, false)
    }

    private fun openCatalog() {
        query = ""
        offset = 0
        show(false, false, true)
        if (state.getBoolean("catalogReady", false)) {
            loadPage()
        } else if (!busy()) {
            catalog()
        }
    }

    private fun show(recommended: Boolean, installed: Boolean) {
        show(recommended, installed, false)
    }

    private fun show(recommended: Boolean, installed: Boolean, catalog: Boolean) {
        if (dialog != null) {
            dialog!!.dismiss()
        }
        main.removeCallbacks(searchTask)
        this.recommended = recommended
        this.installedOnly = installed
        this.catalogView = catalog
        refresh = null
        recommendedButton = null
        installedButton = null
        catalogButton = null
        adapter = null
        search = null
        previous = null
        next = null
        pageLabel = null
        if (!recommended) {
            revision = -1
        }
        buttons.clear()
        inline.clear()
        cardIds.clear()
        trashButtons.clear()
        rows.clear()
        repairButton = null
        activeCard = null
        compatChecks.clear()
        dnsChecks.clear()
        val body: LinearLayout = AgentStatusUi.body(activity)
        AgentStatusUi.info(body, tr("Окружение: ", "Environment: ") + WorkspaceEnvironments.label(target()))
        message = AgentStatusUi.info(body, "")
        message!!.setTag("desktop-programs-status")
        errorDetails = AgentStatusUi.row(body, tr("Подробнее", "Details"), "programs-error-details", Runnable {
            val content: LinearLayout = AgentStatusUi.body(activity)
            AgentStatusUi.info(content, state.getString("detail", ""))
            val scroll: ScrollView = AgentStatusUi.scroll(content)
            WorkspaceDialogs.builder(activity).setTitle(tr("Причина ошибки", "Error details")).setView(scroll).setPositiveButton(tr("Назад", "Back"), null).show()
        })
        errorDetails!!.setVisibility(View.GONE)
        if (!recommended && !installedOnly && !catalogView) {
            val pending: Bundle? = state.getBundle("resumePrograms")
            val ids: MutableSet<String> = LinkedHashSet<String>()
            if (busy() && state.getString("action", "").equals("install")) {
                ids.add(state.getString("program", ""))
            }
            if (pending != null) {
                ids.addAll(pending.keySet())
            }
            for (id in ids) {
                val name: String = if (id.startsWith("pkg:")) id.substring(4) else DesktopPrograms.title(id, ru(), target())
                val button: TextView = card(body, name, "", if (id.startsWith("pkg:")) "desktop-package-" + id.substring(4) else "desktop-program-" + id, Runnable { confirm(id, name, "") })
                buttons.put(id, button)
                activeCard = button
            }
        }
        if (recommended) {
            AgentStatusUi.info(body, tr("Рекомендуемые программы. Статус учитывает необходимые компоненты; новые программы требуют проверки.", "Recommended programs. Status includes required components; new applications need testing."))
            refresh = AgentStatusUi.row(body, tr("Проверить установленные программы", "Check installed programs"), "programs-refresh", Runnable(this::check))
            for (id in DesktopPrograms.RECOMMENDED) {
                val button: TextView = card(body, DesktopPrograms.title(id, ru(), target()), description(id), "desktop-program-" + id, Runnable {
                    if (!canResume(id) && !items.containsKey(id)) {
                        check()
                    } else {
                        confirm(id, DesktopPrograms.title(id, ru(), target()), description(id))
                    }
                })
                buttons.put(id, button)
            }
        } else if (!installedOnly && !catalogView) {
            recommendedButton = AgentStatusUi.row(body, tr("Рекомендуемые ›", "Recommended ›"), "programs-recommended", Runnable {
                show(true)
                if (!busy() && items.isEmpty()) {
                    check(false)
                }
            })
            installedButton = AgentStatusUi.row(body, tr("Установленные программы ›", "Installed programs ›"), "programs-installed", Runnable {
                query = ""
                offset = 0
                show(false, true)
                if (busy()) {
                    loadPage()
                } else {
                    loadInstalled()
                }
            })
            catalogButton = AgentStatusUi.row(body, tr("Поиск и установка программ ›", "Search and install programs ›"), "programs-catalog", Runnable(this::openCatalog))
        } else {
            if (installedOnly && DesktopPrograms.supportsMaintenance(target())) {
                repairButton = AgentStatusUi.row(body, tr("Исправить права ярлыков", "Repair launcher permissions"), "programs-repair-launchers", Runnable {
                    if (!busy()) {
                        maintenance.accept("repair-launchers")
                    }
                })
            }
            refresh = AgentStatusUi.row(body, if (installedOnly) tr("Проверить установленные программы", "Check installed programs") else tr("Подготовить и обновить каталог", "Prepare and refresh catalog"), "programs-refresh", Runnable {
                if (installedOnly) {
                    loadInstalled(true)
                } else {
                    confirmSetup()
                }
            })
            search = EditText(activity)
            search!!.setSingleLine(true)
            search!!.setTextColor(Pal.TEXT)
            search!!.setHintTextColor(Pal.MUTED)
            search!!.setHint(tr("Поиск по названию и описанию", "Search names and descriptions"))
            search!!.setTag("desktop-program-search")
            search!!.setFilters(arrayOf<InputFilter>(InputFilter.LengthFilter(200)))
            search!!.setText(query)
            body.addView(search, LinearLayout.LayoutParams(-1, -2))
            search!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {
                }

                override fun onTextChanged(s: CharSequence, st: Int, before: Int, count: Int) {
                    query = s.toString()
                    offset = 0
                    rows.clear()
                    total = 0
                    adapter!!.notifyDataSetChanged()
                    render()
                    main.removeCallbacks(searchTask)
                    main.postDelayed(searchTask, 250L)
                }

                override fun afterTextChanged(e: Editable) {
                }
            })
            val list = ListView(activity)
            list.setTag("desktop-program-catalog-list")
            list.setDividerHeight(dp(8))
            adapter = object : BaseAdapter() {
                override fun getCount(): Int {
                    return rows.size
                }

                override fun getItem(i: Int): Any {
                    return rows.get(i)
                }

                override fun getItemId(i: Int): Long {
                    return i.toLong()
                }

                override fun getView(i: Int, old: View, parent: ViewGroup): View {
                    val entry: Bundle = rows.get(i)
                    val holder = LinearLayout(activity)
                    holder.setOrientation(LinearLayout.VERTICAL)
                    val pkg: String = entry.getString("package", "")
                    val description: String = entry.getString("summary", "") + "\n" + pkg + "\n" + (if (entry.getBoolean("snap")) tr("Требует Snap — установка здесь недоступна.", "Requires Snap — installation is unavailable here.") else tr("Работа в нашем окружении не проверена.", "Not tested in our environment."))
                    val button: TextView = card(holder, entry.getString("name", pkg), description, "desktop-package-" + pkg, Runnable {
                        if (!busy()) {
                            confirm("pkg:" + pkg, entry.getString("name", pkg), description)
                        }
                    })
                    if (installedOnly) {
                        button.setText(tr("Установлено", "Installed"))
                        AgentStatusUi.available(button, false)
                        updateTrash(button, true)
                        return holder
                    }
                    button.setText(if (entry.getBoolean("installed")) tr("Установлено", "Installed") else if (entry.getBoolean("available")) tr("Установить", "Install") else tr("Недоступно", "Unavailable"))
                    AgentStatusUi.available(button, !busy() && !entry.getBoolean("installed") && entry.getBoolean("available"))
                    updateCard(button)
                    updateTrash(button, entry.getBoolean("installed"))
                    return holder
                }
            }
            list.setAdapter(adapter)
            body.addView(list, LinearLayout.LayoutParams(-1, dp(340)))
            val pages = LinearLayout(activity)
            pages.setGravity(Gravity.CENTER_VERTICAL)
            previous = arrow(false)
            previous!!.setContentDescription(tr("Предыдущие программы", "Previous programs"))
            previous!!.setOnClickListener(View.OnClickListener { v ->
                offset = Math.max(0, offset - 30)
                loadPage()
            })
            pages.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)))
            pageLabel = TextView(activity)
            pageLabel!!.setTextColor(Pal.MUTED)
            pageLabel!!.setGravity(Gravity.CENTER)
            pages.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1.0f))
            next = arrow(true)
            next!!.setContentDescription(tr("Следующие программы", "Next programs"))
            next!!.setOnClickListener(View.OnClickListener { v ->
                offset += 30
                loadPage()
            })
            pages.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
            body.addView(pages)
        }
        dialog = WorkspaceDialogs.builder(activity).setTitle(if (recommended) tr("Рекомендуемые", "Recommended") else if (installedOnly) tr("Установленные программы", "Installed programs") else if (catalogView) tr("Поиск и установка программ", "Search and install programs") else tr("Программы рабочего стола", "Desktop programs")).setView(if (recommended) AgentStatusUi.scroll(body) else body).setNegativeButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w ->
            if (this.recommended || this.installedOnly || this.catalogView) {
                query = ""
                offset = 0
                show(false)
            } else {
                close()
                back.run()
            }
        }).create()
        dialog!!.show()
        render()
    }

    private fun arrow(forward: Boolean): ImageButton {
        val button = ImageButton(activity)
        button.setImageResource(R.drawable.workspace_next)
        button.setRotation((if (forward) 0 else 180).toFloat())
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        button.setPadding(dp(12), dp(12), dp(12), dp(12))
        button.setBackground(WorkspaceDialogs.background(activity, false))
        button.setTag(if (forward) "desktop-program-next" else "desktop-program-previous")
        return button
    }

    private fun available(button: View?, enabled: Boolean) {
        button!!.setEnabled(enabled)
        button.setAlpha(if (enabled) 1f else 0.45f)
    }

    private fun card(body: LinearLayout, name: String, description: String, tag: String, click: Runnable): TextView {
        val card = LinearLayout(activity)
        card.setGravity(Gravity.CENTER_VERTICAL)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.setBackground(WorkspaceDialogs.background(activity, false))
        val labels = LinearLayout(activity)
        labels.setOrientation(LinearLayout.VERTICAL)
        val title = TextView(activity)
        title.setText(name)
        title.setTextColor(Pal.TEXT)
        title.setTextSize(14.0f)
        labels.addView(title)
        val detail = TextView(activity)
        detail.setText(description)
        detail.setTextColor(Pal.MUTED)
        detail.setTextSize(12.0f)
        detail.setPadding(0, dp(4), dp(8), 0)
        labels.addView(detail)
        card.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
        val button: TextView = WorkspaceDialogs.action(activity, tr("Проверка…", "Checking…"), 0)
        button.setTextSize(12.0f)
        button.setGravity(Gravity.CENTER)
        button.setMinHeight(dp(44))
        button.setTag(tag)
        button.setOnClickListener(View.OnClickListener { v ->
            if (installingCard(tag)) {
                stopInstall()
            } else {
                click.run()
            }
        })
        val statusRow = LinearLayout(activity)
        statusRow.setGravity(Gravity.CENTER_VERTICAL)
        statusRow.addView(button, LinearLayout.LayoutParams(0, -2, 1.0f))
        val cardId: String = if (tag.startsWith("desktop-program-")) tag.substring(16) else "pkg:" + tag.substring(16)
        val pkg: String = if (cardId.startsWith("pkg:")) cardId.substring(4) else DesktopPrograms.mainPackage(cardId, target())
        if (!pkg.isEmpty()) {
            val trash = ImageButton(activity)
            trash.setImageResource(R.drawable.workspace_delete)
            trash.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
            trash.setPadding(dp(10), dp(10), dp(10), dp(10))
            trash.setBackground(WorkspaceDialogs.background(activity, false))
            trash.setTag("desktop-remove-" + pkg)
            trash.setContentDescription(tr("Удалить ", "Remove ") + pkg)
            trash.setVisibility(View.GONE)
            trash.setOnClickListener(View.OnClickListener { v -> confirmRemove(pkg, pkg) })
            trashButtons.put(button, trash)
            statusRow.addView(trash, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        if (DesktopPrograms.supportsFirefox(target()) && (tag.equals("desktop-program-browser") || tag.equals("desktop-package-firefox"))) {
            val actions = LinearLayout(activity)
            actions.setOrientation(LinearLayout.VERTICAL)
            actions.setGravity(Gravity.CENTER_HORIZONTAL)
            actions.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
            val check = CheckBox(activity)
            check.setText("PRoot")
            check.setTextSize(11.0f)
            check.setTextColor(Pal.TEXT)
            check.setButtonTintList(ColorStateList.valueOf(Pal.TEXT))
            check.setMinHeight(dp(44))
            check.setTag("desktop-firefox-compat-checkbox")
            check.setContentDescription(tr("Совместимость Firefox с PRoot", "Firefox PRoot compatibility"))
            check.setTooltipText(check.getContentDescription())
            check.setChecked(state.getBoolean("firefoxCompat", false))
            available(check, !busy() && state.getBoolean("firefoxCompatKnown", false))
            check.setOnClickListener(View.OnClickListener { v ->
                check.setChecked(state.getBoolean("firefoxCompat", false))
                if (!busy()) {
                    maintenance.accept(if (state.getBoolean("firefoxCompat", false)) "firefox-compat-disable" else "firefox-compat-enable")
                }
            })
            compatChecks.add(check)
            actions.addView(check, LinearLayout.LayoutParams(-2, -2))
            if (DesktopPrograms.supportsFirefox(target())) {
                val dns = CheckBox(activity)
                dns.setText("DNS HTTPS")
                dns.setTextSize(11.0f)
                dns.setTextColor(Pal.TEXT)
                dns.setButtonTintList(ColorStateList.valueOf(Pal.TEXT))
                dns.setMinHeight(dp(44))
                dns.setTag("desktop-firefox-dns-checkbox")
                dns.setContentDescription(tr("DNS Firefox через HTTPS", "Firefox DNS over HTTPS"))
                dns.setTooltipText(dns.getContentDescription())
                dns.setChecked(state.getBoolean("firefoxDns", false))
                available(dns, !busy() && state.getBoolean("firefoxDnsKnown", false))
                dns.setOnClickListener(View.OnClickListener { v ->
                    dns.setChecked(state.getBoolean("firefoxDns", false))
                    if (!busy()) {
                        maintenance.accept(if (state.getBoolean("firefoxDns", false)) "firefox-dns-disable" else "firefox-dns-enable")
                    }
                })
                dnsChecks.add(dns)
                actions.addView(dns, LinearLayout.LayoutParams(-2, -2))
            }
            card.addView(actions, LinearLayout.LayoutParams(dp(128), -2))
        } else {
            card.addView(statusRow, LinearLayout.LayoutParams(dp(128), -2))
        }
        val wrapper = LinearLayout(activity)
        wrapper.setOrientation(LinearLayout.VERTICAL)
        wrapper.setBackground(WorkspaceDialogs.background(activity, false))
        card.setBackground(null)
        wrapper.addView(card, LinearLayout.LayoutParams(-1, -2))
        val line = DesktopProgramProgress(activity)
        line.view.setPadding(dp(12), 0, dp(12), dp(8))
        wrapper.addView(line.view, LinearLayout.LayoutParams(-1, -2))
        inline.put(button, line)
        val id: String = if (tag.startsWith("desktop-program-")) tag.substring(16) else "pkg:" + tag.substring(16)
        cardIds.put(button, id)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(8)
        body.addView(wrapper, lp)
        updateCard(button)
        return button
    }

    private fun loadInstalled() {
        loadInstalled(false)
    }

    private fun loadInstalled(refresh: Boolean) {
        if (busy()) {
            return
        }
        if (!refresh && state.getBoolean("installedReady", false)) {
            loadPage()
            return
        }
        revision = -1
        offset = 0
        rows.clear()
        if (adapter != null) {
            adapter!!.notifyDataSetChanged()
        }
        start("checking", if (refresh) "programs-installed-refresh" else "programs-installed", "")
    }

    fun setDns(enabled: Boolean) {
        if (busy()) {
            return
        }
        installName = tr("DNS Firefox через HTTPS", "Firefox DNS over HTTPS")
        start("installing", if (enabled) "programs-firefox-dns-on" else "programs-firefox-dns-off", "browser")
    }

    fun setCompatibility(enabled: Boolean) {
        if (busy()) {
            return
        }
        installName = tr("Совместимость Firefox", "Firefox compatibility")
        start("installing", if (enabled) "programs-firefox-compat-on" else "programs-firefox-compat-off", "browser")
    }

    private fun check() {
        check(true)
    }

    private fun check(refresh: Boolean) {
        if (busy()) {
            return
        }
        items = Bundle()
        start("checking", if (refresh) "programs-check-refresh" else "programs-check", "")
    }

    private fun catalog() {
        catalog(false)
    }

    private fun catalog(refresh: Boolean) {
        if (busy()) {
            return
        }
        revision = -1
        offset = 0
        rows.clear()
        if (adapter != null) {
            adapter!!.notifyDataSetChanged()
        }
        start("cataloguing", if (refresh) "programs-catalog-refresh" else "programs-catalog", "")
    }

    private fun loadPage() {
        if (!catalogView && !installedOnly || !state.getBoolean(if (installedOnly) "installedReady" else "catalogReady", false) || dialog == null || !dialog!!.isShowing()) {
            return
        }
        try {
            if (!busy()) {
                revision = state.getInt("catalogRevision", 0)
            }
            val q = JSONObject()
            q.put("query", query)
            q.put("offset", offset)
            q.put("installed", installedOnly)
            request.accept("programs-page", q.toString())
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
    }

    private fun confirmRemove(pkg: String, name: String) {
        if (busy() || !DesktopPrograms.removable(pkg)) {
            return
        }
        val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Удалить ", "Remove ") + name + "?").setMessage(tr("Окружение: ", "Environment: ") + WorkspaceEnvironments.label(target()) + "\n" + pkg + (if (target().equals("termux") && pkg.equals("evince")) tr("\nСправка evince-help также будет удалена, если установлена.", "\nThe evince-help documentation will also be removed if installed.") else if (target().equals("opensuse") && pkg.equals("evince")) tr("\nPDF-модуль evince-plugin-pdfdocument также будет удалён, если установлен.", "\nThe evince-plugin-pdfdocument PDF module will also be removed if installed.") else "") + "\n\n" + (if (pkg.equals("termux-jobs-gpu-test")) tr("Будут удалены Jobs GPU Test и его ярлыки. Отчёты и общие зависимости сохранятся. Перед удалением закройте тест.", "Jobs GPU Test and its shortcuts will be removed. Reports and shared dependencies are retained. Close the test first.") else tr("Будет удалён пакет программы. Общие зависимости, настройки и ваши документы сохранятся. Если пакет нужен другим программам, удаление будет отклонено. Перед удалением закройте эту программу.", "The application package will be removed. Shared dependencies, settings and your documents are retained. Removal is refused if other packages require it. Close the application first."))).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(tr("Удалить", "Remove"), DialogInterface.OnClickListener { d, w ->
            installName = name
            start("removing", "programs-remove", "pkg:" + pkg)
        }).create()
        confirm.setCanceledOnTouchOutside(false)
        confirm.show()
    }

    private fun nativeCatalog(): Boolean {
        return target().equals("alpine") || target().equals("termux") || target().equals("fedora") || target().equals("opensuse") || target().equals("void") || target().equals("archlinux")
    }

    private fun description(id: String): String {
        if (id.equals("browser") && nativeCatalog()) {
            return tr("Firefox из репозитория выбранного окружения. При падении вкладок можно включить совместимость PRoot.", "Firefox from the selected environment's repository. Enable PRoot compatibility if tabs crash.")
        }
        return DesktopPrograms.description(id, ru())
    }

    private fun archNotice(): String {
        return if (target().equals("archlinux")) tr("\nArch Linux: будут также обновлены пакеты этого окружения (pacman -Syu).", "\nArch Linux: this environment’s packages will also be upgraded (pacman -Syu). ") else ""
    }

    private fun confirmSetup() {
        if (busy()) {
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(tr("Подготовить и обновить каталог?", "Prepare and refresh catalog?")).setMessage((if (nativeCatalog()) tr("Будут обновлены списки пакетов выбранного окружения. Каталог содержит программы и библиотеки; установка выбирается отдельно.", "Refresh the selected environment package lists. The catalog includes applications and libraries; installation is separate.") else tr("При необходимости в выбранное окружение будут установлены AppStream и привязки Python для AppStream и APT. Уже подготовленные компоненты переустанавливаться не будут. Затем обновятся метаданные программ из её репозиториев. Нужны интернет и свободное место. Это не устанавливает сами программы каталога.", "Missing AppStream, Python and APT bindings will be installed in the selected environment. Existing components are retained, then repository application metadata is refreshed. Internet and free storage are required. Catalog applications themselves will not be installed.")) + archNotice()).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(tr("Продолжить", "Continue"), DialogInterface.OnClickListener { d, w ->
            installName = DesktopPrograms.title("catalog-tools", ru())
            start("installing", "programs-catalog-install", "catalog-tools")
        }).show()
    }

    private fun canResume(id: String?): Boolean {
        val pending: Bundle? = state.getBundle("resumePrograms")
        return pending != null && pending.containsKey(id)
    }

    private fun confirm(id: String, name: String, description: String) {
        if (busy() || items.getBoolean(id, false)) {
            return
        }
        if (canResume(id)) {
            installName = name
            start("installing", "programs-install", id)
            return
        }
        val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Установить ", "Install ") + name + "?").setMessage(description + "\n\n" + tr("Окружение: ", "Environment: ") + WorkspaceEnvironments.label(target()) + ".\n" + tr("Потребуются интернет и место для программы и её зависимостей. После отмены может понадобиться восстановление пакетов.", "Internet and storage for the program and dependencies are required. Cancelling may require package repair.") + archNotice()).setNegativeButton(tr("Назад", "Back"), null).setPositiveButton(tr("Установить", "Install"), DialogInterface.OnClickListener { d, w ->
            installName = name
            start("installing", "programs-install", id)
        }).create()
        confirm.setCanceledOnTouchOutside(false)
        confirm.show()
    }

    private fun start(phase: String, op: String, id: String) {
        awaiting = true
        expectedJob = state.getInt("job", 0) + 1
        val next = Bundle(state)
        next.remove("catalogPage")
        next.remove("downloadBytes")
        next.remove("downloadTotal")
        next.putString("stage", "preparing")
        next.putString("phase", phase)
        next.putString("action", if (op.startsWith("programs-")) op.substring(9) else op)
        next.putString("program", id)
        next.putString("detail", "")
        next.putInt("percent", -1)
        next.putLong("eta", (-1).toLong())
        state = next
        render()
        request.accept(op, id)
    }

    fun accept(b: Bundle) {
        if (dialog == null || !dialog!!.isShowing() || !target().equals(b.getString("target", ""))) {
            return
        }
        val job: Int = b.getInt("job", 0)
        if (job < state.getInt("job", 0) || awaiting && job < expectedJob) {
            return
        }
        awaiting = false
        val phase: String = b.getString("phase", "")
        val controlsChanged: Boolean = !phase.equals(state.getString("phase", "")) || job != state.getInt("job", 0)
        val result: Bundle? = b.getBundle("items")
        if (result != null) {
            items = Bundle(result)
        }
        if (phase.equals("cancelled") || phase.equals("error")) {
            val action: String = b.getString("action", state.getString("action", ""))
            if (result == null && (action.equals("install") || action.equals("remove") || action.equals("catalog-install"))) {
                items = Bundle()
            }
        }
        state = Bundle(b)
        val page: Bundle? = b.getBundle("catalogPage")
        var rebuild = false
        if (page != null && (catalogView || installedOnly) && installedOnly == page.getBoolean("installed", false) && query.equals(page.getString("query", "")) && offset == page.getInt("offset", -1)) {
            val values: ArrayList<Bundle>? = page.getParcelableArrayList("rows")
            rows.clear()
            if (values != null) {
                rows.addAll(values)
            }
            total = page.getInt("total", 0)
            rebuild = true
        }
        render()
        if (adapter != null && (rebuild || controlsChanged)) {
            adapter!!.notifyDataSetChanged()
        }
        if (installedOnly && phase.equals("complete") && (b.getString("action", "").equals("remove") || !state.getBoolean("installedReady", false))) {
            loadInstalled()
            return
        }
        if (recommended && phase.equals("complete") && items.isEmpty() && !b.getString("action", "").equals("check")) {
            check(false)
            return
        }
        val version: Int = b.getInt("catalogRevision", 0)
        if ((catalogView || installedOnly) && !busy() && b.getBoolean(if (installedOnly) "installedReady" else "catalogReady", false) && version != revision) {
            revision = version
            loadPage()
        }
    }

    private fun installingCard(tag: String): Boolean {
        val id: String = if (tag.startsWith("desktop-program-")) tag.substring(16) else "pkg:" + tag.substring(16)
        return busy() && state.getString("action", "").equals("install") && id.equals(state.getString("program", ""))
    }

    private fun stopInstall() {
        if (!busy() || state.getString("phase", "").equals("cancelling")) {
            return
        }
        state.putString("phase", "cancelling")
        render()
        request.accept("programs-cancel", "")
    }

    private fun updateTrash(button: TextView, installed: Boolean) {
        val trash: ImageButton? = trashButtons.get(button)
        if (trash == null) {
            return
        }
        val id: String? = cardIds.get(button)
        val pkg: String = if (id!!.startsWith("pkg:")) id.substring(4) else DesktopPrograms.mainPackage(id, target())
        trash.setVisibility(if (installed) View.VISIBLE else View.GONE)
        available(trash, installed && !busy() && DesktopPrograms.removable(pkg))
    }

    private fun updateCard(button: TextView) {
        val line: DesktopProgramProgress? = inline.get(button)
        if (line == null) {
            return
        }
        val current: Boolean = busy() && state.getString("action", "").equals("install") && cardIds.get(button)!!.equals(state.getString("program", ""))
        line.update(state, current)
        if (recommended) {
            updateTrash(button, items.getBoolean(cardIds.get(button), false))
        } else if (trashButtons.containsKey(button) && busy()) {
            available(trashButtons.get(button), false)
        }
        if (current) {
            val stopping: Boolean = state.getString("phase", "").equals("cancelling")
            setText(button, if (stopping) tr("Остановка…", "Stopping…") else tr("Стоп", "Stop"))
            AgentStatusUi.available(button, !stopping)
        } else if (busy()) {
            AgentStatusUi.available(button, false)
        } else if (canResume(cardIds.get(button)) && !items.getBoolean(cardIds.get(button), false)) {
            setText(button, tr("Продолжить", "Continue"))
            AgentStatusUi.available(button, true)
            updateTrash(button, false)
        } else if (!recommended) {
            val id: String? = cardIds.get(button)
            val justInstalled: Boolean = state.getString("phase", "").equals("complete") && state.getString("action", "").equals("install") && id!!.equals(state.getString("program", ""))
            val installed: Boolean = justInstalled || items.getBoolean(id, false)
            if (installed) {
                setText(button, tr("Установлено", "Installed"))
                AgentStatusUi.available(button, false)
                updateTrash(button, true)
            } else if (button === activeCard) {
                setText(button, if (items.containsKey(id)) tr("Установить", "Install") else tr("Проверить", "Check"))
                AgentStatusUi.available(button, true)
                updateTrash(button, false)
            }
        }
    }

    private fun render() {
        if (dialog == null) {
            return
        }
        val phase: String = state.getString("phase", "")
        val active: Boolean = busy()
        if (repairButton != null) {
            AgentStatusUi.available(repairButton, !active)
        }
        for (check in compatChecks) {
            check.setChecked(state.getBoolean("firefoxCompat", false))
            available(check, !active && state.getBoolean("firefoxCompatKnown", false))
        }
        for (check in dnsChecks) {
            check.setChecked(state.getBoolean("firefoxDns", false))
            available(check, !active && state.getBoolean("firefoxDnsKnown", false))
        }
        if (recommended) {
            for (e in buttons.entries) {
                val known: Boolean = items.containsKey(e.key)
                val installed: Boolean = items.getBoolean(e.key, false)
                setText(e.value, if (installed) tr("Установлено", "Installed") else if (active) tr("Проверка…", "Checking…") else if (known) tr("Установить", "Install") else tr("Проверить", "Check"))
                AgentStatusUi.available(e.value, !active && !installed)
            }
        } else if (installedOnly || catalogView) {
            search!!.setEnabled(!active && state.getBoolean(if (installedOnly) "installedReady" else "catalogReady", false))
            available(previous, !active && offset > 0)
            available(next, !active && offset + 30 < total)
            setText(pageLabel, if (rows.isEmpty()) "0 / " + total else (offset + 1).toString() + "–" + (offset + rows.size) + " / " + total)
        }
        if (refresh != null) {
            setText(refresh, if (active) tr("Выполняется…", "Working…") else if (recommended || installedOnly) tr("Проверить установленные программы", "Check installed programs") else tr("Подготовить и обновить каталог", "Prepare and refresh catalog"))
            AgentStatusUi.available(refresh, !active)
        }
        var text: String = if (phase.equals("needs-tools")) tr("Каталог ещё не готов. В разделе «Поиск и установка программ» нажмите «Подготовить и обновить каталог». Рекомендуемые доступны отдельно.", "Open Search and install programs, then select Prepare and refresh catalog. Recommended programs are available separately.") else if (phase.equals("error")) tr("Не удалось выполнить действие. Причина указана ниже.", "The action failed. The reason is shown below.") else if (phase.equals("cancelled")) tr("Установка остановлена. Нажмите «Продолжить» в карточке программы. При необходимости восстановите пакеты.", "Installation stopped. Select Continue on the program card. Repair packages if necessary.") else if (phase.equals("cataloguing")) tr("Загрузка каталога выбранного окружения…", "Loading the selected environment's catalog…") else if (phase.equals("checking")) tr("Проверка установленных программ…", "Checking installed programs…") else if (installedOnly) tr("Установленные графические программы выбранного окружения. Базовые компоненты защищены от удаления.", "Installed graphical programs in the selected environment. Core desktop components are protected.") else if (recommended) tr("Состояние программ проверено.", "Program status checked.") else if (nativeCatalog()) tr("Программы и пакеты выбранного окружения, включая библиотеки. Совместимость с X11 требует проверки.", "Environment applications and packages, including libraries. X11 compatibility requires testing.") else tr("Дополнительные программы из репозиториев. Совместимость с proot/X11 требует проверки.", "Additional repository applications. Compatibility with proot/X11 requires testing.")
        val error: Boolean = phase.equals("error")
        val detail: String = state.getString("detail", "")
        if (error && (detail.contains("Failed to fetch") || detail.contains("Error reading from server"))) {
            text = tr("Не удалось скачать пакеты из репозитория. Проверьте соединение или выберите другое зеркало и повторите установку. Подробности — ниже.", "Could not download repository packages. Check the connection or select another mirror, then retry installation. Details below.")
        }
        setText(message, text)
        errorDetails!!.setVisibility(if (error && !detail.isEmpty()) View.VISIBLE else View.GONE)
        for (button in ArrayList<TextView>(inline.keys)) {
            updateCard(button)
        }
        val inlineInstall: Boolean = state.getString("action", "").equals("install")
        if ((phase.equals("installing") || phase.equals("removing") || phase.equals("cancelling")) && !inlineInstall) {
            if (progress == null) {
                progress = DesktopInstallUi(activity, if (phase.equals("removing")) tr("Удаление программы", "Removing program") else tr("Установка программы", "Installing program"), Runnable { request.accept("programs-cancel", "") })
            }
            val id: String = state.getString("program", "")
            val title: String = if (installName.isEmpty()) if (id.startsWith("pkg:")) id.substring(4) else DesktopPrograms.title(if (id.isEmpty()) "catalog-tools" else id, ru()) else installName
            progress!!.update(if (phase.equals("cancelling")) tr("Отмена…", "Cancelling…") else (if (phase.equals("removing")) tr("Удаление: ", "Removing: ") else tr("Установка: ", "Installing: ")) + title, state.getString("detail", ""), state.getInt("percent", -1), state.getLong("eta", (-1).toLong()), state.getLong("sampleTime", 0L), state.getString("stage", "preparing"), state.getLong("downloadBytes", (-1).toLong()), state.getLong("downloadTotal", (-1).toLong()))
        } else if (progress != null) {
            progress!!.close()
            progress = null
        }
    }

    private fun setText(v: TextView?, value: String) {
        if (!v!!.getText().toString().equals(value)) {
            v.setText(value)
        }
    }

    fun failed() {
        awaiting = false
        val b = Bundle()
        b.putInt("job", state.getInt("job", 0))
        b.putString("target", target())
        b.putString("phase", "error")
        accept(b)
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        if (progress != null) {
            progress!!.close()
            progress = null
        }
        if (dialog != null) {
            dialog!!.dismiss()
            dialog = null
        }
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun ru(): Boolean {
        return WorkspaceLanguage.code(activity)!!.equals("ru")
    }

    private fun tr(ru: String, en: String): String {
        return AgentStatusUi.tr(activity, ru, en)
    }
}
