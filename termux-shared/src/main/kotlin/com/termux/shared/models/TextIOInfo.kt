package com.termux.shared.models

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.Keep
import com.termux.shared.data.DataUtils
import java.io.Serializable

/**
 * An object that stored info for [TextIOActivity].
 * Max text limit is 95KB to prevent TransactionTooLargeException as per
 * [DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES]. Larger size can be supported for in-app
 * transactions by storing [TextIOInfo] as a serialized object in a file like
 * [com.termux.shared.activities.ReportActivity] does.
 **/
open class TextIOInfo : Serializable {

    /** The action for which [TextIOActivity] will be started.*/
    private val mAction: String
    /** The internal app component that is will start the [TextIOActivity].*/
    private val mSender: String

    /** The activity title.*/
    private var mTitle: String? = null

    /** If back button should be shown in [android.app.ActionBar].*/
    private var mShowBackButtonInActionBar: Boolean = false

    /** If label is enabled.*/
    private var mLabelEnabled: Boolean = false
    /**
     * The label of text input set in [android.widget.TextView] that can be updated by user.
     * Max allowed length is [.LABEL_SIZE_LIMIT_IN_BYTES].
     **/
    private var mLabel: String? = null
    /** The text size of label. Defaults to 14sp.*/
    private var mLabelSize: Int = 14
    /** The text color of label. Defaults to [Color.BLACK].*/
    private var mLabelColor: Int = Color.BLACK
    /** The [Typeface] family  of label. Defaults to "sans-serif".*/
    private var mLabelTypeFaceFamily: String = "sans-serif"
    /** The [Typeface] style  of label. Defaults to [Typeface.BOLD].*/
    private var mLabelTypeFaceStyle: Int = Typeface.BOLD

    /**
     * The text of text input set in [android.widget.EditText] that can be updated by user.
     * Max allowed length is [.TEXT_SIZE_LIMIT_IN_BYTES].
     **/
    private var mText: String? = null
    /** The text size for text. Defaults to 12sp.*/
    private var mTextSize: Int = 12
    /** The text size for text. Defaults to [.TEXT_SIZE_LIMIT_IN_BYTES].*/
    private var mTextLengthLimit: Int = TEXT_SIZE_LIMIT_IN_BYTES
    /** The text color of text. Defaults to [Color.BLACK].*/
    private var mTextColor: Int = Color.BLACK
    /** The [Typeface] family for text. Defaults to "sans-serif".*/
    private var mTextTypeFaceFamily: String = "sans-serif"
    /** The [Typeface] style for text. Defaults to [Typeface.NORMAL].*/
    private var mTextTypeFaceStyle: Int = Typeface.NORMAL
    /** If horizontal scrolling should be enabled for text.*/
    private var mTextHorizontallyScrolling: Boolean = false
    /** If character usage should be enabled for text.*/
    private var mShowTextCharacterUsage: Boolean = false
    /** If editing text should be disabled so that text acts like its in a [android.widget.TextView].*/
    private var mEditingTextDisabled: Boolean = false

    constructor(action: String, sender: String) {
        mAction = action
        mSender = sender
    }

    open fun getAction(): String {
        return mAction
    }

    open fun getSender(): String {
        return mSender
    }

    open fun getTitle(): String? {
        return mTitle
    }

    open fun setTitle(title: String) {
        mTitle = title
    }

    open fun shouldShowBackButtonInActionBar(): Boolean {
        return mShowBackButtonInActionBar
    }

    open fun setShowBackButtonInActionBar(showBackButtonInActionBar: Boolean) {
        mShowBackButtonInActionBar = showBackButtonInActionBar
    }

    open fun isLabelEnabled(): Boolean {
        return mLabelEnabled
    }

    open fun setLabelEnabled(labelEnabled: Boolean) {
        mLabelEnabled = labelEnabled
    }

    open fun getLabel(): String? {
        return mLabel
    }

    open fun setLabel(label: String) {
        mLabel = DataUtils.getTruncatedCommandOutput(label, LABEL_SIZE_LIMIT_IN_BYTES, true, false, false)
    }

