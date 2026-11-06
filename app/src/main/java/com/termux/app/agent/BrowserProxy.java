package com.termux.app.agent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.termux.R;

/**
 * Own proxy for the built-in browser only. The site's trust score is decided by the exit IP, so a user can route
 * just the browser through a residential/mobile proxy without touching the rest of the phone.
 */
public final class BrowserProxy {
    private static final String PREFS = "agentdroid_browser_proxy";

    private BrowserProxy() { }

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("migrated")) {
            // one proxy used to serve both; keep it for the browser and for AI if that box was ticked
            SharedPreferences.Editor e = p.edit().putBoolean("migrated", true);
            String old = p.getString("addr", "");
            if (!old.isEmpty()) {
                e.putString("b_addr", old).putString("b_user", p.getString("user", "")).putString("b_pass", p.getString("pass", ""));
                if (p.getBoolean("ai", true)) e.putString("a_addr", old).putString("a_user", p.getString("user", "")).putString("a_pass", p.getString("pass", ""));
            }
            e.apply();
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return p;
    }

    public static boolean supported() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE); } catch (Throwable t) { return false; }
    }

    /** "scheme://host:port" or "", normalised from what the user typed (host:port means http). */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "http://" + s;
        return s;
    }

    /** The browser's proxy address. */
    public static String address(Context c) { return prefs(c).getString("b_addr", ""); }

    public static boolean enabled(Context c) { return !address(c).isEmpty(); }

    public static boolean appEnabled(Context c) { return !prefs(c).getString("a_addr", "").isEmpty(); }

    /** True when any proxy is set, for the menu tick. */
    public static boolean anyEnabled(Context c) { return enabled(c) || appEnabled(c); }

    /** Points the app's own requests (AI models) at the app proxy, or direct when none is set. */
    public static void syncAgent(Context c) {
        SharedPreferences p = prefs(c);
        com.agentdroid.agent.NetConfig.set(p.getString("a_addr", ""), p.getString("a_user", ""), p.getString("a_pass", ""));
    }

    /** Applies the browser proxy (or clears it) for every WebView of the process. Safe to call repeatedly. */
    public static void apply(Context c) {
        syncAgent(c);
        if (!supported()) return;
        try {
            String addr = address(c);
            Runnable done = () -> { };
            if (addr.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, done);
            } else {
                ProxyConfig cfg = new ProxyConfig.Builder().addProxyRule(addr).build();
                ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, done);
            }
        } catch (Throwable ignored) {
            // an invalid address must never break the browser
        }
    }

    /** Answers the proxy's own 407 challenge with the browser proxy's login. Returns true when handled. */
    public static boolean authenticate(Context c, HttpAuthHandler handler, boolean isProxy) {
        if (!isProxy) return false;
        SharedPreferences p = prefs(c);
        String user = p.getString("b_user", "");
        if (user.isEmpty()) return false;
        handler.proceed(user, p.getString("b_pass", ""));
        return true;
    }

    public static void showDialog(final Activity a, final Runnable onSaved) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, 0);
        SharedPreferences sp = prefs(a);
        final EditText[] b = section(a, box, R.string.browser_proxy_sec_browser, R.string.browser_proxy_sec_browser_hint, sp, "b_");
        final EditText[] ap = section(a, box, R.string.browser_proxy_sec_app, R.string.browser_proxy_sec_app_hint, sp, "a_");
        TextView fmt = new TextView(a);
        fmt.setText(R.string.browser_proxy_hint);
        fmt.setPadding(0, p / 2, 0, 0);
        box.addView(fmt);
        if (!supported()) {
            TextView warn = new TextView(a);
            warn.setText(R.string.browser_proxy_unsupported);
            box.addView(warn);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new ThemedDialog(a).setTitle(R.string.browser_proxy_title).setView(sv)
                .setPositiveButton(R.string.browser_proxy_save, (d, w) -> {
                    prefs(a).edit()
                            .putString("b_addr", normalize(b[0].getText().toString())).putString("b_user", b[1].getText().toString().trim()).putString("b_pass", b[2].getText().toString())
                            .putString("a_addr", normalize(ap[0].getText().toString())).putString("a_user", ap[1].getText().toString().trim()).putString("a_pass", ap[2].getText().toString())
                            .apply();
                    apply(a);
                    Toast.makeText(a, anyEnabled(a) ? R.string.browser_proxy_on : R.string.browser_proxy_off, Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static EditText[] section(Activity a, LinearLayout box, int title, int hint, SharedPreferences sp, String key) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, (int) (10 * a.getResources().getDisplayMetrics().density), 0, 0);
        box.addView(t);
        TextView h = new TextView(a);
        h.setText(hint);
        h.setTextSize(13);
        box.addView(h);
        EditText addr = field(a, a.getString(R.string.browser_proxy_addr), sp.getString(key + "addr", ""), false);
        EditText user = field(a, a.getString(R.string.browser_proxy_user), sp.getString(key + "user", ""), false);
        EditText pass = field(a, a.getString(R.string.browser_proxy_pass), sp.getString(key + "pass", ""), true);
        box.addView(addr); box.addView(user); box.addView(pass);
        return new EditText[]{addr, user, pass};
    }

    private static EditText field(Activity a, String hint, String value, boolean secret) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(secret
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }
}
