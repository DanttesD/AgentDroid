package com.termux.shared.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.termux.shared.R
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.TextIOInfo
import com.termux.shared.view.KeyboardUtils
import java.util.Locale

/**
 * An activity to edit or view text based on config passed as [TextIOInfo].
 *
 * Add Following to `AndroidManifest.xml` to use in an app:
 *
 * `` <activity android:name="com.termux.shared.activities.TextIOActivity" android:theme="@style/Theme.AppCompat.TermuxTextIOActivity" />` `
 **/
open class TextIOActivity : AppCompatActivity() {

    private var mTextIOLabel: TextView? = null
    private var mTextIOLabelSeparator: View? = null
    private var mTextIOText: EditText? = null
    private var mTextIOHorizontalScrollView: HorizontalScrollView? = null
    private var mTextIOTextLinearLayout: LinearLayout? = null
    private var mTextIOTextCharacterUsage: TextView? = null

    private var mTextIOInfo: TextIOInfo? = null
    private var mBundle: Bundle? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.logVerbose(LOG_TAG, "onCreate")

        setContentView(R.layout.activity_text_io)

        mTextIOLabel = findViewById(R.id.text_io_label)
        mTextIOLabelSeparator = findViewById(R.id.text_io_label_separator)
        mTextIOText = findViewById(R.id.text_io_text)
        mTextIOHorizontalScrollView = findViewById(R.id.text_io_horizontal_scroll_view)
        mTextIOTextLinearLayout = findViewById(R.id.text_io_text_linear_layout)
        mTextIOTextCharacterUsage = findViewById(R.id.text_io_text_character_usage)

        val toolbar: Toolbar? = findViewById(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
        }

        mBundle = null
        val intent: Intent? = getIntent()
        if (intent != null) {
            mBundle = intent.getExtras()
        } else if (savedInstanceState != null) {
            mBundle = savedInstanceState
        }

        updateUI()
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logger.logVerbose(LOG_TAG, "onNewIntent")

