package com.termux.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agentdroid.agent.AgentLoop
import com.agentdroid.agent.Attachment
import com.agentdroid.agent.Json
import com.agentdroid.agent.StreamListener
import com.agentdroid.agent.ThemeColors
import com.agentdroid.agent.ToolCall
import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecBackend
import com.termux.R
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The Chat tab. The pill on top chooses who works (the built-in agent or a web account), the three-dot menu holds only what
 * concerns this chat, the gear opens Settings. Approvals appear as a card in the feed; files and voice go in through the composer.
 **/
open class ChatActivity : Activity(), ApprovalCenter.Inline {

    private var config: AgentConfig? = null
    private var selector: BackendSelector? = null
    private var messages: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var sendBtn: View? = null
    private var sendIcon: ImageView? = null
    private var chipExec: TextView? = null
    private var chipScreen: TextView? = null
    private var chipTunnel: TextView? = null
    private var toolState: TextView? = null
    private var chipsRow: LinearLayout? = null
    private var ui: Ui? = null
    private var tcol: ThemeColors? = null
    private var pillTitle: TextView? = null
    private var backendLabel: TextView? = null
    @Volatile private var loop: AgentLoop? = null
    @Volatile private var busy: Boolean = false
    @Volatile private var chatEpoch: Int = 0
    private var currentAssistant: TextView? = null
    private var lastCard: LinearLayout? = null
    private var nativeBox: LinearLayout? = null
    private var webBox: FrameLayout? = null
    private var outer: FrameLayout? = null
    private var helper: BrowserHelper? = null
    private val webViews: MutableMap<Int, WebView?> = HashMap<Int, WebView?>()
    private val webUrls: MutableMap<Int, String> = HashMap<Int, String>()
    private var currentWeb: Int = -1
    private var webAccounts: WebAccounts? = null
    @Volatile private var webMode: Boolean = false
    private var approvals: InlineApproval? = null
    private var attachScroll: HorizontalScrollView? = null
    private var attachRow: LinearLayout? = null
    private val attachments: MutableList<Attachment> = ArrayList<Attachment>()
    private var lastPin: String? = ""
    private var themeSig: String = ""

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val th: ThemeColors = AppTheme.apply(this)
        tcol = th
        themeSig = AppTheme.signature(this)
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        config = AgentConfig(this)
        selector = AgentBackends.create(this)
        val pin: String? = config!!.backendPin()
        if (!pin!!.isEmpty()) {
            try {
                selector!!.pin(pin)
            } catch (ignored: Exception) {
            }
        }
        lastPin = pin

        ui = Ui(this)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(th.bg)
        root.setPadding(0, dp(24), 0, 0)
        root.addView(topBar())
        root.addView(chips())

