package com.agentdroid.agent

/** Things only the Android app can do (they need a Context). */
interface PhoneHooks {
    fun setClipboard(text: String)

    fun getClipboard(): String?

    fun notifyUser(title: String, text: String)
}
