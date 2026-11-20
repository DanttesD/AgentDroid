package com.termux.shared.shell

import android.content.Context

open class TermuxShellEnvironmentClient : ShellEnvironmentClient {

    override fun getDefaultWorkingDirectoryPath(): String {
        return TermuxShellUtils.getDefaultWorkingDirectoryPath()
    }

    override fun getDefaultBinPath(): String {
        return TermuxShellUtils.getDefaultBinPath()
    }

    override fun buildEnvironment(currentPackageContext: Context, isFailSafe: Boolean, workingDirectory: String?): Array<String> {
        return TermuxShellUtils.buildEnvironment(currentPackageContext, isFailSafe, workingDirectory)
    }

    override fun setupProcessArgs(fileToExecute: String?, arguments: Array<String?>?): Array<String?> {
        return TermuxShellUtils.setupProcessArgs(fileToExecute, arguments)
    }
}
