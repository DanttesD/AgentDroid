package com.termux.app.agent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.widget.CheckBox;
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

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

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

    public static String address(Context c) { return prefs(c).getString("addr", ""); }

    public static boolean enabled(Context c) { return !address(c).isEmpty(); }

    public static boolean forAi(Context c) { return prefs(c).getBoolean("ai", true); }

    /** Points the agent's model requests at the saved proxy (or direct when it is off or not meant for them). */
    public static void syncAgent(Context c) {
        SharedPreferences p = prefs(c);
        boolean on = enabled(c) && forAi(c);
        com.agentdroid.agent.NetConfig.set(on ? address(c) : "", p.getString("user", ""), p.getString("pass", ""));
    }

    /** Applies the saved proxy (or clears it) for every WebView of the process. Safe to call repeatedly. */
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

    /** Answers the proxy's own 407 challenge with the saved login. Returns true when handled. */
    public static boolean authenticate(Context c, HttpAuthHandler handler, boolean isProxy) {
        if (!isProxy) return false;
        SharedPreferences p = prefs(c);
        String user = p.getString("user", "");
        if (user.isEmpty()) return false;
        handler.proceed(user, p.getString("pass", ""));
        return true;
    }

    public static void showDialog(final Activity a, final Runnable onSaved) {
        final Ui ui = new Ui(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, 0);
        TextView hint = new TextView(a);
        hint.setText(R.string.browser_proxy_hint);
        box.addView(hint);
        final EditText addr = field(a, a.getString(R.string.browser_proxy_addr), address(a), false);
        final EditText user = field(a, a.getString(R.string.browser_proxy_user), prefs(a).getString("user", ""), false);
        final EditText pass = field(a, a.getString(R.string.browser_proxy_pass), prefs(a).getString("pass", ""), true);
        box.addView(addr); box.addView(user); box.addView(pass);
        final CheckBox ai = new CheckBox(a);
        ai.setText(R.string.browser_proxy_ai);
        ai.setChecked(forAi(a));
        box.addView(ai);
        if (!supported()) {
            TextView warn = new TextView(a);
            warn.setText(R.string.browser_proxy_unsupported);
            box.addView(warn);
        }
        new ThemedDialog(a).setTitle(R.string.browser_proxy_title).setView(box)
                .setPositiveButton(R.string.browser_proxy_save, (d, w) -> {
                    prefs(a).edit().putString("addr", normalize(addr.getText().toString()))
                            .putString("user", user.getText().toString().trim())
                            .putString("pass", pass.getText().toString()).putBoolean("ai", ai.isChecked()).apply();
                    apply(a);
                    Toast.makeText(a, enabled(a) ? R.string.browser_proxy_on : R.string.browser_proxy_off, Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
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
