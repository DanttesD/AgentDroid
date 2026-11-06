package com.termux.app.agent;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.agentdroid.agent.ThemeColors;
import com.termux.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Network trust check run inside the built-in browser, so it measures the same exit IP the sites see. */
public final class NetCheckActivity extends Activity {
    private String themeSig = "";
    private BrowserHelper helper;
    private WebView web;
    private String html = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeColors th = AppTheme.apply(this);
        themeSig = AppTheme.signature(this);
        Ui ui = new Ui(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.bg);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        bar.setPadding(p, p, p, p / 2);
        TextView title = new TextView(this);
        title.setText(R.string.netcheck_title);
        title.setTextColor(th.on);
        title.setTextSize(20);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(ui.button(getString(R.string.browser_proxy_menu), Ui.Kind.TONAL, v -> BrowserProxy.showDialog(this, this::load)));
        bar.addView(ui.button(getString(R.string.netcheck_again), Ui.Kind.FILLED, v -> load()));
        root.addView(bar);
        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        helper = new BrowserHelper(this, host, null);
        web = helper.create();
        web.setBackgroundColor(th.bg);
        host.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try (InputStream in = getAssets().open("netcheck.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            ThemeColors c = th;
            html = out.toString("UTF-8").replace("%BG%", css(c.bg)).replace("%S%", css(c.s2)).replace("%ON%", css(c.on))
                    .replace("%ON2%", css(c.on2)).replace("%P%", css(c.p)).replace("%OK%", css(c.ok)).replace("%ERR%", css(c.err))
                    .replace("%LINE%", css(c.line));
        } catch (java.io.IOException e) {
            html = "<body>netcheck.html missing</body>";
        }
        load();
    }

    private static String css(int c) { return String.format("#%06X", c & 0xFFFFFF); }

    private void load() {
        BrowserProxy.apply(this);
        web.loadDataWithBaseURL("https://netcheck.agentdroid.local/", html, "text/html", "UTF-8", null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!themeSig.equals(AppTheme.signature(this))) recreate();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (helper != null && helper.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