        // Views must be re-created since different configs for isEditingTextDisabled() and
        // isHorizontallyScrollable() will not work or at least reliably
        finish()
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateUI() {
        if (mBundle == null) {
            finish()
            return
        }

        mTextIOInfo = mBundle!!.getSerializable(EXTRA_TEXT_IO_INFO_OBJECT) as TextIOInfo?
        if (mTextIOInfo == null) {
            finish()
            return
        }

        val actionBar: ActionBar? = getSupportActionBar()
        if (actionBar != null) {
            if (mTextIOInfo!!.getTitle() != null) {
                actionBar.setTitle(mTextIOInfo!!.getTitle())
            } else {
                actionBar.setTitle("Text Input")
            }

            if (mTextIOInfo!!.shouldShowBackButtonInActionBar()) {
                actionBar.setDisplayHomeAsUpEnabled(true)
                actionBar.setDisplayShowHomeEnabled(true)
            }
        }

        mTextIOLabel!!.setVisibility(View.GONE)
        mTextIOLabelSeparator!!.setVisibility(View.GONE)
        if (mTextIOInfo!!.isLabelEnabled()) {
            mTextIOLabel!!.setVisibility(View.VISIBLE)
            mTextIOLabelSeparator!!.setVisibility(View.VISIBLE)
            mTextIOLabel!!.setText(mTextIOInfo!!.getLabel())
            mTextIOLabel!!.setFilters(arrayOf<InputFilter>(InputFilter.LengthFilter(TextIOInfo.LABEL_SIZE_LIMIT_IN_BYTES)))
            mTextIOLabel!!.setTextSize(mTextIOInfo!!.getLabelSize().toFloat())
            mTextIOLabel!!.setTextColor(mTextIOInfo!!.getLabelColor())
            mTextIOLabel!!.setTypeface(Typeface.create(mTextIOInfo!!.getLabelTypeFaceFamily(), mTextIOInfo!!.getLabelTypeFaceStyle()))
        }

        if (mTextIOInfo!!.isHorizontallyScrollable()) {
            mTextIOHorizontalScrollView!!.setEnabled(true)
            mTextIOText!!.setHorizontallyScrolling(true)
        } else {
            // Remove mTextIOHorizontalScrollView and add mTextIOText in its place
            val parent: ViewGroup? = mTextIOHorizontalScrollView!!.getParent() as ViewGroup
            if (parent != null && parent.indexOfChild(mTextIOText) < 0) {
                val params: ViewGroup.LayoutParams = mTextIOHorizontalScrollView!!.getLayoutParams()
                val index: Int = parent.indexOfChild(mTextIOHorizontalScrollView)
                mTextIOTextLinearLayout!!.removeAllViews()
                mTextIOHorizontalScrollView!!.removeAllViews()
                parent.removeView(mTextIOHorizontalScrollView)
                parent.addView(mTextIOText, index, params)
                mTextIOText!!.setHorizontallyScrolling(false)
            }
        }

        mTextIOText!!.setText(mTextIOInfo!!.getText())
        mTextIOText!!.setFilters(arrayOf<InputFilter>(InputFilter.LengthFilter(mTextIOInfo!!.getTextLengthLimit())))
        mTextIOText!!.setTextSize(mTextIOInfo!!.getTextSize().toFloat())
        mTextIOText!!.setTextColor(mTextIOInfo!!.getTextColor())
        mTextIOText!!.setTypeface(Typeface.create(mTextIOInfo!!.getTextTypeFaceFamily(), mTextIOInfo!!.getTextTypeFaceStyle()))

        // setTextIsSelectable must be called after changing KeyListener to regain focusability and selectivity
        if (mTextIOInfo!!.isEditingTextDisabled()) {
            mTextIOText!!.setCursorVisible(false)
            mTextIOText!!.setKeyListener(null)
            mTextIOText!!.setTextIsSelectable(true)
        }

        if (mTextIOInfo!!.shouldShowTextCharacterUsage()) {
            mTextIOTextCharacterUsage!!.setVisibility(View.VISIBLE)
            updateTextIOTextCharacterUsage(mTextIOInfo!!.getText())

            mTextIOText!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(editable: Editable?) {
                    if (editable != null) {
                        updateTextIOTextCharacterUsage(editable.toString())
                    }
                }
            })
        } else {
            mTextIOTextCharacterUsage!!.setVisibility(View.GONE)
            mTextIOText!!.addTextChangedListener(null)
        }
    }

    private fun updateTextIOInfoText() {
        if (mTextIOText != null) {
            mTextIOInfo!!.setText(mTextIOText!!.getText().toString())
        }
    }

    private fun updateTextIOTextCharacterUsage(text: String?) {
        var text = text
        if (text == null) {
            text = ""
        }
        if (mTextIOTextCharacterUsage != null) {
            mTextIOTextCharacterUsage!!.setText(java.lang.String.format(Locale.getDefault(), "%1\$d/%2\$d", text!!.length, mTextIOInfo!!.getTextLengthLimit()))
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        updateTextIOInfoText()
        outState.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, mTextIOInfo)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = getMenuInflater()
        inflater.inflate(R.menu.menu_text_io, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var text = ""
        if (mTextIOText != null) {
            text = mTextIOText!!.getText().toString()
        }

        val id: Int = item.getItemId()
        if (id == android.R.id.home) {
            confirm()
        }
        if (id == R.id.menu_item_cancel) {
            cancel()
        } else if (id == R.id.menu_item_share_text) {
            ShareUtils.shareText(this, mTextIOInfo!!.getTitle(), text)
        } else if (id == R.id.menu_item_copy_text) {
            ShareUtils.copyTextToClipboard(this, text, null)
        }

        return false
    }

    override fun onBackPressed() {
        confirm()
    }

    /** Confirm current text and send it back to calling [Activity].*/
    private fun confirm() {
        updateTextIOInfoText()
        KeyboardUtils.hideSoftKeyboard(this, mTextIOText)
        setResult(Activity.RESULT_OK, getResultIntent())
        finish()
    }

    /** Cancel current text and notify calling [Activity].*/
    private fun cancel() {
        KeyboardUtils.hideSoftKeyboard(this, mTextIOText)
        setResult(Activity.RESULT_CANCELED, getResultIntent())
        finish()
    }

    private fun getResultIntent(): Intent {
        val intent = Intent()
        val bundle = Bundle()
        bundle.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, mTextIOInfo)
        intent.putExtras(bundle)
        return intent
    }

    companion object {

        private val CLASS_NAME: String? = ReportActivity::class.java.getCanonicalName()
        @JvmField val EXTRA_TEXT_IO_INFO_OBJECT: String = CLASS_NAME + ".EXTRA_TEXT_IO_INFO_OBJECT"

        private const val LOG_TAG: String = "TextIOActivity"

        /**
         * Get the [Intent] that can be used to start the [TextIOActivity].
         *
         * @param context The [Context] for operations.
         * @param textIOInfo The [TextIOInfo] containing info for the edit text.
         **/
        @JvmStatic
        fun newInstance(context: Context, textIOInfo: TextIOInfo): Intent {
            val intent = Intent(context, TextIOActivity::class.java)
            val bundle = Bundle()
            bundle.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, textIOInfo)
            intent.putExtras(bundle)
            return intent
        }
    }
}
