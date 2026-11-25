package com.termux.app.workspace

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.termux.R
import java.util.function.BooleanSupplier

/** Presentation only: moves the existing host, never starts/stops an X11 process.*/
class DesktopFullscreen {
    private val activity: Activity
    private val keyboard: Runnable
    private val keyboardEnabled: BooleanSupplier
    private var inputButton: WorkspaceIconButton? = null
    private var keysButton: WorkspaceIconButton? = null
    private val extraKeys: Runnable
    private val extraKeysEnabled: BooleanSupplier
    private var controlsRow: LinearLayout? = null
    private val decor: View
    private val imeObserver: ViewTreeObserver.OnPreDrawListener
    private val layout: View.OnLayoutChangeListener = View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob -> schedule() }
    private var content: View? = null
    private var home: ViewGroup? = null
    private var homeParams: ViewGroup.LayoutParams? = null
    private var homeIndex: Int = 0
    private var originalOrientation: Int = 0
    private var observing: Boolean = false
    private var suppressed: Boolean = false
    private var moving: Boolean = false
    private var queued: Boolean = false
    private var closed: Boolean = false
    private var dialog: Dialog? = null
    private var canvas: FrameLayout? = null
    private var controls: Dialog? = null

    constructor(activity: Activity, keyboard: Runnable, keyboardEnabled: BooleanSupplier, observeIme: Runnable) : this(activity, keyboard, keyboardEnabled, observeIme, Runnable {  }, BooleanSupplier { false })

    constructor(activity: Activity, keyboard: Runnable, keyboardEnabled: BooleanSupplier, observeIme: Runnable, extraKeys: Runnable, extraKeysEnabled: BooleanSupplier) {
        this.activity = activity
        this.keyboard = keyboard
        this.keyboardEnabled = keyboardEnabled
        this.extraKeys = extraKeys
        this.extraKeysEnabled = extraKeysEnabled
        imeObserver = ViewTreeObserver.OnPreDrawListener {
            observeIme.run()
            refreshKeyboard()
            return@OnPreDrawListener true
        }
        decor = activity.getWindow().getDecorView()
    }

    fun sync(next: View?) {
        if (closed) {
            return
        }
        if (next !== content) {
            leave()
            content = next
            suppressed = false
        }
        if (content == null) {
            stopObserving()
            return
        }
        if (!observing) {
            observing = true
            originalOrientation = activity.getRequestedOrientation()
            decor.addOnLayoutChangeListener(layout)
            // Limited to the visible desktop. Restore the application's policy on exit.
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        }
        update()
    }

    private fun schedule() {
        if (queued || closed || !observing) {
            return
        }
        queued = true
        decor.post(Runnable {
            queued = false
            if (!closed && observing) {
                update()
            }
        })
    }

    private fun update() {
        if (moving || content == null || activity.isFinishing() || activity.isDestroyed()) {
            return
        }
        val landscape: Boolean = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) {
            suppressed = false
            leave()
        } else if (!suppressed && dialog == null && content!!.getParent() is ViewGroup) {
            enter()
        }
    }

    private fun enter() {
        moving = true
        home = content!!.getParent() as ViewGroup?
        homeIndex = home!!.indexOfChild(content)
        homeParams = content!!.getLayoutParams()
        home!!.removeView(content)
        canvas = FrameLayout(activity)
        canvas!!.setBackgroundColor(Color.BLACK)
        canvas!!.setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener { v, insets ->
            positionControls(insets)
            return@OnApplyWindowInsetsListener insets
        })
        canvas!!.addView(content, FrameLayout.LayoutParams(-1, -1))
        val next = Dialog(activity)
        dialog = next
        next.requestWindowFeature(Window.FEATURE_NO_TITLE)
        next.setContentView(canvas!!)
        next.setCanceledOnTouchOutside(false)
        next.setOnCancelListener(DialogInterface.OnCancelListener { d ->
            if (dialog === next) {
                suppressed = true
                leave()
            }
        })
        next.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (dialog === next) {
                suppressed = true
                leave()
            }
        })
        val window: Window? = next.getWindow()
        window!!.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 28) {
            val attributes: WindowManager.LayoutParams = window.getAttributes()
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.setAttributes(attributes)
        }
        // Immersive desktop; the separate input control is shown after this window.
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        next.show()
        window.setLayout(-1, -1)
        window.getDecorView().getViewTreeObserver().addOnPreDrawListener(imeObserver)
        showControls(window.getDecorView())
        moving = false
    }

    private fun showControls(anchor: View) {
        val row = LinearLayout(activity)
        controlsRow = row
        row.setPadding(dp(2), dp(2), dp(2), dp(2))
        row.setBackground(WorkspaceDialogs.background(activity, false))
        inputButton = addControl(row, R.drawable.workspace_interactive_input, tr("Интерактивный ввод", "Interactive input"), Runnable {
            keyboard.run()
            refreshKeyboard()
        })
        keysButton = addControl(row, R.drawable.workspace_extra_keys, tr("Дополнительные клавиши", "Additional keys"), Runnable {
            extraKeys.run()
            refreshKeyboard()
        })
        refreshKeyboard()
        // A sibling application window is above the embedded Z-on-top SurfaceView.
        // A non-focusable PopupWindow subwindow can remain below that surface.
        // Keep focus/IME ownership in the desktop window and pass outside touches through.
        controls = Dialog(activity)
        controls!!.requestWindowFeature(Window.FEATURE_NO_TITLE)
        controls!!.setContentView(row)
        controls!!.setCancelable(false)
        controls!!.setCanceledOnTouchOutside(false)
        val window: Window? = controls!!.getWindow()
        window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.getDecorView().setSystemUiVisibility(anchor.getSystemUiVisibility())
        val attributes: WindowManager.LayoutParams = window.getAttributes()
        attributes.gravity = Gravity.TOP or Gravity.RIGHT
        attributes.alpha = 0.65f
        val offset: IntArray = controlOffset(anchor.getRootWindowInsets())
        attributes.x = offset[0]
        attributes.y = offset[1]
        if (Build.VERSION.SDK_INT >= 28) {
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setAttributes(attributes)
        controls!!.show()
        window.setLayout(dp(84), dp(44))
    }

    private fun controlOffset(insets: WindowInsets?): IntArray {
        var right = 0
        var top = 0
        if (Build.VERSION.SDK_INT >= 28 && insets != null && insets.getDisplayCutout() != null) {
            right = insets.getDisplayCutout()!!.getSafeInsetRight()
            top = insets.getDisplayCutout()!!.getSafeInsetTop()
        }
        return intArrayOf(dp(8) + right, dp(48) + top)
    }

    private fun positionControls(insets: WindowInsets) {
        if (controls == null || !controls!!.isShowing()) {
            return
        }
        val offset: IntArray = controlOffset(insets)
        val window: Window? = controls!!.getWindow()
        val attributes: WindowManager.LayoutParams = window!!.getAttributes()
        if (attributes.x == offset[0] && attributes.y == offset[1]) {
            return
        }
        attributes.x = offset[0]
        attributes.y = offset[1]
        window.setAttributes(attributes)
    }

    private fun refreshKeyboard() {
        if (inputButton == null) {
            return
        }
        val enabled: Boolean = keyboardEnabled.getAsBoolean()
        val keys: Boolean = extraKeysEnabled.getAsBoolean()
        if (inputButton!!.isSelected() == enabled && keysButton != null && keysButton!!.isSelected() == keys) {
            return
        }
        inputButton!!.setSelected(enabled)
        inputButton!!.setBackground(WorkspaceDialogs.background(activity, enabled))
        if (keysButton != null) {
            keysButton!!.setSelected(keys)
            keysButton!!.setBackground(WorkspaceDialogs.background(activity, keys))
        }
        controlsRow!!.setBackground(WorkspaceDialogs.background(activity, enabled || keys))
    }

    private fun addControl(row: LinearLayout, icon: Int, description: String, action: Runnable): WorkspaceIconButton {
        val button = WorkspaceIconButton(activity)
        button.setTextColor(Pal.ACCENT)
        button.setIcon(AppCompatResources.getDrawable(activity, icon)!!.mutate(), dp(22))
        button.setContentDescription(description)
        button.setOnClickListener(View.OnClickListener { v -> action.run() })
        row.addView(button, LinearLayout.LayoutParams(dp(40), dp(40)))
        return button
    }

    fun back(): Boolean {
        if (dialog == null) {
            return false
        }
        suppressed = true
        leave()
        return true
    }

    fun isFullscreen(): Boolean {
        return dialog != null
    }

    private fun leave() {
        if (dialog == null) {
            return
        }
        moving = true
        val old: Dialog? = dialog
        dialog = null
        if (controls != null) {
            controls!!.dismiss()
            controls = null
        }
        inputButton = null
        keysButton = null
        controlsRow = null
        // Do not resurrect a host that its owner has already removed/closed.
        if (content != null && content!!.getParent() === canvas) {
            canvas!!.removeView(content)
            if (home != null) {
                home!!.addView(content, Math.min(homeIndex, home!!.getChildCount()), homeParams)
            }
        }
        val observer: ViewTreeObserver = old!!.getWindow()!!.getDecorView().getViewTreeObserver()
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(imeObserver)
        }
        old.setOnCancelListener(null)
        old.setOnDismissListener(null)
        old.dismiss()
        canvas = null
        home = null
        homeParams = null
        moving = false
    }

    private fun stopObserving() {
        if (!observing) {
            return
        }
        observing = false
        decor.removeOnLayoutChangeListener(layout)
        activity.setRequestedOrientation(originalOrientation)
    }

    fun close() {
        if (closed) {
            return
        }
        leave()
        content = null
        stopObserving()
        closed = true
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }
}
