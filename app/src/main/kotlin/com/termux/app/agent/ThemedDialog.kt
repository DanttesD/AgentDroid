package com.termux.app.agent

import android.app.AlertDialog
import android.content.Context
import com.termux.R

/** AlertDialog.Builder whose dialogs follow the app theme (Material You, AMOLED, accent).*/
open class ThemedDialog : AlertDialog.Builder {
    constructor(c: Context) : super(c, R.style.WorkspaceDialog)

    override fun create(): AlertDialog {
        val d: AlertDialog = super.create()
        DialogSkin.attach(d)
        return d
    }
}
