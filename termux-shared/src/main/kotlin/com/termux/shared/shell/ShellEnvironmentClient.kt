package com.termux.shared.shell

import android.content.Context

interface ShellEnvironmentClient {

    /**
     * Get the default working directory path for the environment in case the path that was passed
     * was `null` or empty.
     *
     * @return Should return the default working directory path.
     **/
    fun getDefaultWorkingDirectoryPath(): String

    /**
     * Get the default "/bin" path, likely $PREFIX/bin.
     *
     * @return Should return the "/bin" path.
     **/
    fun getDefaultBinPath(): String

    /**
     * Build the shell environment to be used for commands.
     *
     * @param currentPackageContext The [Context] for the current package.
     * @param isFailSafe If running a failsafe session.
     * @param workingDirectory The working directory for the environment.
     * @return Should return the build environment.
     **/
    fun buildEnvironment(currentPackageContext: Context, isFailSafe: Boolean, workingDirectory: String?): Array<String>

    /**
     * Setup process arguments for the file to execute, like interpreter, etc.
     *
     * @param fileToExecute The file to execute.
     * @param arguments     The arguments to pass to the executable.
     * @return Should return the final process arguments.
     **/
    fun setupProcessArgs(fileToExecute: String?, arguments: Array<String?>?): Array<String?>
}
