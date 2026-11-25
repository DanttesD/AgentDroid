package com.termux.app.terminal.io

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.terminal.TerminalSession

open class TerminalToolbarViewPager {

    open class PageAdapter : PagerAdapter {

        @JvmField val mActivity: TermuxActivity
        @JvmField var mSavedTextInput: String? = null

        constructor(activity: TermuxActivity, savedTextInput: String?) : super() {
            this.mActivity = activity
            this.mSavedTextInput = savedTextInput
        }

        override fun getCount(): Int {
            return 2
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }

        override fun instantiateItem(collection: ViewGroup, position: Int): Any {
            val inflater: LayoutInflater = LayoutInflater.from(mActivity)
            var layout: View
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false)
                val extraKeysView: ExtraKeysView = layout as ExtraKeysView
                extraKeysView.setExtraKeysViewClient(TermuxTerminalExtraKeys(mActivity.getTerminalView(), mActivity.getTermuxTerminalViewClient(), mActivity.getTermuxTerminalSessionClient()))
                extraKeysView.setButtonTextAllCaps(mActivity.getProperties()!!.shouldExtraKeysTextBeAllCaps())
                mActivity.setExtraKeysView(extraKeysView)
                extraKeysView.reload(mActivity.getProperties()!!.getExtraKeysInfo())

                // apply extra keys fix if enabled in prefs
                if (mActivity.getProperties()!!.isUsingFullScreen() && mActivity.getProperties()!!.isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity)
                }
            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false)
                val editText: EditText? = layout.findViewById(R.id.terminal_toolbar_text_input)

                if (mSavedTextInput != null) {
                    editText!!.setText(mSavedTextInput)
                    mSavedTextInput = null
                }

                editText!!.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, event ->
                    val session: TerminalSession? = mActivity.getCurrentSession()
                    if (session != null) {
                        if (session.isRunning()) {
                            var textToSend: String = editText.getText().toString()
                            if (textToSend.length == 0) {
                                textToSend = "\r"
                            }
                            session.write(textToSend)
                        } else {
                            mActivity.getTermuxTerminalSessionClient()!!.removeFinishedSession(session)
                        }
                        editText.setText("")
                    }
                    return@OnEditorActionListener true
                })
            }
            collection.addView(layout)
            return layout
        }

        override fun destroyItem(collection: ViewGroup, position: Int, view: Any) {
            collection.removeView(view as View)
        }
    }

    open class OnPageChangeListener : ViewPager.SimpleOnPageChangeListener {

        @JvmField val mActivity: TermuxActivity
        @JvmField val mTerminalToolbarViewPager: ViewPager?

        constructor(activity: TermuxActivity, viewPager: ViewPager?) : super() {
            this.mActivity = activity
            this.mTerminalToolbarViewPager = viewPager
        }

        override fun onPageSelected(position: Int) {
            if (position == 0) {
                mActivity.getTerminalView()!!.requestFocus()
            } else {
                val editText: EditText? = mTerminalToolbarViewPager!!.findViewById(R.id.terminal_toolbar_text_input)
                if (editText != null) {
                    editText.requestFocus()
                }
            }
        }
    }
}
