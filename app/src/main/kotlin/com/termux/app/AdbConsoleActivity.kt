package com.termux.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.agentdroid.adb.AgentAdb
import com.termux.app.agent.AppTheme
import com.termux.app.agent.Ui
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Test console for the own ADB client (no Shizuku): pair by code, find the wireless-debugging port
 * through mDNS, run shell commands, switch adbd to a fixed local TCP port.
 **/
open class AdbConsoleActivity : Activity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var adb: AgentAdb? = null
    private var host: EditText? = null
    private var pairPort: EditText? = null
    private var code: EditText? = null
    private var connectPort: EditText? = null
    private var command: EditText? = null
    private var output: TextView? = null
    private var ui: Ui? = null
    private var themeSig: String = ""

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        ui = Ui(this)
        setTitle("AgentDroid ADB")
        adb = AgentAdb(this)

        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = (12 * getResources().getDisplayMetrics().density).toInt()
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(ui!!.t.bg)
        root.addView(ui!!.title("ADB"))

        root.addView(label("Адрес (по умолчанию этот телефон)"))
        host = edit("127.0.0.1", false)
        root.addView(host)

        root.addView(label("1. Сопряжение. Открой: Параметры разработчика → Отладка по Wi-Fi → «Сопряжение с устройством по коду», не закрывая окно, вернись сюда."))
        root.addView(button("Найти порт сопряжения (mDNS)", View.OnClickListener { v ->
            run(Task {
                val p: Pair<String, Int>? = adb!!.discoverPairingPort(30000L)
                if (p == null) {
                    log("Порт сопряжения не найден. Окно с кодом должно быть открыто.")
                    return@Task
                }
                set(host, p.first)
                set(pairPort, java.lang.String.valueOf(p.second))
                log("Порт сопряжения: " + p.first + ":" + p.second)
            })
        }))
        pairPort = edit("", true)
        pairPort!!.setHint("порт сопряжения")
        root.addView(pairPort)
        code = edit("", true)
        code!!.setHint("код из 6 цифр")
        root.addView(code)
        root.addView(button("Сопряжение", View.OnClickListener { v ->
            run(Task {
                val ok: Boolean = adb!!.pair(text(host), Integer.parseInt(text(pairPort)), text(code))
                log(if (ok) "Сопряжение выполнено." else "Сопряжение не удалось.")
            })
        }))

        root.addView(label("2. Подключение"))
        root.addView(button("Найти порт подключения (mDNS)", View.OnClickListener { v ->
            run(Task {
                val p: Pair<String, Int>? = adb!!.discoverConnectPort(8000L)
                if (p == null) {
                    log("Порт подключения не найден. Включена ли отладка по Wi-Fi?")
                    return@Task
                }
                set(host, p.first)
                set(connectPort, java.lang.String.valueOf(p.second))
                log("Порт подключения: " + p.first + ":" + p.second)
            })
        }))
        connectPort = edit("", true)
        connectPort!!.setHint("порт подключения")
        root.addView(connectPort)
        root.addView(button("Тест: выполнить id", View.OnClickListener { v -> run(Task { shell("id; getprop ro.product.model") }) }))
        root.addView(button("Переключить на локальный TCP 5555", View.OnClickListener { v ->
            run(Task {
                adb!!.switchToTcp(text(host), Integer.parseInt(text(connectPort)), 5555)
                Thread.sleep(1500L)
                val ok: Boolean = adb!!.isReachable("127.0.0.1", 5555, 3000)
                log(if (ok) "adbd слушает 127.0.0.1:5555 (до перезагрузки телефона)." else "Порт 5555 не отвечает.")
                if (ok) {
                    set(host, "127.0.0.1")
                    set(connectPort, "5555")
                }
            })
        }))

        root.addView(label("3. Своя команда"))
        command = edit("pm list packages | head -5", false)
        root.addView(command)
        root.addView(button("Выполнить", View.OnClickListener { v -> run(Task { shell(text(command)) }) }))

        output = TextView(this)
        output!!.setTypeface(Typeface.MONOSPACE)
        output!!.setTextIsSelectable(true)
        output!!.setTextColor(ui!!.t.on)
        output!!.setPadding(0, pad, 0, 0)
        root.addView(output)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(ui!!.t.bg)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun shell(cmd: String) {
        val r: AgentAdb.ShellResult = adb!!.shell(text(host), Integer.parseInt(text(connectPort)), cmd)
        log("exit=" + r.getExitCode() + "\n" + r.getOutput())
    }

    private fun interface Task {
        @Throws(Exception::class)
        fun run()
    }

    private fun run(task: Task) {
        executor.execute(Runnable {
            try {
                task.run()
            } catch (t: Throwable) {
                log("Ошибка: " + t.javaClass.getSimpleName() + ": " + t.message)
            }
        })
    }

    private fun log(s: String) {
        runOnUiThread(Runnable { output!!.setText(s + "\n\n" + output!!.getText()) })
    }

    private fun set(e: EditText?, s: String) {
        runOnUiThread(Runnable { e!!.setText(s) })
    }

    private fun text(e: EditText?): String {
        return (e!!.getText().toString() as java.lang.String).trim()
    }

    private fun label(s: String): TextView {
        val t: TextView = ui!!.text(s, 13.5f, ui!!.t.on2, false)
        t.setPadding(ui!!.dp(4.0f), ui!!.dp(18.0f), 0, ui!!.dp(8.0f))
        return t
    }

    private fun edit(value: String, number: Boolean): EditText {
        val e = EditText(this)
        e.setText(value)
        e.setTextColor(ui!!.t.on)
        e.setHintTextColor(ui!!.t.on2)
        e.setSingleLine(true)
        e.setBackground(ui!!.shape(ui!!.t.s2, 16.0f, ui!!.t.line, 1.0f))
        e.setPadding(ui!!.dp(16.0f), ui!!.dp(12.0f), ui!!.dp(16.0f), ui!!.dp(12.0f))
        if (number) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ui!!.dp(8.0f)
        e.setLayoutParams(lp)
        return e
    }

    private fun button(s: String, l: View.OnClickListener): TextView {
        val b: TextView = ui!!.button(s, Ui.Kind.TONAL, l)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ui!!.dp(8.0f)
        b.setLayoutParams(lp)
        return b
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
        }
    }
}
