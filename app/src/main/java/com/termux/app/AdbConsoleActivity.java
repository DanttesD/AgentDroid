package com.termux.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentdroid.adb.AgentAdb;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test console for the own ADB client (no Shizuku): pair by code, find the wireless-debugging port
 * through mDNS, run shell commands, switch adbd to a fixed local TCP port.
 */
public class AdbConsoleActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AgentAdb adb;
    private EditText host, pairPort, code, connectPort, command;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("AgentDroid ADB");
        adb = new AgentAdb(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#101418"));

        root.addView(label("Адрес (по умолчанию этот телефон)"));
        host = edit("127.0.0.1", false);
        root.addView(host);

        root.addView(label("1. Сопряжение. Открой: Параметры разработчика → Отладка по Wi-Fi → «Сопряжение с устройством по коду», не закрывая окно, вернись сюда."));
        root.addView(button("Найти порт сопряжения (mDNS)", v -> run(() -> {
            kotlin.Pair<String, Integer> p = adb.discoverPairingPort(30000L);
            if (p == null) { log("Порт сопряжения не найден. Окно с кодом должно быть открыто."); return; }
            set(host, p.getFirst()); set(pairPort, String.valueOf(p.getSecond()));
            log("Порт сопряжения: " + p.getFirst() + ":" + p.getSecond());
        })));
        pairPort = edit("", true);
        pairPort.setHint("порт сопряжения");
        root.addView(pairPort);
        code = edit("", true);
        code.setHint("код из 6 цифр");
        root.addView(code);
        root.addView(button("Сопряжение", v -> run(() -> {
            boolean ok = adb.pair(text(host), Integer.parseInt(text(pairPort)), text(code));
            log(ok ? "Сопряжение выполнено." : "Сопряжение не удалось.");
        })));

        root.addView(label("2. Подключение"));
        root.addView(button("Найти порт подключения (mDNS)", v -> run(() -> {
            kotlin.Pair<String, Integer> p = adb.discoverConnectPort(8000L);
            if (p == null) { log("Порт подключения не найден. Включена ли отладка по Wi-Fi?"); return; }
            set(host, p.getFirst()); set(connectPort, String.valueOf(p.getSecond()));
            log("Порт подключения: " + p.getFirst() + ":" + p.getSecond());
        })));
        connectPort = edit("", true);
        connectPort.setHint("порт подключения");
        root.addView(connectPort);
        root.addView(button("Тест: выполнить id", v -> run(() -> shell("id; getprop ro.product.model"))));
        root.addView(button("Переключить на локальный TCP 5555", v -> run(() -> {
            adb.switchToTcp(text(host), Integer.parseInt(text(connectPort)), 5555);
            Thread.sleep(1500);
            boolean ok = adb.isReachable("127.0.0.1", 5555, 3000);
            log(ok ? "adbd слушает 127.0.0.1:5555 (до перезагрузки телефона)." : "Порт 5555 не отвечает.");
            if (ok) { set(host, "127.0.0.1"); set(connectPort, "5555"); }
        })));

        root.addView(label("3. Своя команда"));
        command = edit("pm list packages | head -5", false);
        root.addView(command);
        root.addView(button("Выполнить", v -> run(() -> shell(text(command)))));

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setTextColor(Color.WHITE);
        output.setPadding(0, pad, 0, 0);
        root.addView(output);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#101418"));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void shell(String cmd) {
        AgentAdb.ShellResult r = adb.shell(text(host), Integer.parseInt(text(connectPort)), cmd);
        log("exit=" + r.getExitCode() + "\n" + r.getOutput());
    }

    private interface Task { void run() throws Exception; }

    private void run(Task task) {
        executor.execute(() -> {
            try { task.run(); }
            catch (Throwable t) { log("Ошибка: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        });
    }

    private void log(String s) { runOnUiThread(() -> output.setText(s + "\n\n" + output.getText())); }
    private void set(EditText e, String s) { runOnUiThread(() -> e.setText(s)); }
    private String text(EditText e) { return e.getText().toString().trim(); }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#9FE6D0"));
        t.setPadding(0, 24, 0, 8);
        return t;
    }

    private EditText edit(String value, boolean number) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
