package com.termux.shared.interact

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Selection
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object TextInputDialogUtils {

    fun interface TextSetListener {
        fun onTextSet(text: String)
    }

    @JvmStatic
    fun textInput(activity: Activity, titleText: Int, initialText: String?, positiveButtonText: Int, onPositive: TextSetListener, neutralButtonText: Int, onNeutral: TextSetListener?, negativeButtonText: Int, onNegative: TextSetListener?, onDismiss: DialogInterface.OnDismissListener?) {
        val input = EditText(activity)
        input.setSingleLine()
        if (initialText != null) {
            input.setText(initialText)
            Selection.setSelection(input.getText(), initialText.length)
        }

        val dialogHolder = arrayOfNulls<AlertDialog>(1)
        input.setImeActionLabel(activity.getResources().getString(positiveButtonText), KeyEvent.KEYCODE_ENTER)
        input.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, event ->
            onPositive.onTextSet(input.getText().toString())
            dialogHolder[0]!!.dismiss()
            return@OnEditorActionListener true
        })

        val dipInPixels: Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, activity.getResources().getDisplayMetrics())
        // https://www.google.com/design/spec/components/dialogs.html#dialogs-specs
        val paddingTopAndSides: Int = Math.round(16 * dipInPixels)
        val paddingBottom: Int = Math.round(24 * dipInPixels)

        val layout = LinearLayout(activity)
        layout.setOrientation(LinearLayout.VERTICAL)
        layout.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.setPadding(paddingTopAndSides, paddingTopAndSides, paddingTopAndSides, paddingBottom)
        layout.addView(input)

        val builder: AlertDialog.Builder = AlertDialog.Builder(activity).setTitle(titleText).setView(layout).setPositiveButton(positiveButtonText, DialogInterface.OnClickListener { d, whichButton -> onPositive.onTextSet(input.getText().toString()) })

        if (onNeutral != null) {
            builder.setNeutralButton(neutralButtonText, DialogInterface.OnClickListener { dialog, which -> onNeutral.onTextSet(input.getText().toString()) })
        }

        if (onNegative == null) {
            builder.setNegativeButton(R.string.cancel, null)
        } else {
            builder.setNegativeButton(negativeButtonText, DialogInterface.OnClickListener { dialog, which -> onNegative.onTextSet(input.getText().toString()) })
        }

        if (onDismiss != null) {
            builder.setOnDismissListener(onDismiss)
        }

        dialogHolder[0] = builder.create()
        dialogHolder[0]!!.setCanceledOnTouchOutside(false)
        dialogHolder[0]!!.show()
    }
}
