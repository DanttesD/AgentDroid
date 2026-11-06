package com.termux.app.agent;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import com.agentdroid.agent.ThemeColors;
import com.termux.R;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Network trust check: a page that is bundled in the app (assets/trustcheck) and runs inside the built-in browser,
 * so every request leaves through the same exit IP (and proxy) that AI sites see.
 */
public final class NetCheckActivity extends Activity {
    private static final String START = "https://appassets.androidplatform.net/index.html";
    private String themeSig = "";
    private BrowserHelper helper;
    private WebView web;

    /** Maps /x to assets/trustcheck/x, so the page's absolute paths keep working. */
    private final class Assets implements WebViewAssetLoader.PathHandler {
        @Override public WebResourceResponse handle(String path) {
            String name = path.isEmpty() ? "index.html" : path;
            if (name.contains("..") || name.startsWith("api/")) return null;
            try {
                InputStream in = getAssets().open("trustcheck/" + name);
                return new WebResourceResponse(mime(name), "UTF-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static String mime(String n) {
        n = n.toLowerCase(Locale.ROOT);
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Lets the page read dnsleak.dev natively when the service does not allow cross-origin reads. */
    public static final class Bridge {
        @android.webkit.JavascriptInterface public String get(String url) {
            try {
                if (!url.startsWith("https://dnsleak.dev/")) return "";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(7000);
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) return "";
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 262144) out.write(buf, 0, n);
                    return out.toString("UTF-8");
                }
            } catch (IOException | RuntimeException e) {
                return "";
            }
        }
    }

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
        helper.setAssetLoader(new WebViewAssetLoader.Builder().addPathHandler("/", new Assets()).build());
        web = helper.create();
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Bridge(), "AgentNative");
        host.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        load();
    }

    private void load() {
        BrowserProxy.apply(this);
        web.loadUrl(START);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!themeSig.equals(AppTheme.signature(this))) recreate();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (helper != null && helper.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override public void onBackPressed() {
        if (helper != null && helper.hasPopup()) { helper.closePopup(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
