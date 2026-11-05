package com.termux.app.agent;

import android.app.AlertDialog;
import android.content.Context;

import com.termux.R;

/** AlertDialog.Builder whose dialogs follow the app theme (Material You, AMOLED, accent). */
public class ThemedDialog extends AlertDialog.Builder {
    public ThemedDialog(Context c) { super(c, R.style.WorkspaceDialog); }

    @Override public AlertDialog create() {
        AlertDialog d = super.create();
        DialogSkin.attach(d);
        return d;
    }
}