        nativeBox = LinearLayout(this)
        nativeBox!!.setOrientation(LinearLayout.VERTICAL)
        root.addView(nativeBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webBox = FrameLayout(this)
        webBox!!.setVisibility(View.GONE)
        root.addView(webBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        scroll = ScrollView(this)
        messages = LinearLayout(this)
        messages!!.setOrientation(LinearLayout.VERTICAL)
        messages!!.setPadding(dp(16), dp(8), dp(16), dp(8))
        scroll!!.addView(messages)
        nativeBox!!.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        attachScroll = HorizontalScrollView(this)
        attachScroll!!.setHorizontalScrollBarEnabled(false)
        attachScroll!!.setVisibility(View.GONE)
        attachRow = LinearLayout(this)
        attachRow!!.setPadding(dp(16), 0, dp(16), 0)
        attachScroll!!.addView(attachRow)
        nativeBox!!.addView(attachScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        composer(nativeBox)

        root.addView(NavBar.build(this, NavBar.CHAT))
        outer = FrameLayout(this)
        outer!!.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(outer)
        approvals = InlineApproval(this, ui, messages, Runnable(this::scrollDown))
        helper = BrowserHelper(this, outer, null)
        webAccounts = WebAccounts(this, getString(R.string.agent_web_default))
        if (getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("web_mode", false)) {
            val id: Int = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("web_acc", -1)
            val acc: WebAccounts.Account? = find(webAccounts!!.accounts(), id)
            if (acc == null || acc.url.isEmpty() || !openWebAccount(acc)) {
                setWebMode(false)
            }
        }

        updatePill()
        refreshBackendLabel()
        updateComposer()
        addNote(getString(R.string.agent_intro))
    }

    // ---- layout pieces -------------------------------------------------------------------------------------------
    private fun topBar(): View {
        val bar = LinearLayout(this)
        bar.setOrientation(LinearLayout.HORIZONTAL)
        bar.setGravity(Gravity.CENTER_VERTICAL)
        bar.setPadding(dp(16), dp(8), dp(8), dp(8))
        val pill = LinearLayout(this)
        pill.setGravity(Gravity.CENTER_VERTICAL)
        pill.setPadding(dp(6), dp(6), dp(12), dp(6))
        pill.setBackground(ui!!.shape(tcol!!.s1, 30.0f, tcol!!.line, 1.0f))
        pill.setClickable(true)
        pill.setOnClickListener(View.OnClickListener { v -> pickPerformer() })
        val avatar: TextView = ui!!.text("✦", 18.0f, tcol!!.onpc, false)
        avatar.setGravity(Gravity.CENTER)
        avatar.setBackground(ui!!.shape(tcol!!.pc, 18.0f, 0, 0.0f))
        pill.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
        val names: LinearLayout = ui!!.column()
        pillTitle = ui!!.text("AgentDroid", 15.0f, tcol!!.on, true)
        pillTitle!!.setSingleLine(true)
        pillTitle!!.setEllipsize(TextUtils.TruncateAt.END)
        names.addView(pillTitle)
        backendLabel = ui!!.text("", 12.0f, tcol!!.on2, false)
        backendLabel!!.setSingleLine(true)
        backendLabel!!.setEllipsize(TextUtils.TruncateAt.END)
        names.addView(backendLabel)
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.leftMargin = dp(10)
        pill.addView(names, nlp)
        pill.addView(ui!!.icon(R.drawable.ic_chev, 20, tcol!!.on2))
        bar.addView(pill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ui!!.iconButton(R.drawable.ic_dots, tcol!!.on, getString(R.string.agent_menu), View.OnClickListener { v -> showChatMenu() }))
        bar.addView(ui!!.iconButton(R.drawable.ic_gear, tcol!!.on, getString(R.string.agent_settings), View.OnClickListener { v -> startActivity(Intent(this, AppSettingsActivity::class.java)) }))
        return bar
    }

    private fun chips(): View {
        val chips = LinearLayout(this)
        chipsRow = chips
        chips.setPadding(dp(16), dp(4), dp(16), dp(8))
        chipExec = ui!!.statusChip("", false)
        chipScreen = ui!!.statusChip("", false)
        chipTunnel = ui!!.statusChip("", false)
        chipExec!!.setOnClickListener(View.OnClickListener { v -> openTab(EnvActivity::class.java) })
        chipScreen!!.setOnClickListener(View.OnClickListener { v -> openTab(PhoneActivity::class.java) })
        chipTunnel!!.setOnClickListener(View.OnClickListener { v -> openTab(PhoneActivity::class.java) })
        for (c in arrayOf<TextView?>(chipExec, chipScreen, chipTunnel)) {
            c!!.setClickable(true)
            val cl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
            cl.rightMargin = dp(8)
            chips.addView(c, cl)
        }
        return chips
    }

    private fun composer(into: LinearLayout?) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setBackground(ui!!.shape(tcol!!.s2, 32.0f, tcol!!.line, 1.0f))
        row.setPadding(dp(6), dp(6), dp(6), dp(6))

        val attach = FrameLayout(this)
        val plus = ImageView(this)
        plus.setImageResource(R.drawable.ic_plus)
        plus.setColorFilter(tcol!!.on2)
        attach.addView(plus, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        attach.setContentDescription(getString(R.string.chat_attach))
        attach.setClickable(true)
        attach.setOnClickListener(View.OnClickListener { v -> pickFile() })
        row.addView(attach, LinearLayout.LayoutParams(dp(40), dp(40)))

        input = EditText(this)
        input!!.setTextColor(tcol!!.on)
        input!!.setHintTextColor(tcol!!.on2)
        input!!.setHint(R.string.agent_hint)
        input!!.setBackground(null)
        input!!.setTextSize(15.0f)
        input!!.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        input!!.setMaxLines(5)
        input!!.setPadding(dp(4), dp(8), 0, dp(8))
        input!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(s: Editable) {
                updateComposer()
            }
        })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val send = FrameLayout(this)
        sendBtn = send
        sendIcon = ImageView(this)
        send.addView(sendIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        send.setClickable(true)
        send.setOnClickListener(View.OnClickListener { v -> onSend() })
        row.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))

        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.setMargins(dp(12), dp(6), dp(12), dp(10))
        into!!.addView(row, rlp)
    }

    /** One button, three jobs: stop while the agent works, send when there is something to send, microphone otherwise.*/
    private fun updateComposer() {
        if (sendIcon == null || input == null) {
            return
        }
        val something: Boolean = (input!!.getText().toString() as java.lang.String).trim().length > 0 || !attachments.isEmpty()
        var icon: Int
        var fill: Int
        var tint: Int
        var desc: Int
        if (busy) {
            icon = R.drawable.ic_stop
            fill = tcol!!.err2
            tint = tcol!!.err
            desc = R.string.agent_stop
        } else if (something) {
            icon = R.drawable.ic_send
            fill = tcol!!.p
            tint = tcol!!.onp
            desc = R.string.agent_send
        } else {
            icon = R.drawable.ic_mic
            fill = tcol!!.pc
            tint = tcol!!.onpc
            desc = R.string.chat_voice
        }
        sendIcon!!.setImageResource(icon)
        sendIcon!!.setColorFilter(tint)
        sendBtn!!.setBackground(ui!!.shape(fill, 22.0f, 0, 0.0f))
        sendBtn!!.setContentDescription(getString(desc))
    }

    private fun dp(v: Int): Int {
        return (v * getResources().getDisplayMetrics().density).toInt()
    }

    // ---- feed ----------------------------------------------------------------------------------------------------
    private fun userBubble(typed: String, files: MutableList<Attachment>): TextView {
        val text = StringBuilder(typed)
        for (a in files) {
            if (text.length > 0) {
                text.append('\n')
            }
            text.append("\ud83d\udcce ").append(a.name)
        }
        val t: TextView = ui!!.text(text.toString(), 15.0f, tcol!!.onpc, false)
        t.setTextIsSelectable(true)
        t.setTag("user")
        t.setLineSpacing(0.0f, 1.15f)
        t.setBackground(ui!!.bubbleShape(tcol!!.pc, 22.0f, 22.0f, 6.0f, 22.0f))
        t.setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.topMargin = dp(14)
        lp.leftMargin = dp(48)
        t.setLayoutParams(lp)
        messages!!.addView(t)
        scrollDown()
        return t
    }

    private fun assistantText(): TextView {
        val t: TextView = ui!!.text("", 15.0f, tcol!!.on, false)
        t.setTextIsSelectable(true)
        t.setLineSpacing(0.0f, 1.2f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        t.setLayoutParams(lp)
        messages!!.addView(t)
        scrollDown()
        return t
    }

    private fun note(s: String, color: Int): TextView {
        val t: TextView = ui!!.text(s, 13.0f, color, false)
        t.setTextIsSelectable(true)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        t.setLayoutParams(lp)
        messages!!.addView(t)
        scrollDown()
        return t
    }

    /** One tool call as a card: name in mono, state on the right, short result under it.*/
    private fun toolCard(name: String?, args: String): LinearLayout {
        val card: LinearLayout = ui!!.column()
        card.setBackground(ui!!.shape(tcol!!.s1, 14.0f, tcol!!.line, 1.0f))
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        val top = LinearLayout(this)
        top.setGravity(Gravity.CENTER_VERTICAL)
        val n: TextView = ui!!.text(name + (if (args.length > 2) " " + args else ""), 12.5f, tcol!!.p, false)
        n.setTypeface(Typeface.MONOSPACE)
        n.setSingleLine(true)
        n.setEllipsize(TextUtils.TruncateAt.END)
        top.addView(n, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolState = ui!!.text("…", 12.0f, tcol!!.on2, false)
        top.addView(toolState)
        card.addView(top)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        card.setLayoutParams(lp)
        messages!!.addView(card)
        scrollDown()
        return card
    }

    private fun addNote(s: String) {
        note(s, tcol!!.on2)
    }

    private fun scrollDown() {
        scroll!!.post(Runnable { scroll!!.fullScroll(View.FOCUS_DOWN) })
    }

    private fun updateChips() {
        val screen: Boolean = AgentAccessibilityService.isRunning()
        val tunnel: Boolean = TunnelManager.isRunning()
        ui!!.styleChip(chipScreen, getString(R.string.chip_screen), screen)
        ui!!.styleChip(chipTunnel, getString(if (tunnel) R.string.chip_tunnel_on else R.string.chip_tunnel_off), tunnel)
    }

    /** Pill text: the built-in agent with its model, or the web account with its site.*/
    private fun updatePill() {
        if (pillTitle == null) {
            return
        }
        if (webMode) {
            val a: WebAccounts.Account? = find(webAccounts!!.accounts(), currentWeb)
            pillTitle!!.setText(if (a == null) getString(R.string.chat_web_label) else a.name)
            backendLabel!!.setText(if (a == null || a.url.isEmpty()) "" else WebAccountsUi.hostOf(a.url))
        } else {
            pillTitle!!.setText("AgentDroid")
            val model: String? = config!!.model()
            backendLabel!!.setText((if (model == null || model.isEmpty()) getString(R.string.agents_not_set) else model) + " · " + config!!.provider())
        }
    }

    private fun refreshBackendLabel() {
        val sel: BackendSelector? = selector
        Thread(Runnable {
            val b: ExecBackend? = sel!!.select()
            val exec: String = if (b == null) getString(R.string.agent_backend_none) else b.displayName()
            val okExec: Boolean = b != null
            runOnUiThread(Runnable {
                updatePill()
                ui!!.styleChip(chipExec, exec, okExec)
                updateChips()
            })
        }, "backend-probe").start()
    }

    private fun openTab(tab: Class<*>) {
        startActivity(Intent(this, tab).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---- performer (pill) and chat menu --------------------------------------------------------------------------

    /** Who works: the built-in agent or one of the web accounts; long press on an account edits it.*/
    private fun pickPerformer() {
        val accs: MutableList<WebAccounts.Account> = webAccounts!!.accounts()
        val items: MutableList<String> = ArrayList<String>()
        val model: String? = config!!.model()
        items.add(mark(!webMode) + getString(R.string.chat_pick_native, if (model == null || model.isEmpty()) getString(R.string.agents_not_set) else model))
        for (a in accs) {
            items.add(mark(webMode && a.id == currentWeb) + WebAccountsUi.title(a))
        }
        items.add("+  " + getString(R.string.agent_menu_web_add))
        val addIndex: Int = items.size - 1
        val dlg: AlertDialog = ThemedDialog(this).setTitle(R.string.chat_pick_title).setItems(items.toTypedArray(), DialogInterface.OnClickListener { d, which ->
            if (which == 0) {
                setWebMode(false)
            } else if (which == addIndex) {
                WebAccountsUi.add(this, webAccounts, WebAccountsUi.Done<WebAccounts.Account>(this::openWebAccount))
            } else {
                openWebAccount(accs.get(which - 1))
            }
        }).create()
        dlg.getListView().setOnItemLongClickListener(AdapterView.OnItemLongClickListener { parent, view, pos, id ->
            if (pos >= 1 && pos < addIndex) {
                dlg.dismiss()
                editWebAccount(accs.get(pos - 1))
                return@OnItemLongClickListener true
            }
            return@OnItemLongClickListener false
        })
        dlg.show()
    }

    /** Only what concerns this chat: for the agent a new chat or a copy of the conversation, for a web account its page and account.*/
    private fun showChatMenu() {
        val items: MutableList<String> = ArrayList<String>()
        val actions: MutableList<Runnable> = ArrayList<Runnable>()
        val acc: WebAccounts.Account? = if (webMode) find(webAccounts!!.accounts(), currentWeb) else null
        if (webMode && acc != null) {
            items.add(getString(R.string.chat_menu_reload))
            actions.add(Runnable {
                val w: WebView? = currentWebView()
                if (w != null) {
                    w.reload()
                }
            })
            items.add(getString(R.string.chat_menu_service))
            actions.add(Runnable {
                WebAccountsUi.changeService(this, webAccounts, acc, WebAccountsUi.Done<WebAccounts.Account>(this::reloadAccount))
            })
            items.add(getString(R.string.agent_web_rename))
            actions.add(Runnable { WebAccountsUi.rename(this, webAccounts, acc, accountListener) })
            items.add(getString(R.string.agent_web_delete))
            actions.add(Runnable { WebAccountsUi.confirmDelete(this, webAccounts, acc, accountListener) })
        } else {
            items.add(getString(R.string.chat_menu_new))
            actions.add(Runnable(this::newChat))
            items.add(getString(R.string.chat_menu_copy))
            actions.add(Runnable(this::copyDialog))
        }
        ThemedDialog(this).setItems(items.toTypedArray(), DialogInterface.OnClickListener { d, which -> actions.get(which).run() }).show()
    }

    /** Starts over: the running task is stopped, the history of the agent and the feed are cleared.*/
    private fun newChat() {
        val l: AgentLoop? = loop
        if (l != null) {
            l.cancel()
        }
        approvals!!.denyAll()
        chatEpoch++
        busy = false
        loop = null
        currentAssistant = null
        lastCard = null
        toolState = null
        attachments.clear()
        renderAttachments()
        messages!!.removeAllViews()
        addNote(getString(R.string.agent_intro))
        updateComposer()
    }

    private fun copyDialog() {
        val b = StringBuilder()
        var i = 0
        while (i < messages!!.getChildCount()) {
            val v: View = messages!!.getChildAt(i)
            val t: String = textOf(v)
            if (t.isEmpty()) {
                i++
                continue
            }
            if (b.length > 0) {
                b.append("\n\n")
            }
            b.append(if ("user".equals(v.getTag())) "> " else "").append(t)
            i++
        }
        val cm: ClipboardManager? = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("agentdroid", b.toString()))
        }
        Toast.makeText(this, R.string.chat_copied_dialog, Toast.LENGTH_SHORT).show()
    }

    // ---- web accounts --------------------------------------------------------------------------------------------
    private val accountListener: WebAccountsUi.Listener = object : WebAccountsUi.Listener {
        override fun renamed(acc: WebAccounts.Account) {
            updatePill()
        }

        override fun serviceChanged(acc: WebAccounts.Account) {
            reloadAccount(acc)
        }

        override fun deleted(acc: WebAccounts.Account) {
            dropWebView(acc.id)
            BrowserHelper.deleteProfile(acc.profile())
            if (currentWeb == acc.id) {
                currentWeb = -1
                setWebMode(false)
            }
        }
    }

    private fun editWebAccount(acc: WebAccounts.Account) {
        WebAccountsUi.edit(this, webAccounts, acc, accountListener)
    }

    private fun dropWebView(id: Int) {
        webUrls.remove(id)
        val old: WebView? = webViews.remove(id)
        if (old != null) {
            webBox!!.removeView(old)
            old.destroy()
        }
    }

    /** Opens the account in the main window; asks for a service first if none is chosen yet. False when it went to another screen.*/
    private fun openWebAccount(acc: WebAccounts.Account): Boolean {
        if (acc.url.isEmpty()) {
            WebAccountsUi.changeService(this, webAccounts, acc, WebAccountsUi.Done<WebAccounts.Account>(this::reloadAccount))
            return false
        }
        val all: MutableList<WebAccounts.Account> = webAccounts!!.accounts()
        val first: Boolean = !all.isEmpty() && all.get(0).id == acc.id
        if (!first && !BrowserHelper.profilesSupported()) {
            // Old WebView: only the first account lives in this window, others open in separate profile screens.
            val idx: Int = Math.max(0, all.indexOf(find(all, acc.id)))
            val i = Intent(this, BrowserPicker.SLOTS[1 + (idx - 1) % (BrowserPicker.SLOTS.size - 1)])
            i.putExtra("url", acc.url)
            startActivity(i)
            return false
        }
        currentWeb = acc.id
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("web_acc", acc.id).apply()
        var w: WebView? = webViews.get(acc.id)
        if (w == null) {
            w = helper!!.create(if (first) null else acc.profile())
            webViews.put(acc.id, w)
            webUrls.put(acc.id, acc.url)
            webBox!!.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            w.loadUrl(acc.url)
        }
        setWebMode(true)
        for (e in webViews.entries) {
            e.value!!.setVisibility(if (e.key == acc.id) View.VISIBLE else View.GONE)
        }
        return true
    }

    /** The service of an account changed: drop its old page and open the new one.*/
    private fun reloadAccount(acc: WebAccounts.Account) {
        dropWebView(acc.id)
        openWebAccount(acc)
    }

    /** Web mode: the chosen web account fills the main area (below the top bar) instead of the agent chat.*/
    private fun setWebMode(on: Boolean) {
        webMode = on
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("web_mode", on).apply()
        nativeBox!!.setVisibility(if (on) View.GONE else View.VISIBLE)
        webBox!!.setVisibility(if (on) View.VISIBLE else View.GONE)
        if (chipsRow != null) {
            chipsRow!!.setVisibility(if (on) View.GONE else View.VISIBLE)
        }
        updatePill()
        if (!on) {
            refreshBackendLabel()
        }
    }

    /** The Agents tab may have asked for a web account (or for the agent) while this screen was in the background.*/
    private fun syncWebFromPrefs() {
        // Accounts deleted or switched to another service on the Agents tab: their pages here are stale.
        val all: MutableList<WebAccounts.Account> = webAccounts!!.accounts()
        for (id in ArrayList<Int>(webViews.keys)) {
            val a: WebAccounts.Account? = find(all, id)
            if (a != null && !a.url.isEmpty() && a.url.equals(webUrls.get(id))) {
                continue
            }
            val showing: Boolean = webMode && currentWeb == id
            dropWebView(id)
            if (a == null) {
                BrowserHelper.deleteProfile("acc" + id)
            }
            if (a != null && !a.url.isEmpty()) {
                if (showing) {
                    openWebAccount(a)
                }
            } else if (currentWeb == id) {
                currentWeb = -1
                if (webMode) {
                    setWebMode(false)
                }
            }
        }
        val sp: SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val want: Boolean = sp.getBoolean("web_mode", false)
        val id: Int = sp.getInt("web_acc", -1)
        if (want && (!webMode || currentWeb != id)) {
            val acc: WebAccounts.Account? = find(webAccounts!!.accounts(), id)
            if (acc == null || acc.url.isEmpty() || !openWebAccount(acc)) {
                if (!webMode) {
                    sp.edit().putBoolean("web_mode", false).apply()
                }
            }
        } else if (!want && webMode) {
            setWebMode(false)
        }
    }

    private fun currentWebView(): WebView? {
        return webViews.get(currentWeb)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (helper != null && helper!!.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_ATTACH) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                addAttachments(data)
            }
            return
        }
        if (requestCode == REQ_VOICE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val heard: ArrayList<String>? = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (heard != null && !heard.isEmpty()) {
                    val now: String = input!!.getText().toString()
                    input!!.setText(if (now.isEmpty()) heard.get(0) else now + " " + heard.get(0))
                    input!!.setSelection(input!!.getText().length)
                }
            }
            return
        }
        if (!helper!!.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onBackPressed() {
        if (helper!!.hasPopup()) {
            helper!!.closePopup()
            return
        }
        val cw: WebView? = currentWebView()
        if (webMode && cw != null && cw.canGoBack()) {
            cw.goBack()
            return
        }
        super.onBackPressed()
    }

    // ---- model and key ------------------------------------------------------------------------------------------

    /** A message was written without an API key: the same dialog as on the Agents tab.*/
    private fun askForKey() {
        ProviderDialog.show(this, config, Runnable {
            loop = null
            updatePill()
        })
    }

    private fun ensureLoop(): AgentLoop? {
        var l: AgentLoop? = loop
        if (l != null) {
            return l
        }
        val backend: ExecBackend? = selector!!.select()
        if (backend == null) {
            return null
        }
        l = PhoneAgent.create(this, backend, PhoneAgent.approver(this))
        loop = l
        return l
    }

    // ---- approvals in the feed -----------------------------------------------------------------------------------

    /** The question appears as a card in the feed; in web mode there is no feed on screen, so the dialog is used.*/
    override fun askInline(description: String, force: Boolean): Boolean? {
        if (webMode || approvals == null) {
            return null
        }
        return approvals!!.ask(description, force)
    }

    // ---- files and voice -----------------------------------------------------------------------------------------
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("*/*")
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_ATTACH)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.chat_attach_bad)
        }
    }

    private fun addAttachments(data: Intent) {
        val uris: MutableList<Uri?> = ArrayList<Uri?>()
        if (data.getClipData() != null) {
            var i = 0
            while (i < data.getClipData()!!.getItemCount()) {
                uris.add(data.getClipData()!!.getItemAt(i).getUri())
                i++
            }
        } else if (data.getData() != null) {
            uris.add(data.getData())
        }
        if (uris.isEmpty()) {
            return
        }
        Thread(Runnable {
            val read: MutableList<Attachment> = ArrayList<Attachment>()
            var bad = 0
            for (u in uris) {
                val a: Attachment? = readAttachment(u)
                if (a == null) {
                    bad++
                } else {
                    read.add(a)
                }
            }
            val unreadable: Int = bad
            runOnUiThread(Runnable {
                var rejected: Int = unreadable
                for (a in read) {
                    if (Attachment.fits(attachments, a)) {
                        attachments.add(a)
                    } else {
                        rejected++
                    }
                }
                renderAttachments()
                if (rejected > 0) {
                    toast(R.string.chat_attach_bad)
                }
            })
        }, "attach-read").start()
    }

    /** The file as text, or null when it is too big, binary or unreadable.*/
    private fun readAttachment(uri: Uri?): Attachment? {
        var name: String? = null
        try {
            val c = getContentResolver().query(uri!!, arrayOf<String>(OpenableColumns.DISPLAY_NAME), null, null, null)
            var primaryException: Throwable? = null
            try {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0)
                }
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        c?.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    c?.close()
                }
            }
        } catch (ignored: RuntimeException) {
        }
        if (name == null) {
            name = uri!!.getLastPathSegment()
        }
        try {
            (getContentResolver().openInputStream(uri!!)).use { `in` ->
                if (`in` == null) {
                    return null
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    if (out.size() > Attachment.MAX_BYTES) {
                        return null
                    }
                }
                return Attachment.of(name, out.toByteArray())
            }
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
    }

    private fun renderAttachments() {
        attachRow!!.removeAllViews()
        for (a in attachments) {
            val chip: TextView = ui!!.text("\ud83d\udcce " + a.name + "  ✕", 12.5f, tcol!!.on, false)
            chip.setSingleLine(true)
            chip.setGravity(Gravity.CENTER_VERTICAL)
            chip.setBackground(ui!!.shape(tcol!!.s2, 16.0f, tcol!!.line, 1.0f))
            chip.setPadding(dp(12), 0, dp(12), 0)
            chip.setContentDescription(getString(R.string.chat_attach_remove, a.name))
            chip.setClickable(true)
            chip.setOnClickListener(View.OnClickListener { v ->
                attachments.remove(a)
                renderAttachments()
            })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            lp.rightMargin = dp(8)
            attachRow!!.addView(chip, lp)
        }
        attachScroll!!.setVisibility(if (attachments.isEmpty()) View.GONE else View.VISIBLE)
        updateComposer()
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_voice))
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.chat_voice_missing)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------
    protected override fun onResume() {
        super.onResume()
        ApprovalCenter.onResume(this)
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        syncWebFromPrefs()
        updateChips()
        updatePill()
        val pin: String? = config!!.backendPin()
        if (!pin!!.equals(lastPin)) {
            lastPin = pin
            try {
                selector!!.pin(if (pin.isEmpty()) null else pin)
            } catch (ignored: Exception) {
            }
            loop = null
            refreshBackendLabel()
        }
    }

    protected override fun onPause() {
        // First leave the "visible" slot, then give the open cards back: the agent thread asks again where the user is now.
        ApprovalCenter.onPause(this)
        if (approvals != null) {
            approvals!!.releasePending()
        }
        super.onPause()
    }

    // ---- sending -------------------------------------------------------------------------------------------------
    private fun onSend() {
        if (busy) {
            val l: AgentLoop? = loop
            if (l != null) {
                l.cancel()
            }
            approvals!!.denyAll()
            return
        }
        val typed: String = (input!!.getText().toString() as java.lang.String).trim()
        if (typed.isEmpty() && attachments.isEmpty()) {
            startVoice()
            return
        }
        if (config!!.apiKey().isEmpty() && !config!!.provider()!!.equals("custom")) {
            askForKey()
            return
        }
        val files: MutableList<Attachment> = ArrayList<Attachment>(attachments)
        val msg: String = Attachment.compose(typed, files)
        input!!.setText("")
        attachments.clear()
        renderAttachments()
        userBubble(typed, files)
        busy = true
        updateComposer()
        currentAssistant = null
        val epoch: Int = chatEpoch
        Thread(Runnable {
            try {
                val l: AgentLoop? = ensureLoop()
                if (l == null) {
                    post(epoch, Runnable { addNote(getString(R.string.agent_no_backend)) })
                    return@Runnable
                }
                l.send(msg, object : StreamListener() {
                    override fun onText(delta: String) {
                        post(epoch, Runnable {
                            if (currentAssistant == null) {
                                currentAssistant = assistantText()
                            }
                            currentAssistant!!.append(delta)
                            scrollDown()
                        })
                    }

                    override fun onToolCall(call: ToolCall) {
                        post(epoch, Runnable {
                            currentAssistant = null
                            lastCard = toolCard(call.name, Json.stringify(call.args))
                        })
                    }

                    override fun onToolResult(call: ToolCall, result: String, error: Boolean) {
                        post(epoch, Runnable {
                            val shown: String = if (result.length > 600) result.substring(0, 600) + "\n…" else result
                            if (toolState != null) {
                                toolState!!.setText(getString(if (error) R.string.tool_failed else R.string.tool_done))
                                toolState!!.setTextColor(if (error) tcol!!.err else tcol!!.ok)
                            }
                            if (lastCard != null && !(shown as java.lang.String).trim().isEmpty()) {
                                val r: TextView = ui!!.text(shown, 12.0f, tcol!!.on2, false)
                                r.setTypeface(Typeface.MONOSPACE)
                                r.setTextIsSelectable(true)
                                lastCard!!.addView(r, ui!!.gap(6))
                            }
                        })
                    }

                    override fun onError(m: String) {
                        post(epoch, Runnable { addNote("⚠ " + m) })
                    }
                })
            } catch (e: Exception) {
                post(epoch, Runnable { addNote("⚠ " + e.message) })
            } finally {
                post(epoch, Runnable {
                    busy = false
                    currentAssistant = null
                    lastCard = null
                    updateComposer()
                })
            }
        }, "agent-loop").start()
    }

    /** Runs on the UI thread unless the chat was cleared meanwhile (a late answer of the old run must not leak into the new chat).*/
    private fun post(epoch: Int, r: Runnable) {
        runOnUiThread(Runnable {
            if (epoch == chatEpoch) {
                r.run()
            }
        })
    }

    companion object {
        private const val REQ_ATTACH: Int = 7101
        private const val REQ_VOICE: Int = 7102
        private const val PREFS: String = "agentdroid_agent"

        /** Opens a web account in the chat window (used by the Agents tab).*/
        @JvmStatic
        fun showWebAccount(from: Activity, accountId: Int) {
            from.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("web_mode", true).putInt("web_acc", accountId).apply()
            from.startActivity(Intent(from, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        @JvmStatic
        private fun mark(current: Boolean): String {
            return if (current) "●  " else "○  "
        }

        @JvmStatic
        private fun textOf(v: View): String {
            if (v is TextView) {
                return ((v as TextView).getText().toString() as java.lang.String).trim()
            }
            if (!(v is ViewGroup)) {
                return ""
            }
            val b = StringBuilder()
            val g: ViewGroup = v as ViewGroup
            var i = 0
            while (i < g.getChildCount()) {
                val t: String = textOf(g.getChildAt(i))
                if (t.isEmpty()) {
                    i++
                    continue
                }
                if (b.length > 0) {
                    b.append('\n')
                }
                b.append(t)
                i++
            }
            return b.toString()
        }

        @JvmStatic
        private fun find(all: MutableList<WebAccounts.Account>, id: Int): WebAccounts.Account? {
            for (a in all) {
                if (a.id == id) {
                    return a
                }
            }
            return null
        }
    }
}
