package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Irreversible deletion always needs a fresh, unchecked acknowledgement.*/
object StorageDeletionConsent {
    @JvmStatic
    private fun t(a: Activity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun show(activity: Activity, title: String, summary: String, confirmed: Runnable): AlertDialog {
        val body = LinearLayout(activity)
        body.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = WorkspaceDialogs.dp(activity, 16.0f)
        body.setPadding(pad, 0, pad, pad)
        val text = TextView(activity)
        text.setText(summary)
        text.setTextColor(Pal.TEXT)
        text.setTextSize(14.0f)
        body.addView(text)
        val consent = CheckBox(activity)
        consent.setText(t(activity, "Данные могут быть потеряны навсегда", "Data may be permanently lost"))
        consent.setTextColor(Pal.DANGER)
        body.addView(consent)
        val scroll = ScrollView(activity)
        scroll.addView(body)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).blockOutsideDismiss().setTitle(title).setView(scroll).setNegativeButton(t(activity, "Отмена", "Cancel"), null).setPositiveButton(t(activity, "Удалить", "Delete"), null).create()
        dialog.setCancelable(false)
        dialog.show()
        val button: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        button.setTextColor(Pal.DANGER)
        button.setEnabled(false)
        consent.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { v, checked -> button.setEnabled(checked) })
        button.setOnClickListener(View.OnClickListener { v ->
            if (!consent.isChecked()) {
                return@OnClickListener
            }
            dialog.dismiss()
            confirmed.run()
        })
        return dialog
    }
}
