package com.termux.shared.terminal.io.extrakeys

import android.R
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.PopupWindow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.stream.Collectors

/**
 * A [View] showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboards.
 *
 * To use it, add following to a layout file and import it in your activity layout file or inflate
 * it with a [androidx.viewpager.widget.ViewPager].:
 * `* <?xml version="1.0" encoding="utf-8"?>
 * <com.termux.shared.terminal.io.extrakeys.ExtraKeysView xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:id="@+id/extra_keys"
 *     style="?android:attr/buttonBarStyle"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:layout_alignParentBottom="true"
 *     android:orientation="horizontal" />
 * `
 *
 * Then in your activity, get its reference by a call to [android.app.Activity.findViewById]
 * or [LayoutInflater.inflate] if using [androidx.viewpager.widget.ViewPager].
 * Then call [.setExtraKeysViewClient] and pass it the implementation of
 * [IExtraKeysView] so that you can receive callbacks. You can also override other values set
 * in [ExtraKeysView.ExtraKeysView] by calling the respective functions.
 * If you extend [ExtraKeysView], you can also set them in the constructor, but do call super().
 *
 * After this you will have to make a call to [ExtraKeysView.reload] to load and display the extra keys. Read its class javadocs for more
 * info on how to create it.
 *
 * Termux app defines the view in res/layout/view_terminal_toolbar_extra_keys and
 * inflates it in TerminalToolbarViewPager.instantiateItem() and sets the [ExtraKeysView] client
 * and calls [ExtraKeysView.reload] is created by TermuxAppSharedProperties.setExtraKeys().
 * Then its got and the view height is adjusted in TermuxActivity.setTerminalToolbarHeight().
 * The client used is TermuxTerminalExtraKeys, which extends
 * [com.termux.shared.terminal.io.TerminalExtraKeys] to handle Termux app specific logic and
 * leave the rest to the super class.
 **/
class ExtraKeysView : GridLayout {

    /** The client for the [ExtraKeysView].*/
    interface IExtraKeysView {

        /**
         * This is called by [ExtraKeysView] when a button is clicked. This is also called
         * for [.mRepetitiveKeys] and [ExtraKeyButton] that have a popup set.
         * However, this is not called for [.mSpecialButtons], whose state can instead be read
         * via a call to [.readSpecialButton].
         *
         * @param view The view that was clicked.
         * @param buttonInfo The [ExtraKeyButton] for the button that was clicked.
         *                   The button may be a [ExtraKeyButton.KEY_MACRO] set which can be
         *                   checked with a call to [ExtraKeyButton.isMacro].
         * @param button The [Button] that was clicked.
         **/
        fun onExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton?, button: Button?)

