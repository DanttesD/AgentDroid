package com.termux.shared.terminal.io.extrakeys

import android.widget.Button
import java.util.function.Consumer

/** The [Class] that maintains a state of a [SpecialButton]*/
open class SpecialButtonState {

    /** If special button has been created for the [ExtraKeysView].*/
    @JvmField var isCreated: Boolean = false
    /** If special button is active.*/
    @JvmField var isActive: Boolean = false
    /** If special button is locked due to long hold on it and should not be deactivated if its
     * state is read.*/
    @JvmField var isLocked: Boolean = false

    @JvmField var buttons: MutableList<Button> = ArrayList<Button>()

    @JvmField var mExtraKeysView: ExtraKeysView

    /**
     * Initialize a [SpecialButtonState] to maintain state of a [SpecialButton].
     *
     * @param extraKeysView The [ExtraKeysView] instance in which the [SpecialButton]
     *                      is to be registered.
     **/
    constructor(extraKeysView: ExtraKeysView) {
        mExtraKeysView = extraKeysView
    }

    /** Set [.isCreated].*/
    open fun setIsCreated(value: Boolean) {
        isCreated = value
    }

    /** Set [.isActive].*/
    open fun setIsActive(value: Boolean) {
        isActive = value
        buttons.forEach(Consumer<Button> { button ->
            button.setTextColor(if (value) mExtraKeysView.getButtonActiveTextColor() else mExtraKeysView.getButtonTextColor())
        })
    }

    /** Set [.isLocked].*/
    open fun setIsLocked(value: Boolean) {
        isLocked = value
    }
}
