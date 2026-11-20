package com.termux.shared.shell

import android.content.Context
import com.termux.shared.R
import com.termux.shared.logger.Logger
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.errors.Errno
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * A class that maintains info for foreground Termux sessions.
 * It also provides a way to link each [TerminalSession] with the [ExecutionCommand]
 * that started it.
 **/
open class TermuxSession {

    private val mTerminalSession: TerminalSession
    private val mExecutionCommand: ExecutionCommand
    private val mTermuxSessionClient: TermuxSessionClient?
    private val mSetStdoutOnExit: Boolean

    private constructor(terminalSession: TerminalSession, executionCommand: ExecutionCommand, termuxSessionClient: TermuxSessionClient, setStdoutOnExit: Boolean) {
        this.mTerminalSession = terminalSession
        this.mExecutionCommand = executionCommand
        this.mTermuxSessionClient = termuxSessionClient
        this.mSetStdoutOnExit = setStdoutOnExit
    }

    /**
     * Signal that this [TermuxSession] has finished.  This should be called when
     * [TerminalSessionClient.onSessionFinished] callback is received by the caller.
     *
     * If the processes has finished, then sets [ResultData.stdout], [ResultData.stderr]
     * and [ResultData.exitCode] for the [.mExecutionCommand] of the `termuxTask`
     * and then calls [.processTermuxSessionResult] to process the result}.
     *
     **/
    open fun finish() {
        // If process is still running, then ignore the call
        if (mTerminalSession.isRunning()) {
            return
        }

        val exitCode: Int = mTerminalSession.getExitStatus()

        if (exitCode == 0) {
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession exited normally")
        } else {
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession exited with code: " + exitCode)
        }

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (mExecutionCommand.isStateFailed()) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession state to ExecutionState.EXECUTED and processing results since it has already failed")
            return
        }

        mExecutionCommand.resultData.exitCode = exitCode

