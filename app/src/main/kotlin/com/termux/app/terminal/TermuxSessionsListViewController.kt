package com.termux.app.terminal

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxSession
import com.termux.terminal.TerminalSession

open class TermuxSessionsListViewController : ArrayAdapter<TermuxSession>, AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    @JvmField val mActivity: TermuxActivity

    @JvmField val boldSpan: StyleSpan = StyleSpan(Typeface.BOLD)
    @JvmField val italicSpan: StyleSpan = StyleSpan(Typeface.ITALIC)

    constructor(activity: TermuxActivity, sessionList: MutableList<TermuxSession>) : super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList) {
        this.mActivity = activity
    }

    @SuppressLint("SetTextI18n")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var sessionRowView: View? = convertView
        if (sessionRowView == null) {
            val inflater: LayoutInflater = mActivity.getLayoutInflater()
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false)
        }

        val sessionTitleView: TextView? = sessionRowView!!.findViewById(R.id.session_title)

        val sessionAtRow: TerminalSession? = getItem(position)!!.getTerminalSession()
        if (sessionAtRow == null) {
            sessionTitleView!!.setText("null session")
            return sessionRowView
        }

        val isUsingBlackUI: Boolean = mActivity.getProperties()!!.isUsingBlackUI()

        if (isUsingBlackUI) {
            sessionTitleView!!.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected))
        }

        val name: String? = sessionAtRow.mSessionName
        val sessionTitle: String? = sessionAtRow.getTitle()

        val numberPart: String = "[" + (position + 1) + "] "
        val sessionNamePart: String? = if (TextUtils.isEmpty(name)) "" else name
        val sessionTitlePart: String = if (TextUtils.isEmpty(sessionTitle)) "" else (if (sessionNamePart!!.isEmpty()) "" else "\n") + sessionTitle

        val fullSessionTitle: String = numberPart + sessionNamePart + sessionTitlePart
        val fullSessionTitleStyled = SpannableString(fullSessionTitle)
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length + sessionNamePart!!.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length + sessionNamePart.length, fullSessionTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        sessionTitleView!!.setText(fullSessionTitleStyled)

        val sessionRunning: Boolean = sessionAtRow.isRunning()

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() and Paint.STRIKE_THRU_TEXT_FLAG.inv())
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() or Paint.STRIKE_THRU_TEXT_FLAG)
        }
        val defaultColor: Int = if (isUsingBlackUI) Color.WHITE else Color.BLACK
        val color: Int = if (sessionRunning || sessionAtRow.getExitStatus() == 0) defaultColor else Color.RED
        sessionTitleView.setTextColor(color)
        return sessionRowView
    }

    override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        val clickedSession: TermuxSession? = getItem(position)
        mActivity.getTermuxTerminalSessionClient()!!.setCurrentSession(clickedSession!!.getTerminalSession())
        mActivity.getDrawer()!!.closeDrawers()
    }

    override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean {
        val selectedSession: TermuxSession? = getItem(position)
        mActivity.getTermuxTerminalSessionClient()!!.renameSession(selectedSession!!.getTerminalSession())
        return true
    }
}
