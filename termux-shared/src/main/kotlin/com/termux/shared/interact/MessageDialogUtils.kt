package com.termux.shared.interact

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.termux.shared.R
import com.termux.shared.logger.Logger

object MessageDialogUtils {

    /**
     * Show a message in a dialog
     *
     * @param context The [Context] to use to start the dialog. An [Activity] [Context]
     *                must be passed, otherwise exceptions will be thrown.
     * @param titleText The title text of the dialog.
     * @param messageText The message text of the dialog.
     * @param onDismiss The [DialogInterface.OnDismissListener] to run when dialog is dismissed.
     **/
    @JvmStatic
    fun showMessage(context: Context, titleText: String, messageText: String, onDismiss: DialogInterface.OnDismissListener?) {
        showMessage(context, titleText, messageText, null, null, null, null, onDismiss)
    }

    /**
     * Show a message in a dialog
     *
     * @param context The [Context] to use to start the dialog. An [Activity] [Context]
     *                must be passed, otherwise exceptions will be thrown.
     * @param titleText The title text of the dialog.
     * @param messageText The message text of the dialog.
     * @param positiveText The positive button text of the dialog.
     * @param onPositiveButton The [DialogInterface.OnClickListener] to run when positive button
     *                         is pressed.
     * @param negativeText The negative button text of the dialog. If this is `null`, then
     *                         negative button will not be shown.
     * @param onNegativeButton The [DialogInterface.OnClickListener] to run when negative button
     *                         is pressed.
     * @param onDismiss The [DialogInterface.OnDismissListener] to run when dialog is dismissed.
     **/
    @JvmStatic
    fun showMessage(context: Context, titleText: String, messageText: String, positiveText: String?, onPositiveButton: DialogInterface.OnClickListener?, negativeText: String?, onNegativeButton: DialogInterface.OnClickListener?, onDismiss: DialogInterface.OnDismissListener?) {
        var positiveText = positiveText
        val builder = AlertDialog.Builder(context, R.style.Theme_AppCompat_Light_Dialog)

        val inflater: LayoutInflater? = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater?
        val view: View? = inflater!!.inflate(R.layout.dialog_show_message, null)
        if (view != null) {
            builder.setView(view)

            val titleView: TextView? = view.findViewById(R.id.dialog_title)
            if (titleView != null) {
                titleView.setText(titleText)
            }

            val messageView: TextView? = view.findViewById(R.id.dialog_message)
            if (messageView != null) {
                messageView.setText(messageText)
            }
        }

        if (positiveText == null) {
            positiveText = context.getString(android.R.string.ok)
        }
        builder.setPositiveButton(positiveText, onPositiveButton)

        if (negativeText != null) {
            builder.setNegativeButton(negativeText, onNegativeButton)
        }

        if (onDismiss != null) {
            builder.setOnDismissListener(onDismiss)
        }

        val dialog: AlertDialog = builder.create()

        dialog.setOnShowListener(DialogInterface.OnShowListener { dialogInterface ->
            Logger.logError("dialog")
            var button: Button? = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            if (button != null) {
                button.setTextColor(Color.BLACK)
            }
            button = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            if (button != null) {
                button.setTextColor(Color.BLACK)
            }
        })

        dialog.show()
    }

    @JvmStatic
    fun exitAppWithErrorMessage(context: Context, titleText: String, messageText: String) {
        showMessage(context, titleText, messageText, DialogInterface.OnDismissListener { dialog -> System.exit(0) })
    }
}