        if (this.mSetStdoutOnExit) {
            mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false))
        }

        if (!mExecutionCommand.setState(ExecutionCommand.ExecutionState.EXECUTED)) {
            return
        }

        TermuxSession.processTermuxSessionResult(this, null)
    }

    /**
     * Kill this [TermuxSession] by sending a [OsConstants.SIGILL] to its [.mTerminalSession]
     * if its still executing.
     *
     * @param context The [Context] for operations.
     * @param processResult If set to `true`, then the [.processTermuxSessionResult]
     *                      will be called to process the failure.
     **/
    open fun killIfExecuting(context: Context, processResult: Boolean) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (mExecutionCommand.hasExecuted()) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession since it has already finished executing")
            return
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession")
        if (mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                mExecutionCommand.resultData.exitCode = 137 // SIGKILL

                // Get whatever output has been set till now in case its needed
                if (this.mSetStdoutOnExit) {
                    mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false))
                }

                TermuxSession.processTermuxSessionResult(this, null)
            }
        }

        // Send SIGKILL to process
        mTerminalSession.finishIfRunning()
    }

    open fun getTerminalSession(): TerminalSession {
        return mTerminalSession
    }

    open fun getExecutionCommand(): ExecutionCommand {
        return mExecutionCommand
    }

    fun interface TermuxSessionClient {

        /**
         * Callback function for when [TermuxSession] exits.
         *
         * @param termuxSession The [TermuxSession] that exited.
         **/
        fun onTermuxSessionExited(termuxSession: TermuxSession)
    }

    companion object {

        private const val LOG_TAG: String = "TermuxSession"

        /**
         * Start execution of an [ExecutionCommand] with [Runtime.exec],].
         *
         * The [ExecutionCommand.executable], must be set, [ExecutionCommand.commandLabel],
         * [ExecutionCommand.arguments] and [ExecutionCommand.workingDirectory] may optionally
         * be set.
         *
         * If [ExecutionCommand.executable] is `null`, then a default shell is automatically
         * chosen.
         *
         * @param context The [Context] for operations.
         * @param executionCommand The [ExecutionCommand] containing the information for execution command.
         * @param terminalSessionClient The [TerminalSessionClient] interface implementation.
         * @param termuxSessionClient The [TermuxSessionClient] interface implementation.
         * @param shellEnvironmentClient The [ShellEnvironmentClient] interface implementation.
         * @param sessionName The optional [TerminalSession] name.
         * @param setStdoutOnExit If set to `true`, then the [ResultData.stdout]
         *                        available in the [TermuxSessionClient.onTermuxSessionExited]
         *                        callback will be set to the [TerminalSession] transcript. The session
         *                        transcript will contain both stdout and stderr combined, basically
         *                        anything sent to the the pseudo terminal /dev/pts, including PS1 prefixes.
         *                        Set this to `true` only if the session transcript is required,
         *                        since this requires extra processing to get it.
         * @return Returns the [TermuxSession]. This will be `null` if failed to start the execution command.
         **/
        @JvmStatic
        fun execute(context: Context, executionCommand: ExecutionCommand, terminalSessionClient: TerminalSessionClient, termuxSessionClient: TermuxSessionClient, shellEnvironmentClient: ShellEnvironmentClient, sessionName: String?, setStdoutOnExit: Boolean): TermuxSession? {
            if (executionCommand.workingDirectory == null || executionCommand.workingDirectory!!.isEmpty()) {
                executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath()
            }
            if (executionCommand.workingDirectory!!.isEmpty()) {
                executionCommand.workingDirectory = "/"
            }

            val environment: Array<String> = shellEnvironmentClient.buildEnvironment(context, executionCommand.isFailsafe, executionCommand.workingDirectory)

            var defaultBinPath: String = shellEnvironmentClient.getDefaultBinPath()
            if (defaultBinPath.isEmpty()) {
                defaultBinPath = "/system/bin"
            }

            var isLoginShell = false
            if (executionCommand.executable == null) {
                if (!executionCommand.isFailsafe) {
                    for (shellBinary in arrayOf<String>("login", "bash", "zsh")) {
                        val shellFile = File(defaultBinPath, shellBinary)
                        if (shellFile.canExecute()) {
                            executionCommand.executable = shellFile.getAbsolutePath()
                            break
                        }
                    }
                }

                if (executionCommand.executable == null) {
                    // Fall back to system shell as last resort:
                    // Do not start a login shell since ~/.profile may cause startup failure if its invalid.
                    // /system/bin/sh is provided by mksh (not toybox) and does load .mkshrc but for android its set
                    // to /system/etc/mkshrc even though its default is ~/.mkshrc.
                    // So /system/etc/mkshrc must still be valid for failsafe session to start properly.
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=663
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=41
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/Android.bp;l=114
                    executionCommand.executable = "/system/bin/sh"
                } else {
                    isLoginShell = true
                }
            }

            val processArgs: Array<String?> = shellEnvironmentClient.setupProcessArgs(executionCommand.executable, executionCommand.arguments)

            executionCommand.executable = processArgs[0]
            val processName: String = (if (isLoginShell) "-" else "") + ShellUtils.getExecutableBasename(executionCommand.executable)

            val arguments = arrayOfNulls<String>(processArgs.size)
            arguments[0] = processName
            if (processArgs.size > 1) {
                System.arraycopy(processArgs, 1, arguments, 1, processArgs.size - 1)
            }

            executionCommand.arguments = arguments

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = processName
            }

            if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_failed_to_execute_termux_session_command, executionCommand.getCommandIdAndLabelLogString()))
                TermuxSession.processTermuxSessionResult(null, executionCommand)
                return null
            }

            Logger.logDebugExtended(LOG_TAG, executionCommand.toString())

            Logger.logDebug(LOG_TAG, "Running \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession")
            val terminalSession = TerminalSession(executionCommand.executable, executionCommand.workingDirectory, executionCommand.arguments as Array<String?>?, environment as Array<String?>?, executionCommand.terminalTranscriptRows, terminalSessionClient)

            if (sessionName != null) {
                terminalSession.mSessionName = sessionName
            }

            return TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit)
        }

        /**
         * Process the results of [TermuxSession] or [ExecutionCommand].
         *
         * Only one of `termuxSession` and `executionCommand` must be set.
         *
         * If the `termuxSession` and its [.mTermuxSessionClient] are not `null`,
         * then the [TermuxSession.TermuxSessionClient.onTermuxSessionExited]
         * callback will be called.
         *
         * @param termuxSession The [TermuxSession], which should be set if
         *                  [.execute]
         *                   successfully started the process.
         * @param executionCommand The [ExecutionCommand], which should be set if
         *                          [.execute]
         *                          failed to start the process.
         **/
        @JvmStatic
        private fun processTermuxSessionResult(termuxSession: TermuxSession?, executionCommand: ExecutionCommand?) {
            var executionCommand = executionCommand
            if (termuxSession != null) {
                executionCommand = termuxSession.mExecutionCommand
            }

            if (executionCommand == null) {
                return
            }

            if (executionCommand.shouldNotProcessResults()) {
                Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession result")
                return
            }

            Logger.logDebug(LOG_TAG, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession result")

            if (termuxSession != null && termuxSession.mTermuxSessionClient != null) {
                termuxSession.mTermuxSessionClient!!.onTermuxSessionExited(termuxSession)
            } else {
                // If a callback is not set and execution command didn't fail, then we set success state now
                // Otherwise, the callback host can set it himself when its done with the termuxSession
                if (!executionCommand.isStateFailed()) {
                    executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS)
                }
            }
        }
    }
}
