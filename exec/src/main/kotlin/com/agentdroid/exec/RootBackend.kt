package com.agentdroid.exec

/** Commands as uid 0 through `su -c`. Available only when su answers with uid=0. */
open class RootBackend @JvmOverloads constructor(suBinary: String = "su") :
    ProcessBackend("root", "Root (su)", suBinary, "-c") {

    override fun isAvailable(): Boolean = try {
        val r = exec(ExecRequest.builder("id -u").timeoutMs(8000).build())
        r.ok() && r.stdout().trim() == "0"
    } catch (e: Exception) {
        false
    }
}
