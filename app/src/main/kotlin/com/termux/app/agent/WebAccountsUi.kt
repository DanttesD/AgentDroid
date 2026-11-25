package com.termux.app.agent

import android.app.Activity
import android.content.DialogInterface
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.termux.R

/** Dialogs for web accounts: choose the service, add, rename, change the service, delete. Shared by the chat and the Agents tab.*/
object WebAccountsUi {

    fun interface Done<T> {
        fun on(value: T)
    }

    /** What happened to the account behind the dialog.*/
    interface Listener {
        fun renamed(acc: WebAccounts.Account)

        fun serviceChanged(acc: WebAccounts.Account)

        fun deleted(acc: WebAccounts.Account)
    }

    @JvmStatic
    fun hostOf(url: String): String {
        try {
            val h: String? = Uri.parse(url).getHost()
            return if (h == null) url else if (h.startsWith("www.")) h.substring(4) else h
        } catch (e: RuntimeException) {
            return url
        }
    }

    /** "Claude · claude.ai"; just the name while no service is chosen.*/
    @JvmStatic
    fun title(a: WebAccounts.Account): String {
        return if (a.url.isEmpty()) a.name else a.name + " · " + hostOf(a.url)
    }

    /** The service list (built-in and the user's own) plus "add service"; nothing is created until one is chosen.*/
    @JvmStatic
    fun chooseService(a: Activity, wa: WebAccounts?, chosen: Done<WebAccounts.Service>) {
        val svc: MutableList<WebAccounts.Service> = wa!!.services()
        val names = arrayOfNulls<String>(svc.size + 1)
        var i = 0
        while (i < svc.size) {
            names[i] = svc.get(i).name
            i++
        }
        names[svc.size] = a.getString(R.string.agent_web_add_service)
        ThemedDialog(a).setTitle(R.string.agent_web_pick_title).setItems(names, DialogInterface.OnClickListener { d, which ->
            if (which == svc.size) {
                addService(a, wa, chosen)
            } else {
                chosen.on(svc.get(which))
            }
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    @JvmStatic
    private fun addService(a: Activity, wa: WebAccounts?, chosen: Done<WebAccounts.Service>) {
        val name = EditText(a)
        name.setHint(R.string.agent_web_service_name)
        name.setSingleLine(true)
        val url = EditText(a)
        url.setHint(R.string.agent_web_service_url)
        url.setSingleLine(true)
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI)
        val box = LinearLayout(a)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(40, 10, 40, 0)
        box.addView(name)
        box.addView(url)
        ThemedDialog(a).setTitle(R.string.agent_web_add_service).setView(box).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { d, w ->
            val n: String = (name.getText().toString() as java.lang.String).trim()
            var u: String = (url.getText().toString() as java.lang.String).trim()
            if (n.isEmpty() || u.isEmpty()) {
                return@OnClickListener
            }
            if (!u.contains("://")) {
                u = "https://" + u
            }
            wa!!.addService(n, u)
            chosen.on(WebAccounts.Service(n, u))
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    /** New account named after its service ("Claude", then "Claude 2"). Created only after a service was chosen.*/
    @JvmStatic
    fun add(a: Activity, wa: WebAccounts?, created: Done<WebAccounts.Account>) {
        chooseService(a, wa, Done<WebAccounts.Service> { svc ->
            val acc: WebAccounts.Account = wa!!.add()
            acc.url = svc.url
            acc.name = uniqueName(wa, svc.name, acc.id)
            wa.update(acc)
            created.on(acc)
        })
    }

    /** Picks another service for an existing account; the account is renamed too while it still carries the old service name.*/
    @JvmStatic
    fun changeService(a: Activity, wa: WebAccounts?, acc: WebAccounts.Account, changed: Done<WebAccounts.Account>) {
        chooseService(a, wa, Done<WebAccounts.Service> { svc ->
            val oldService: String? = serviceName(wa, acc.url)
            val autoNamed: Boolean = acc.name.equals(oldService) || (acc.name as java.lang.String).matches(".+ \\d+") && oldService != null && acc.name.startsWith(oldService + " ") || acc.url.isEmpty()
            acc.url = svc.url
            if (autoNamed) {
                acc.name = uniqueName(wa, svc.name, acc.id)
            }
            wa!!.update(acc)
            changed.on(acc)
        })
    }

    @JvmStatic
    private fun serviceName(wa: WebAccounts?, url: String): String? {
        for (s in wa!!.services()) {
            if (s.url.equals(url)) {
                return s.name
            }
        }
        return null
    }

    @JvmStatic
    private fun uniqueName(wa: WebAccounts?, base: String, selfId: Int): String {
        val all: MutableList<WebAccounts.Account> = wa!!.accounts()
        var name: String = base
        var n = 2
        while (taken(all, name, selfId)) {
            name = base + " " + n
            n++
        }
        return name
    }

    @JvmStatic
    private fun taken(all: MutableList<WebAccounts.Account>, name: String, selfId: Int): Boolean {
        for (x in all) {
            if (x.id != selfId && (x.name as java.lang.String).equalsIgnoreCase(name)) {
                return true
            }
        }
        return false
    }

    /** Rename / change service / delete for one account.*/
    @JvmStatic
    fun edit(a: Activity, wa: WebAccounts?, acc: WebAccounts.Account, l: Listener) {
        val opts: Array<String> = arrayOf<String>(a.getString(R.string.agent_web_rename), a.getString(R.string.agent_web_change_service), a.getString(R.string.agent_web_delete))
        ThemedDialog(a).setTitle(acc.name).setItems(opts, DialogInterface.OnClickListener { d, which ->
            if (which == 0) {
                rename(a, wa, acc, l)
            } else if (which == 1) {
                changeService(a, wa, acc, Done<WebAccounts.Account> { x -> l.serviceChanged(x) })
            } else {
                confirmDelete(a, wa, acc, l)
            }
        }).show()
    }

    @JvmStatic
    fun rename(a: Activity, wa: WebAccounts?, acc: WebAccounts.Account, l: Listener) {
        val input = EditText(a)
        input.setText(acc.name)
        input.setSelectAllOnFocus(true)
        input.setSingleLine(true)
        ThemedDialog(a).setTitle(R.string.agent_web_rename).setView(input).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { d, w ->
            val n: String = (input.getText().toString() as java.lang.String).trim()
            if (n.isEmpty()) {
                return@OnClickListener
            }
            acc.name = n
            wa!!.update(acc)
            l.renamed(acc)
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    @JvmStatic
    fun confirmDelete(a: Activity, wa: WebAccounts?, acc: WebAccounts.Account, l: Listener) {
        ThemedDialog(a).setMessage(a.getString(R.string.webacc_delete_confirm, acc.name)).setPositiveButton(R.string.agent_web_delete, DialogInterface.OnClickListener { d, w ->
            wa!!.remove(acc)
            l.deleted(acc)
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }
}