    open fun getLabelSize(): Int {
        return mLabelSize
    }

    open fun setLabelSize(labelSize: Int) {
        if (labelSize > 0) {
            mLabelSize = labelSize
        }
    }

    open fun getLabelColor(): Int {
        return mLabelColor
    }

    open fun setLabelColor(labelColor: Int) {
        mLabelColor = labelColor
    }

    open fun getLabelTypeFaceFamily(): String {
        return mLabelTypeFaceFamily
    }

    open fun setLabelTypeFaceFamily(labelTypeFaceFamily: String) {
        mLabelTypeFaceFamily = labelTypeFaceFamily
    }

    open fun getLabelTypeFaceStyle(): Int {
        return mLabelTypeFaceStyle
    }

    open fun setLabelTypeFaceStyle(labelTypeFaceStyle: Int) {
        mLabelTypeFaceStyle = labelTypeFaceStyle
    }

    open fun getText(): String? {
        return mText
    }

    open fun setText(text: String) {
        mText = DataUtils.getTruncatedCommandOutput(text, TEXT_SIZE_LIMIT_IN_BYTES, true, false, false)
    }

    open fun getTextSize(): Int {
        return mTextSize
    }

    open fun setTextSize(textSize: Int) {
        if (textSize > 0) {
            mTextSize = textSize
        }
    }

    open fun getTextLengthLimit(): Int {
        return mTextLengthLimit
    }

    open fun setTextLengthLimit(textLengthLimit: Int) {
        if (textLengthLimit < TEXT_SIZE_LIMIT_IN_BYTES) {
            mTextLengthLimit = textLengthLimit
        }
    }

    open fun getTextColor(): Int {
        return mTextColor
    }

    open fun setTextColor(textColor: Int) {
        mTextColor = textColor
    }

    open fun getTextTypeFaceFamily(): String {
        return mTextTypeFaceFamily
    }

    open fun setTextTypeFaceFamily(textTypeFaceFamily: String) {
        mTextTypeFaceFamily = textTypeFaceFamily
    }

    open fun getTextTypeFaceStyle(): Int {
        return mTextTypeFaceStyle
    }

    open fun setTextTypeFaceStyle(textTypeFaceStyle: Int) {
        mTextTypeFaceStyle = textTypeFaceStyle
    }

    open fun isHorizontallyScrollable(): Boolean {
        return mTextHorizontallyScrolling
    }

    open fun setTextHorizontallyScrolling(textHorizontallyScrolling: Boolean) {
        mTextHorizontallyScrolling = textHorizontallyScrolling
    }

    open fun shouldShowTextCharacterUsage(): Boolean {
        return mShowTextCharacterUsage
    }

    open fun setShowTextCharacterUsage(showTextCharacterUsage: Boolean) {
        mShowTextCharacterUsage = showTextCharacterUsage
    }

    open fun isEditingTextDisabled(): Boolean {
        return mEditingTextDisabled
    }

    open fun setEditingTextDisabled(editingTextDisabled: Boolean) {
        mEditingTextDisabled = editingTextDisabled
    }

    companion object {

        /**
         * Explicitly define `serialVersionUID` to prevent exceptions on deserialization.
         *
         * Like when calling `Bundle.getSerializable()` on Android.
         * `android.os.BadParcelableException: Parcelable encountered IOException reading a Serializable object` (name = <class_name>)
         * `java.io.InvalidClassException: <class_name>; local class incompatible`
         *
         * The `@Keep` annotation is necessary to prevent the field from being removed by proguard when
         * app is compiled, even if its kept during library compilation.
         *
         * **See Also:**
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/version.html#a6678
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/class.html#a4100
         **/
        @Keep
        private const val serialVersionUID: Long = 1L

        const val GENERAL_DATA_SIZE_LIMIT_IN_BYTES: Int = 1000
        const val LABEL_SIZE_LIMIT_IN_BYTES: Int = 4000
        const val TEXT_SIZE_LIMIT_IN_BYTES: Int = 100000 - GENERAL_DATA_SIZE_LIMIT_IN_BYTES - LABEL_SIZE_LIMIT_IN_BYTES // < 100KB
    }
}
