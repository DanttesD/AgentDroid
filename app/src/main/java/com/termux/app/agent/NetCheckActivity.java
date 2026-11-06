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

import com.agentdroid.agent.CssTheme;
import com.agentdroid.agent.NetScore;
import com.agentdroid.agent.ThemeColors;
import com.termux.R;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Network trust check: a page that is bundled in the app (assets/trustcheck) and runs inside the built-in browser,
 * so every request leaves through the same exit IP (and proxy) that AI sites see.
 */
public final class NetCheckActivity extends Activity {
    private static final String START = "https://appassets.androidplatform.net/index.html";
    private String themeSig = "";
    private BrowserHelper helper;
    private WebView web;
    private ThemeColors th;
    private String themedCss;
    private TextView scoreView, tierView, subView;

    private static final String PREFS = "agentdroid_netcheck";

    /** Last result for other screens: {score, cc, org, hosting, ts} or null when never checked. */
    public static JSONObject last(Context c) {
        try {
            String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", "");
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static String tierText(Context c, int score) {
        int t = NetScore.tier(score);
        return c.getString(t == 0 ? R.string.netcheck_tier_low : t == 1 ? R.string.netcheck_tier_mid : t == 2 ? R.string.netcheck_tier_high : R.string.netcheck_tier_unknown);
    }

    public static int tierColor(ThemeColors th, int score) {
        int t = NetScore.tier(score);
        return t == 0 ? th.ok : t == 1 ? 0xFFE0A526 : t == 2 ? th.err : th.on2;
    }

    private String css() {
        if (themedCss != null) return themedCss;
        try (InputStream in = getAssets().open("trustcheck/styles.css")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String base = CssTheme.remap(out.toString("UTF-8"), AppTheme.hue(this), th.dark);
            String s1 = hex(th.s1), line = hex(th.line);
            themedCss = base + (th.dark ? ":root{color-scheme:dark}" : "") + ":root,body{background:" + hex(th.bg) + "!important;color:" + hex(th.on) + "}"
                    + ".workspace{background:" + hex(th.bg) + "!important;border-color:" + line + "!important}"
                    + ".service-card,.panel,.history-wrap,.score-explanation,.advice-card,dialog{background:" + s1 + "!important;border-color:" + line + "!important}";
        } catch (IOException e) {
            themedCss = "";
        }
        return themedCss;
    }

    private static String hex(int c) { return String.format("#%06x", c & 0xFFFFFF); }

    /** Maps /x to assets/trustcheck/x, so the page's absolute paths keep working. */
    private final class Assets implements WebViewAssetLoader.PathHandler {
        @Override public WebResourceResponse handle(String path) {
            String name = path.isEmpty() ? "index.html" : path;
            if (name.contains("..") || name.startsWith("api/")) return null;
            if (name.equals("styles.css")) {
                try {
                    return new WebResourceResponse("text/css", "UTF-8", new java.io.ByteArrayInputStream(css().getBytes("UTF-8")));
                } catch (java.io.UnsupportedEncodingException e) {
                    return null;
                }
            }
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
    public final class Bridge {
        @android.webkit.JavascriptInterface public void report(String json) {
            try {
                JSONObject o = new JSONObject(json);
                int score = NetScore.total(o.isNull("score") ? -1 : o.optInt("score", -1), o.optString("cc", ""));
                o.put("score", score);
                o.put("ts", System.currentTimeMillis());
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", o.toString()).apply();
                runOnUiThread(() -> showResult(o));
            } catch (Exception ignored) { }
        }

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
        th = AppTheme.apply(this);
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
        LinearLayout card = ui.card();
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        scoreView = ui.text("\u2026", 44, th.on2, true);
        line.addView(scoreView);
        LinearLayout info = ui.column();
        tierView = ui.text(getString(R.string.netcheck_checking), 16, th.on, true);
        subView = ui.text("", 13, th.on2, false);
        info.addView(tierView);
        info.addView(subView, ui.gap(2));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = ui.dp(16);
        line.addView(info, ip);
        card.addView(line);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(p, 0, p, p / 2);
        root.addView(card, cp);
        JSONObject prev = last(this);
        if (prev != null) showResult(prev);
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

    private void showResult(JSONObject o) {
        int score = o.optInt("score", -1);
        scoreView.setText(score < 0 ? "\u2014" : String.valueOf(score));
        scoreView.setTextColor(tierColor(th, score));
        tierView.setText(tierText(this, score));
        String cc = o.optString("cc", "");
        String country = cc.length() == 2 ? new Locale("", cc).getDisplayCountry(new Locale("ru")) : "";
        StringBuilder sub = new StringBuilder();
        if (!country.isEmpty()) sub.append(country);
        String org = o.optString("org", "");
        if (!org.isEmpty()) sub.append(sub.length() > 0 ? " \u00b7 " : "").append(org);
        sub.append(sub.length() > 0 ? " \u00b7 " : "").append(getString(o.optBoolean("hosting") ? R.string.netcheck_kind_hosting : R.string.netcheck_kind_plain));
        subView.setText(sub);
    }

    private void load() {
        scoreView.setText("\u2026");
        scoreView.setTextColor(th.on2);
        tierView.setText(R.string.netcheck_checking);
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
