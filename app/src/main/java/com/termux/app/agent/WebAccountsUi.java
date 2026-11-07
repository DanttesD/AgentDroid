package com.termux.app.agent;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.termux.R;

import java.util.List;

/** Dialogs for web accounts: choose the service, add, rename, change the service, delete. Shared by the chat and the Agents tab. */
final class WebAccountsUi {
    private WebAccountsUi() { }

    interface Done<T> { void on(T value); }

    /** What happened to the account behind the dialog. */
    interface Listener {
        void renamed(WebAccounts.Account acc);
        void serviceChanged(WebAccounts.Account acc);
        void deleted(WebAccounts.Account acc);
    }

    static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? url : h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** "Claude · claude.ai"; just the name while no service is chosen. */
    static String title(WebAccounts.Account a) {
        return a.url.isEmpty() ? a.name : a.name + " · " + hostOf(a.url);
    }

    /** The service list (built-in and the user's own) plus "add service"; nothing is created until one is chosen. */
    static void chooseService(final Activity a, final WebAccounts wa, final Done<WebAccounts.Service> chosen) {
        final List<WebAccounts.Service> svc = wa.services();
        String[] names = new String[svc.size() + 1];
        for (int i = 0; i < svc.size(); i++) names[i] = svc.get(i).name;
        names[svc.size()] = a.getString(R.string.agent_web_add_service);
        new ThemedDialog(a).setTitle(R.string.agent_web_pick_title).setItems(names, (d, which) -> {
            if (which == svc.size()) addService(a, wa, chosen);
            else chosen.on(svc.get(which));
        }).setNegativeButton(R.string.agent_cancel, null).show();
    }

    private static void addService(final Activity a, final WebAccounts wa, final Done<WebAccounts.Service> chosen) {
        final EditText name = new EditText(a);
        name.setHint(R.string.agent_web_service_name);
        name.setSingleLine(true);
        final EditText url = new EditText(a);
        url.setHint(R.string.agent_web_service_url);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 10, 40, 0);
        box.addView(name);
        box.addView(url);
        new ThemedDialog(a).setTitle(R.string.agent_web_add_service).setView(box)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) return;
                    if (!u.contains("://")) u = "https://" + u;
                    wa.addService(n, u);
                    chosen.on(new WebAccounts.Service(n, u));
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    /** New account named after its service ("Claude", then "Claude 2"). Created only after a service was chosen. */
    static void add(final Activity a, final WebAccounts wa, final Done<WebAccounts.Account> created) {
        chooseService(a, wa, svc -> {
            WebAccounts.Account acc = wa.add();
            acc.url = svc.url;
            acc.name = uniqueName(wa, svc.name, acc.id);
            wa.update(acc);
            created.on(acc);
        });
    }

    /** Picks another service for an existing account; the account is renamed too while it still carries the old service name. */
    static void changeService(final Activity a, final WebAccounts wa, final WebAccounts.Account acc, final Done<WebAccounts.Account> changed) {
        chooseService(a, wa, svc -> {
            String oldService = serviceName(wa, acc.url);
            boolean autoNamed = acc.name.equals(oldService) || acc.name.matches(".+ \\d+") && oldService != null && acc.name.startsWith(oldService + " ")
                    || acc.url.isEmpty();
            acc.url = svc.url;
            if (autoNamed) acc.name = uniqueName(wa, svc.name, acc.id);
            wa.update(acc);
            changed.on(acc);
        });
    }

    private static String serviceName(WebAccounts wa, String url) {
        for (WebAccounts.Service s : wa.services()) if (s.url.equals(url)) return s.name;
        return null;
    }

    private static String uniqueName(WebAccounts wa, String base, int selfId) {
        List<WebAccounts.Account> all = wa.accounts();
        String name = base;
        for (int n = 2; taken(all, name, selfId); n++) name = base + " " + n;
        return name;
    }

    private static boolean taken(List<WebAccounts.Account> all, String name, int selfId) {
        for (WebAccounts.Account x : all) if (x.id != selfId && x.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Rename / change service / delete for one account. */
    static void edit(final Activity a, final WebAccounts wa, final WebAccounts.Account acc, final Listener l) {
        String[] opts = {a.getString(R.string.agent_web_rename), a.getString(R.string.agent_web_change_service), a.getString(R.string.agent_web_delete)};
        new ThemedDialog(a).setTitle(acc.name).setItems(opts, (d, which) -> {
            if (which == 0) rename(a, wa, acc, l);
            else if (which == 1) changeService(a, wa, acc, x -> l.serviceChanged(x));
            else confirmDelete(a, wa, acc, l);
        }).show();
    }

    static void rename(final Activity a, final WebAccounts wa, final WebAccounts.Account acc, final Listener l) {
        final EditText input = new EditText(a);
        input.setText(acc.name);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        new ThemedDialog(a).setTitle(R.string.agent_web_rename).setView(input)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) return;
                    acc.name = n;
                    wa.update(acc);
                    l.renamed(acc);
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    static void confirmDelete(final Activity a, final WebAccounts wa, final WebAccounts.Account acc, final Listener l) {
        new ThemedDialog(a).setMessage(a.getString(R.string.webacc_delete_confirm, acc.name))
                .setPositiveButton(R.string.agent_web_delete, (d, w) -> {
                    wa.remove(acc);
                    l.deleted(acc);
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }
}
