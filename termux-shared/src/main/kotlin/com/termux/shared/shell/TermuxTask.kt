package com.termux.shared.shell

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.errors.Errno
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * A class that maintains info for background Termux tasks run with [Runtime.exec],].
 * It also provides a way to link each [Process] with the [ExecutionCommand]
 * that started it.
 **/
class TermuxTask {

    private val mProcess: Process
    private val mExecutionCommand: ExecutionCommand
    private val mTermuxTaskClient: TermuxTaskClient?

    private constructor(process: Process, executionCommand: ExecutionCommand, termuxTaskClient: TermuxTaskClient?) {
        this.mProcess = process
        this.mExecutionCommand = executionCommand
        this.mTermuxTaskClient = termuxTaskClient
    }

    /**
     * Sets up stdout and stderr readers for the [.mProcess] and waits for the process to end.
     *
     * If the processes finishes, then sets [ResultData.stdout], [ResultData.stderr]
     * and [ResultData.exitCode] for the [.mExecutionCommand] of the `termuxTask`
     * and then calls [.processTermuxTaskResult].
     *
     * @param context The [Context] for operations.
     **/
    @Throws(IllegalThreadStateException::class, InterruptedException::class)
    private fun executeInner(context: Context) {
        val pid: Int = ShellUtils.getPid(mProcess)

        Logger.logDebug(LOG_TAG, "Running \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask with pid " + pid)

        mExecutionCommand.resultData.exitCode = null

        // setup stdin, and stdout and stderr gobblers
        val STDIN = DataOutputStream(mProcess.getOutputStream())
        val STDOUT = StreamGobbler(pid.toString() + "-stdout", mProcess.getInputStream(), mExecutionCommand.resultData.stdout, mExecutionCommand.backgroundCustomLogLevel)
        val STDERR = StreamGobbler(pid.toString() + "-stderr", mProcess.getErrorStream(), mExecutionCommand.resultData.stderr, mExecutionCommand.backgroundCustomLogLevel)

        // start gobbling
        STDOUT.start()
        STDERR.start()

        if (!DataUtils.isNullOrEmpty(mExecutionCommand.stdin)) {
            try {
                STDIN.write(((mExecutionCommand.stdin + "\n") as java.lang.String).getBytes(StandardCharsets.UTF_8))
                STDIN.flush()
                STDIN.close()
                //STDIN.write("exit\n".getBytes(StandardCharsets.UTF_8));
                //STDIN.flush();
            } catch (e: IOException) {
                if (e.message != null && (e.message!!.contains("EPIPE") || e.message!!.contains("Stream closed"))) {
                    // Method most horrid to catch broken pipe, in which case we
                    // do nothing. The command is not a shell, the shell closed
                    // STDIN, the script already contained the exit command, etc.
                    // these cases we want the output instead of returning null.
                } else {
                    // other issues we don't know how to handle, leads to
                    // returning null
                    mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_exception_received_while_executing_termux_task_command, mExecutionCommand.getCommandIdAndLabelLogString(), e.message), e)
                    mExecutionCommand.resultData.exitCode = 1
                    TermuxTask.processTermuxTaskResult(this, null)
                    kill()
                    return
                }
            }
        }

        // wait for our process to finish, while we gobble away in the background
        val exitCode: Int = mProcess.waitFor()

        // make sure our threads are done gobbling
        // and the process is destroyed - while the latter shouldn't be
        // needed in theory, and may even produce warnings, in "normal" Java
        // they are required for guaranteed cleanup of resources, so lets be
        // safe and do this on Android as well
        try {
            STDIN.close()
        } catch (e: IOException) {
            // might be closed already
        }
        STDOUT.join()
        STDERR.join()
        mProcess.destroy()

        // Process result
        if (exitCode == 0) {
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask with pid " + pid + " exited normally")
        } else {
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask with pid " + pid + " exited with code: " + exitCode)
        }

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (mExecutionCommand.isStateFailed()) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask state to ExecutionState.EXECUTED and processing results since it has already failed")
            return
        }

        mExecutionCommand.resultData.exitCode = exitCode

        if (!mExecutionCommand.setState(ExecutionCommand.ExecutionState.EXECUTED)) {
            return
        }

        TermuxTask.processTermuxTaskResult(this, null)
    }

    /**
     * Kill this [TermuxTask] by sending a [OsConstants.SIGILL] to its [.mProcess]
     * if its still executing.
     *
     * @param context The [Context] for operations.
     * @param processResult If set to `true`, then the [.processTermuxTaskResult]
     *                      will be called to process the failure.
     **/
    fun killIfExecuting(context: Context, processResult: Boolean) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (mExecutionCommand.hasExecuted()) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask since it has already finished executing")
            return
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask")

        if (mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                mExecutionCommand.resultData.exitCode = 137 // SIGKILL
                TermuxTask.processTermuxTaskResult(this, null)
            }
        }

        if (mExecutionCommand.isExecuting()) {
            kill()
        }
    }

    /**
     * Kill this [TermuxTask] by sending a [OsConstants.SIGILL] to its [.mProcess].
     **/
    fun kill() {
        val pid: Int = ShellUtils.getPid(mProcess)
        try {
            // Send SIGKILL to process
            Os.kill(pid, OsConstants.SIGKILL)
        } catch (e: ErrnoException) {
            Logger.logWarn(LOG_TAG, "Failed to send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask with pid " + pid + ": " + e.message)
        }
    }

    fun getProcess(): Process {
        return mProcess
    }

    fun getExecutionCommand(): ExecutionCommand {
        return mExecutionCommand
    }

    fun interface TermuxTaskClient {

        /**
         * Callback function for when [TermuxTask] exits.
         *
         * @param termuxTask The [TermuxTask] that exited.
         **/
        fun onTermuxTaskExited(termuxTask: TermuxTask)
    }

    companion object {

        private const val LOG_TAG: String = "TermuxTask"

        /**
         * Start execution of an [ExecutionCommand] with [Runtime.exec],].
         *
         * The [ExecutionCommand.executable], must be set.
         * The  [ExecutionCommand.commandLabel], [ExecutionCommand.arguments] and
         * [ExecutionCommand.workingDirectory] may optionally be set.
         *
         * @param context The [Context] for operations.
         * @param executionCommand The [ExecutionCommand] containing the information for execution command.
         * @param termuxTaskClient The [TermuxTaskClient] interface implementation.
         *                           The [TermuxTaskClient.onTermuxTaskExited] will
         *                           be called regardless of `isSynchronous` value but not if
         *                           `null` is returned by this method. This can
         *                           optionally be `null`.
         * @param shellEnvironmentClient The [ShellEnvironmentClient] interface implementation.
         * @param isSynchronous If set to `true`, then the command will be executed in the
         *                      caller thread and results returned synchronously in the [ExecutionCommand]
         *                      sub object of the [TermuxTask] returned.
         *                      If set to `false`, then a new thread is started run the commands
         *                      asynchronously in the background and control is returned to the caller thread.
         * @return Returns the [TermuxTask]. This will be `null` if failed to start the execution command.
         **/
        @JvmStatic
        fun execute(context: Context, executionCommand: ExecutionCommand, termuxTaskClient: TermuxTaskClient?, shellEnvironmentClient: ShellEnvironmentClient, isSynchronous: Boolean): TermuxTask? {
            if (executionCommand.workingDirectory == null || executionCommand.workingDirectory!!.isEmpty()) {
                executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath()
            }
            if (executionCommand.workingDirectory!!.isEmpty()) {
                executionCommand.workingDirectory = "/"
            }

            val env: Array<String> = shellEnvironmentClient.buildEnvironment(context, false, executionCommand.workingDirectory)

            val commandArray: Array<String?> = shellEnvironmentClient.setupProcessArgs(executionCommand.executable, executionCommand.arguments)

            if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_failed_to_execute_termux_task_command, executionCommand.getCommandIdAndLabelLogString()))
                TermuxTask.processTermuxTaskResult(null, executionCommand)
                return null
            }

            // No need to log stdin if logging is disabled, like for app internal scripts
            Logger.logDebugExtended(LOG_TAG, ExecutionCommand.getExecutionInputLogString(executionCommand, true, Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)))

            val taskName: String? = ShellUtils.getExecutableBasename(executionCommand.executable)

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = taskName
            }

            // Exec the process
            var process: Process
            try {
                process = Runtime.getRuntime().exec(commandArray, env, File(executionCommand.workingDirectory))
            } catch (e: IOException) {
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_failed_to_execute_termux_task_command, executionCommand.getCommandIdAndLabelLogString()), e)
                TermuxTask.processTermuxTaskResult(null, executionCommand)
                return null
            }

            val termuxTask = TermuxTask(process, executionCommand, termuxTaskClient)

            if (isSynchronous) {
                try {
                    termuxTask.executeInner(context)
                } catch (e: IllegalThreadStateException) {
                    // TODO: Should either of these be handled or returned?
                } catch (e: InterruptedException) {
                }
            } else {
                object : Thread() {
                    override fun run() {
                        try {
                            termuxTask.executeInner(context)
                        } catch (e: IllegalThreadStateException) {
                        } catch (e: InterruptedException) {
                        }
                    }
                }.start()
            }

            return termuxTask
        }

        /**
         * Process the results of [TermuxTask] or [ExecutionCommand].
         *
         * Only one of `termuxTask` and `executionCommand` must be set.
         *
         * If the `termuxTask` and its [.mTermuxTaskClient] are not `null`,
         * then the [TermuxTaskClient.onTermuxTaskExited] callback will be called.
         *
         * @param termuxTask The [TermuxTask], which should be set if
         *                  [.execute]
         *                   successfully started the process.
         * @param executionCommand The [ExecutionCommand], which should be set if
         *                          [.execute]
         *                          failed to start the process.
         **/
        @JvmStatic
        private fun processTermuxTaskResult(termuxTask: TermuxTask?, executionCommand: ExecutionCommand?) {
            var executionCommand = executionCommand
            if (termuxTask != null) {
                executionCommand = termuxTask.mExecutionCommand
            }

            if (executionCommand == null) {
                return
            }

            if (executionCommand.shouldNotProcessResults()) {
                Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask result")
                return
            }

            Logger.logDebug(LOG_TAG, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask result")

            if (termuxTask != null && termuxTask.mTermuxTaskClient != null) {
                termuxTask.mTermuxTaskClient!!.onTermuxTaskExited(termuxTask)
            } else {
                // If a callback is not set and execution command didn't fail, then we set success state now
                // Otherwise, the callback host can set it himself when its done with the termuxTask
                if (!executionCommand.isStateFailed()) {
                    executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS)
                }
            }
        }
    }
}