        /**
         * This is called by [ExtraKeysView] when a button is clicked so that the client
         * can perform any hepatic feedback. This is only called in the [Button.OnClickListener]
         * and not for every repeat. Its also called for [.mSpecialButtons].
         *
         * @param view The view that was clicked.
         * @param buttonInfo The [ExtraKeyButton] for the button that was clicked.
         * @param button The [Button] that was clicked.
         * @return Return `true` if the client handled the feedback, otherwise `false`
         * so that [ExtraKeysView.performExtraKeyButtonHapticFeedback]
         * can handle it depending on system settings.
         **/
        fun performExtraKeyButtonHapticFeedback(view: View, buttonInfo: ExtraKeyButton?, button: Button?): Boolean
    }

    /** The implementation of the [IExtraKeysView] that acts as a client for the [ExtraKeysView].*/
    private var mExtraKeysViewClient: IExtraKeysView? = null

    /** The map for the [SpecialButton] and their [SpecialButtonState]. Defaults to
     * the one returned by [.getDefaultSpecialButtons].*/
    private var mSpecialButtons: MutableMap<SpecialButton, SpecialButtonState>? = null

    /** The keys for the [SpecialButton] added to [.mSpecialButtons]. This is automatically
     * set when the call to [.setSpecialButtons] is made.*/
    private var mSpecialButtonsKeys: MutableSet<String>? = null

    /**
     * The list of keys for which auto repeat of key should be triggered if its extra keys button
     * is long pressed. This is done by calling [IExtraKeysView.onExtraKeyButtonClick]
     * every [.mLongPressRepeatDelay] seconds after [.mLongPressTimeout] has passed.
     * The default keys are defined by [ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS].
     **/
    private var mRepetitiveKeys: MutableList<String>? = null

    /** The text color for the extra keys button. Defaults to [.DEFAULT_BUTTON_TEXT_COLOR].*/
    private var mButtonTextColor: Int = 0
    /** The text color for the extra keys button when its active.
     * Defaults to [.DEFAULT_BUTTON_ACTIVE_TEXT_COLOR].*/
    private var mButtonActiveTextColor: Int = 0
    /** The background color for the extra keys button. Defaults to [.DEFAULT_BUTTON_BACKGROUND_COLOR].*/
    private var mButtonBackgroundColor: Int = 0
    /** The background color for the extra keys button when its active. Defaults to
     * [.DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR].*/
    private var mButtonActiveBackgroundColor: Int = 0

    /** Defines whether text for the extra keys button should be all capitalized automatically.*/
    private var mButtonTextAllCaps: Boolean = true

    /**
     * Defines the duration in milliseconds before a press turns into a long press. The default
     * duration used is the one returned by a call to [ViewConfiguration.getLongPressTimeout]
     * which will return the system defined duration which can be changed in accessibility settings.
     * The duration must be in between [.MIN_LONG_PRESS_DURATION] and [.MAX_LONG_PRESS_DURATION],
     * otherwise [.FALLBACK_LONG_PRESS_DURATION] is used.
     **/
    private var mLongPressTimeout: Int = 0

    /**
     * Defines the duration in milliseconds for the delay between trigger of each repeat of
     * [.mRepetitiveKeys]. The default value is defined by [.DEFAULT_LONG_PRESS_REPEAT_DELAY].
     * The duration must be in between [.MIN_LONG_PRESS__REPEAT_DELAY] and
     * [.MAX_LONG_PRESS__REPEAT_DELAY], otherwise [.DEFAULT_LONG_PRESS_REPEAT_DELAY] is used.
     **/
    private var mLongPressRepeatDelay: Int = 0

    /** The popup window shown if [ExtraKeyButton.getPopup] returns a `non-null` value
     * and a swipe up action is done on an extra key.*/
    private var mPopupWindow: PopupWindow? = null

    private var mScheduledExecutor: ScheduledExecutorService? = null
    private var mHandler: Handler? = null
    private var mSpecialButtonsLongHoldRunnable: SpecialButtonsLongHoldRunnable? = null
    private var mLongPressCount: Int = 0

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS)
        setSpecialButtons(getDefaultSpecialButtons(this))
        setButtonColors(DEFAULT_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR)
        setLongPressTimeout(ViewConfiguration.getLongPressTimeout())
        setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY)
    }

    /** Get [.mExtraKeysViewClient].*/
    fun getExtraKeysViewClient(): IExtraKeysView? {
        return mExtraKeysViewClient
    }

    /** Set [.mExtraKeysViewClient].*/
    fun setExtraKeysViewClient(extraKeysViewClient: IExtraKeysView?) {
        mExtraKeysViewClient = extraKeysViewClient
    }

    /** Get [.mRepetitiveKeys].*/
    fun getRepetitiveKeys(): MutableList<String>? {
        if (mRepetitiveKeys == null) {
            return null
        }
        return mRepetitiveKeys!!.stream().map(Function<String, String> { s -> java.lang.String(s) as String }).collect(Collectors.toList())
    }

    /** Set [.mRepetitiveKeys]. Must not be `null`.*/
    fun setRepetitiveKeys(repetitiveKeys: MutableList<String>) {
        mRepetitiveKeys = repetitiveKeys
    }

    /** Get [.mSpecialButtons].*/
    fun getSpecialButtons(): MutableMap<SpecialButton, SpecialButtonState>? {
        if (mSpecialButtons == null) {
            return null
        }
        return mSpecialButtons!!.entries.stream().collect(Collectors.toMap(Function<MutableMap.MutableEntry<SpecialButton, SpecialButtonState>, SpecialButton> { e -> e.key }, Function<MutableMap.MutableEntry<SpecialButton, SpecialButtonState>, SpecialButtonState> { e -> e.value }))
    }

    /** Get [.mSpecialButtonsKeys].*/
    fun getSpecialButtonsKeys(): MutableSet<String>? {
        if (mSpecialButtonsKeys == null) {
            return null
        }
        return mSpecialButtonsKeys!!.stream().map(Function<String, String> { s -> java.lang.String(s) as String }).collect(Collectors.toSet())
    }

    /** Set [.mSpecialButtonsKeys]. Must not be `null`.*/
    fun setSpecialButtons(specialButtons: MutableMap<SpecialButton, SpecialButtonState>) {
        mSpecialButtons = specialButtons
        mSpecialButtonsKeys = this.mSpecialButtons!!.keys.stream().map(Function<SpecialButton, String>(SpecialButton::getKey)).collect(Collectors.toSet())
    }

    /**
     * Set the [ExtraKeysView] button colors.
     *
     * @param buttonTextColor The value for [.mButtonTextColor].
     * @param buttonActiveTextColor The value for [.mButtonActiveTextColor].
     * @param buttonBackgroundColor The value for [.mButtonBackgroundColor].
     * @param buttonActiveBackgroundColor The value for [.mButtonActiveBackgroundColor].
     **/
    fun setButtonColors(buttonTextColor: Int, buttonActiveTextColor: Int, buttonBackgroundColor: Int, buttonActiveBackgroundColor: Int) {
        mButtonTextColor = buttonTextColor
        mButtonActiveTextColor = buttonActiveTextColor
        mButtonBackgroundColor = buttonBackgroundColor
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor
    }

    /** Get [.mButtonTextColor].*/
    fun getButtonTextColor(): Int {
        return mButtonTextColor
    }

    /** Set [.mButtonTextColor].*/
    fun setButtonTextColor(buttonTextColor: Int) {
        mButtonTextColor = buttonTextColor
    }

    /** Get [.mButtonActiveTextColor].*/
    fun getButtonActiveTextColor(): Int {
        return mButtonActiveTextColor
    }

    /** Set [.mButtonActiveTextColor].*/
    fun setButtonActiveTextColor(buttonActiveTextColor: Int) {
        mButtonActiveTextColor = buttonActiveTextColor
    }

    /** Get [.mButtonBackgroundColor].*/
    fun getButtonBackgroundColor(): Int {
        return mButtonBackgroundColor
    }

    /** Set [.mButtonBackgroundColor].*/
    fun setButtonBackgroundColor(buttonBackgroundColor: Int) {
        mButtonBackgroundColor = buttonBackgroundColor
    }

    /** Get [.mButtonActiveBackgroundColor].*/
    fun getButtonActiveBackgroundColor(): Int {
        return mButtonActiveBackgroundColor
    }

    /** Set [.mButtonActiveBackgroundColor].*/
    fun setButtonActiveBackgroundColor(buttonActiveBackgroundColor: Int) {
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor
    }

    /** Set [.mButtonTextAllCaps].*/
    fun setButtonTextAllCaps(buttonTextAllCaps: Boolean) {
        mButtonTextAllCaps = buttonTextAllCaps
    }

    /** Get [.mLongPressTimeout].*/
    fun getLongPressTimeout(): Int {
        return mLongPressTimeout
    }

    /** Set [.mLongPressTimeout].*/
    fun setLongPressTimeout(longPressDuration: Int) {
        if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
            mLongPressTimeout = longPressDuration
        } else {
            mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION
        }
    }

    /** Get [.mLongPressRepeatDelay].*/
    fun getLongPressRepeatDelay(): Int {
        return mLongPressRepeatDelay
    }

    /** Set [.mLongPressRepeatDelay].*/
    fun setLongPressRepeatDelay(longPressRepeatDelay: Int) {
        if (mLongPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY && mLongPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
            mLongPressRepeatDelay = longPressRepeatDelay
        } else {
            mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY
        }
    }

    /** Get the default map that can be used for [.mSpecialButtons].*/
    fun getDefaultSpecialButtons(extraKeysView: ExtraKeysView): MutableMap<SpecialButton, SpecialButtonState> {
        return object : HashMap<SpecialButton, SpecialButtonState>() {
            init {
                put(SpecialButton.CTRL, SpecialButtonState(extraKeysView))
                put(SpecialButton.ALT, SpecialButtonState(extraKeysView))
                put(SpecialButton.SHIFT, SpecialButtonState(extraKeysView))
                put(SpecialButton.FN, SpecialButtonState(extraKeysView))
            }
        }
    }

    /**
     * Reload this instance of [ExtraKeysView] with the info passed in `extraKeysInfo`.
     *
     * @param extraKeysInfo The [ExtraKeysInfo] that defines the necessary info for the extra keys.
     **/
    @SuppressLint("ClickableViewAccessibility")
    fun reload(extraKeysInfo: ExtraKeysInfo?) {
        if (extraKeysInfo == null) {
            return
        }

        for (state in mSpecialButtons!!.values) {
            state.buttons = ArrayList<Button>()
        }

        removeAllViews()

        val buttons: Array<Array<ExtraKeyButton?>?> = extraKeysInfo.getMatrix()

        setRowCount(buttons.size)
        setColumnCount(maximumLength(buttons))

        for (row in 0 until (buttons.size)) {
            var col = 0
            while (col < buttons[row]!!.size) {
                val buttonInfo: ExtraKeyButton? = buttons[row]!![col]

                var button: Button?
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo!!.getKey(), true)
                    if (button == null) {
                        return
                    }
                } else {
                    button = Button(getContext(), null, R.attr.buttonBarButtonStyle)
                }

                button!!.setText(buttonInfo!!.getDisplay())
                button!!.setTextColor(mButtonTextColor)
                button!!.setAllCaps(mButtonTextAllCaps)
                button!!.setPadding(0, 0, 0, 0)

                button!!.setOnClickListener(View.OnClickListener { view ->
                    performExtraKeyButtonHapticFeedback(view, buttonInfo, button)
                    onAnyExtraKeyButtonClick(view, buttonInfo, button)
                })

                button!!.setOnTouchListener(View.OnTouchListener { view, event ->
                    when (event.getAction()) {
                        MotionEvent.ACTION_DOWN -> {
                            view.setBackgroundColor(mButtonActiveBackgroundColor)
                            // Start long press scheduled executors which will be stopped in next MotionEvent
                            startScheduledExecutors(view, buttonInfo, button)
                            return@OnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (buttonInfo!!.getPopup() != null) {
                                // Show popup on swipe up
                                if (mPopupWindow == null && event.getY() < 0) {
                                    stopScheduledExecutors()
                                    view.setBackgroundColor(mButtonBackgroundColor)
                                    showPopup(view, buttonInfo!!.getPopup())
                                }
                                if (mPopupWindow != null && event.getY() > 0) {
                                    view.setBackgroundColor(mButtonActiveBackgroundColor)
                                    dismissPopup()
                                }
                            }
                            return@OnTouchListener true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            return@OnTouchListener true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            // If ACTION_UP up was not from a repetitive key or was with a key with a popup button
                            if (mLongPressCount == 0 || mPopupWindow != null) {
                                // Trigger popup button click if swipe up complete
                                if (mPopupWindow != null) {
                                    dismissPopup()
                                    if (buttonInfo!!.getPopup() != null) {
                                        onAnyExtraKeyButtonClick(view, buttonInfo!!.getPopup(), button)
                                    }
                                } else {
                                    view.performClick()
                                }
                            }
                            return@OnTouchListener true
                        }
                        else -> {
                            return@OnTouchListener true
                        }
                    }
                })

                val param = GridLayout.LayoutParams()
                param.width = 0
                param.height = 0
                param.setMargins(0, 0, 0, 0)
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.0f)
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.0f)
                button!!.setLayoutParams(param)

                addView(button)
                col++
            }
        }
    }

    private fun onExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton?, button: Button?) {
        if (mExtraKeysViewClient != null) {
            mExtraKeysViewClient!!.onExtraKeyButtonClick(view, buttonInfo, button)
        }
    }

    private fun performExtraKeyButtonHapticFeedback(view: View, buttonInfo: ExtraKeyButton?, button: Button?) {
        if (mExtraKeysViewClient != null) {
            // If client handled the feedback, then just return
            if (mExtraKeysViewClient!!.performExtraKeyButtonHapticFeedback(view, buttonInfo, button)) {
                return
            }
        }

        if (Settings.System.getInt(getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                button!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else {
                // Perform haptic feedback only if no total silence mode enabled.
                if (Settings.Global.getInt(getContext().getContentResolver(), "zen_mode", 0) != 2) {
                    button!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
    }

    private fun onAnyExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton?, button: Button?) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) {
                return
            }
            val state: SpecialButtonState? = mSpecialButtons!!.get(SpecialButton.valueOf(buttonInfo!!.getKey()))
            if (state == null) {
                return
            }

            // Toggle active state and disable lock state if new state is not active
            state.setIsActive(!state.isActive)
            if (!state.isActive) {
                state.setIsLocked(false)
            }
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button)
        }
    }

    private fun startScheduledExecutors(view: View, buttonInfo: ExtraKeyButton?, button: Button?) {
        stopScheduledExecutors()
        mLongPressCount = 0
        if (mRepetitiveKeys!!.contains(buttonInfo!!.getKey())) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors.
            // Currently, only one (last) repeat key can run at a time. Old ones are stopped.
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            mScheduledExecutor!!.scheduleWithFixedDelay(Runnable {
                mLongPressCount++
                onExtraKeyButtonClick(view, buttonInfo, button)
            }, mLongPressTimeout.toLong(), mLongPressRepeatDelay.toLong(), TimeUnit.MILLISECONDS)
        } else if (isSpecialButton(buttonInfo)) {
            // Lock the key if long pressed by running mSpecialButtonsLongHoldRunnable after
            // waiting for mLongPressTimeout milliseconds. If user does not long press, then the
            // ACTION_UP triggered will cancel the runnable by calling stopScheduledExecutors before
            // it has a chance to run.
            val state: SpecialButtonState? = mSpecialButtons!!.get(SpecialButton.valueOf(buttonInfo!!.getKey()))
            if (state == null) {
                return
            }
            if (mHandler == null) {
                mHandler = Handler(Looper.getMainLooper())
            }
            mSpecialButtonsLongHoldRunnable = SpecialButtonsLongHoldRunnable(state)
            mHandler!!.postDelayed(mSpecialButtonsLongHoldRunnable!!, mLongPressTimeout.toLong())
        }
    }

    private fun stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor!!.shutdownNow()
            mScheduledExecutor = null
        }

        if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
            mHandler!!.removeCallbacks(mSpecialButtonsLongHoldRunnable!!)
            mSpecialButtonsLongHoldRunnable = null
        }
    }

    private open inner class SpecialButtonsLongHoldRunnable : Runnable {
        private val mState: SpecialButtonState

        constructor(state: SpecialButtonState) {
            mState = state
        }

        override fun run() {
            // Toggle active and lock state
            mState.setIsLocked(!mState.isActive)
            mState.setIsActive(!mState.isActive)
            mLongPressCount++
        }
    }

    fun showPopup(view: View, extraButton: ExtraKeyButton?) {
        val width: Int = view.getMeasuredWidth()
        val height: Int = view.getMeasuredHeight()
        var button: Button?
        if (isSpecialButton(extraButton)) {
            button = createSpecialButton(extraButton!!.getKey(), false)
            if (button == null) {
                return
            }
        } else {
            button = Button(getContext(), null, R.attr.buttonBarButtonStyle)
            button!!.setTextColor(mButtonTextColor)
        }
        button!!.setText(extraButton!!.getDisplay())
        button!!.setAllCaps(mButtonTextAllCaps)
        button!!.setPadding(0, 0, 0, 0)
        button!!.setMinHeight(0)
        button!!.setMinWidth(0)
        button!!.setMinimumWidth(0)
        button!!.setMinimumHeight(0)
        button!!.setWidth(width)
        button!!.setHeight(height)
        button!!.setBackgroundColor(mButtonActiveBackgroundColor)
        mPopupWindow = PopupWindow(this)
        mPopupWindow!!.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
        mPopupWindow!!.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        mPopupWindow!!.setContentView(button)
        mPopupWindow!!.setOutsideTouchable(true)
        mPopupWindow!!.setFocusable(false)
        mPopupWindow!!.showAsDropDown(view, 0, -2 * height)
    }

    private fun dismissPopup() {
        mPopupWindow!!.setContentView(null)
        mPopupWindow!!.dismiss()
        mPopupWindow = null
    }

    /** Check whether a [ExtraKeyButton] is a [SpecialButton].*/
    fun isSpecialButton(button: ExtraKeyButton?): Boolean {
        return mSpecialButtonsKeys!!.contains(button!!.getKey())
    }

    /**
     * Read whether [SpecialButton] registered in [.mSpecialButtons] is active or not.
     *
     * @param specialButton The [SpecialButton] to read.
     * @param autoSetInActive Set to `true` if [SpecialButtonState.isActive] should be
     *                        set `false` if button is not locked.
     * @return Returns `null` if button does not exist in [.mSpecialButtons]. If button
     *         exists, then returns `true` if the button is created in [ExtraKeysView]
     *         and is active, otherwise `false`.
     **/
    fun readSpecialButton(specialButton: SpecialButton, autoSetInActive: Boolean): Boolean? {
        val state: SpecialButtonState? = mSpecialButtons!!.get(specialButton)
        if (state == null) {
            return null
        }

        if (!state.isCreated || !state.isActive) {
            return false
        }

        // Disable active state only if not locked
        if (autoSetInActive && !state.isLocked) {
            state.setIsActive(false)
        }

        return true
    }

    private fun createSpecialButton(buttonKey: String, needUpdate: Boolean): Button? {
        val state: SpecialButtonState? = mSpecialButtons!!.get(SpecialButton.valueOf(buttonKey))
        if (state == null) {
            return null
        }
        state.setIsCreated(true)
        val button = Button(getContext(), null, R.attr.buttonBarButtonStyle)
        button.setTextColor(if (state.isActive) mButtonActiveTextColor else mButtonTextColor)
        if (needUpdate) {
            state.buttons.add(button)
        }
        return button
    }

    companion object {

        /** Defines the default value for [.mButtonTextColor].*/
        const val DEFAULT_BUTTON_TEXT_COLOR: Int = 0xFFFFFFFF.toInt()
        /** Defines the default value for [.mButtonActiveTextColor].*/
        const val DEFAULT_BUTTON_ACTIVE_TEXT_COLOR: Int = 0xFF80DEEA.toInt()
        /** Defines the default value for [.mButtonBackgroundColor].*/
        const val DEFAULT_BUTTON_BACKGROUND_COLOR: Int = 0x00000000
        /** Defines the default value for [.mButtonActiveBackgroundColor].*/
        const val DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR: Int = 0xFF7F7F7F.toInt()

        /** Defines the minimum allowed duration in milliseconds for [.mLongPressTimeout].*/
        const val MIN_LONG_PRESS_DURATION: Int = 200
        /** Defines the maximum allowed duration in milliseconds for [.mLongPressTimeout].*/
        const val MAX_LONG_PRESS_DURATION: Int = 3000
        /** Defines the fallback duration in milliseconds for [.mLongPressTimeout].*/
        const val FALLBACK_LONG_PRESS_DURATION: Int = 400

        /** Defines the minimum allowed duration in milliseconds for [.mLongPressRepeatDelay].*/
        const val MIN_LONG_PRESS__REPEAT_DELAY: Int = 5
        /** Defines the maximum allowed duration in milliseconds for [.mLongPressRepeatDelay].*/
        const val MAX_LONG_PRESS__REPEAT_DELAY: Int = 2000
        /** Defines the default duration in milliseconds for [.mLongPressRepeatDelay].*/
        const val DEFAULT_LONG_PRESS_REPEAT_DELAY: Int = 80

        /**
         * General util function to compute the longest column length in a matrix.
         **/
        @JvmStatic
        fun maximumLength(matrix: Array<out Array<out Any?>?>): Int {
            var m = 0
            for (row in matrix) {
                m = Math.max(m, row!!.size)
            }
            return m
        }
    }
}
