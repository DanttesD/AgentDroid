package com.termux.shared.models

import android.content.Intent
import android.net.Uri
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.errors.Error
import java.util.Collections

open class ExecutionCommand {

    /*
     The {@link ExecutionState#SUCCESS} and {@link ExecutionState#FAILED} is defined based on
     successful execution of command without any internal errors or exceptions being raised.
     The shell command {@link #exitCode} being non-zero **does not** mean that execution command failed.
     Only the {@link #errCode} being non-zero means that execution command failed from the Termux app
     perspective.
     **/

    /** The [Enum] that defines [ExecutionCommand] state.*/
    enum class ExecutionState {

        PRE_EXECUTION("Pre-Execution", 0),
        EXECUTING("Executing", 1),
        EXECUTED("Executed", 2),
        SUCCESS("Success", 3),
        FAILED("Failed", 4);

        private val name_: String
        private val value: Int

        constructor(name: String, value: Int) {
            this.name_ = name
            this.value = value
        }

        fun getName(): String {
            return name_
        }

        fun getValue(): Int {
            return value
        }
    }

    /** The optional unique id for the [ExecutionCommand].*/
    @JvmField var id: Int? = null

    /** The current state of the [ExecutionCommand].*/
    private var currentState: ExecutionState = ExecutionState.PRE_EXECUTION
    /** The previous state of the [ExecutionCommand].*/
    private var previousState: ExecutionState = ExecutionState.PRE_EXECUTION

    /** The executable for the [ExecutionCommand].*/
    @JvmField var executable: String? = null
    /** The executable Uri for the [ExecutionCommand].*/
    @JvmField var executableUri: Uri? = null
    /** The executable arguments array for the [ExecutionCommand].*/
    @JvmField var arguments: Array<String?>? = null
    /** The stdin string for the [ExecutionCommand].*/
    @JvmField var stdin: String? = null
    /** The current working directory for the [ExecutionCommand].*/
    @JvmField var workingDirectory: String? = null

    /** The terminal transcript rows for the [ExecutionCommand].*/
    @JvmField var terminalTranscriptRows: Int? = null

    /** If the [ExecutionCommand] is a background or a foreground terminal session command.*/
    @JvmField var inBackground: Boolean = false
    /** If the [ExecutionCommand] is meant to start a failsafe terminal session.*/
    @JvmField var isFailsafe: Boolean = false

    /**
     * The [ExecutionCommand] custom log level for background [com.termux.shared.shell.TermuxTask]
     * commands. By default, @link com.termux.shared.shell.StreamGobbler} only logs stdout and
     * stderr if [Logger] `CURRENT_LOG_LEVEL` is >= [Logger.LOG_LEVEL_VERBOSE] and
     * [com.termux.shared.shell.TermuxTask] only logs stdin if `CURRENT_LOG_LEVEL` is >=
     * [Logger.LOG_LEVEL_DEBUG].
     **/
    @JvmField var backgroundCustomLogLevel: Int? = null

    /** The session action of foreground commands.*/
    @JvmField var sessionAction: String? = null

    /** The command label for the [ExecutionCommand].*/
    @JvmField var commandLabel: String? = null
    /** The markdown text for the command description for the [ExecutionCommand].*/
    @JvmField var commandDescription: String? = null
    /** The markdown text for the help of command for the [ExecutionCommand]. This can be used
     * to provide useful info to the user if an internal error is raised.*/
    @JvmField var commandHelp: String? = null

    /** Defines the markdown text for the help of the Termux plugin API that was used to start the
     * [ExecutionCommand]. This can be used to provide useful info to the user if an internal
     * error is raised.*/
    @JvmField var pluginAPIHelp: String? = null

    /** Defines the [Intent] received which started the command.*/
    @JvmField var commandIntent: Intent? = null

    /** Defines if [ExecutionCommand] was started because of an external plugin request
     * like with an intent or from within Termux app itself.*/
    @JvmField var isPluginExecutionCommand: Boolean = false

    /** Defines the [ResultConfig] for the [ExecutionCommand] containing information
     * on how to handle the result.*/
    @JvmField val resultConfig: ResultConfig = ResultConfig()

    /** Defines the [ResultData] for the [ExecutionCommand] containing information
     * of the result.*/
    @JvmField val resultData: ResultData = ResultData()

    /** Defines if processing results already called for this [ExecutionCommand].*/
    @JvmField var processingResultsAlreadyCalled: Boolean = false

    constructor()

    constructor(id: Int?) {
        this.id = id
    }

    constructor(id: Int?, executable: String, arguments: Array<String?>?, stdin: String?, workingDirectory: String?, inBackground: Boolean, isFailsafe: Boolean) {
        this.id = id
        this.executable = executable
        this.arguments = arguments
        this.stdin = stdin
        this.workingDirectory = workingDirectory
        this.inBackground = inBackground
        this.isFailsafe = isFailsafe
    }

    open fun isPluginExecutionCommandWithPendingResult(): Boolean {
        return isPluginExecutionCommand && resultConfig.isCommandWithPendingResult()
    }

    @Synchronized
    open fun setState(newState: ExecutionState): Boolean {
        // The state transition cannot go back or change if already at {@link ExecutionState#SUCCESS}
        if (newState.getValue() < currentState.getValue() || currentState === ExecutionState.SUCCESS) {
            Logger.logError(LOG_TAG, "Invalid " + getCommandIdAndLabelLogString() + " state transition from \"" + currentState.getName() + "\" to \"" + newState.getName() + "\"")
            return false
        }

        // The {@link ExecutionState#FAILED} can be set again, like to add more errors, but we don't update
        // {@link #previousState} with the {@link #currentState} value if its at {@link ExecutionState#FAILED} to
        // preserve the last valid state
        if (currentState !== ExecutionState.FAILED) {
            previousState = currentState
        }

        currentState = newState
        return true
    }

    @Synchronized
    open fun hasExecuted(): Boolean {
        return currentState.getValue() >= ExecutionState.EXECUTED.getValue()
    }

    @Synchronized
    open fun isExecuting(): Boolean {
        return currentState === ExecutionState.EXECUTING
    }

    @Synchronized
    open fun isSuccessful(): Boolean {
        return currentState === ExecutionState.SUCCESS
    }

    @Synchronized
    open fun setStateFailed(error: Error): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), null)
    }

    @Synchronized
    open fun setStateFailed(error: Error, throwable: Throwable): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), Collections.singletonList(throwable))
    }

    @Synchronized
    open fun setStateFailed(error: Error, throwablesList: MutableList<Throwable>): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), throwablesList)
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String): Boolean {
        return setStateFailed(null, code, message, null)
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwable: Throwable): Boolean {
        return setStateFailed(null, code, message, Collections.singletonList(throwable))
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwablesList: MutableList<Throwable>): Boolean {
        return setStateFailed(null, code, message, throwablesList)
    }

    @Synchronized
    open fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: MutableList<Throwable>?): Boolean {
        if (!this.resultData.setStateFailed(type, code, message, throwablesList)) {
            Logger.logWarn(LOG_TAG, "setStateFailed for " + getCommandIdAndLabelLogString() + " resultData encountered an error.")
        }

        return setState(ExecutionState.FAILED)
    }

    @Synchronized
    open fun shouldNotProcessResults(): Boolean {
        if (processingResultsAlreadyCalled) {
            return true
        } else {
            processingResultsAlreadyCalled = true
            return false
        }
    }

    @Synchronized
    open fun isStateFailed(): Boolean {
        if (currentState !== ExecutionState.FAILED) {
            return false
        }

        if (!resultData.isStateFailed()) {
            Logger.logWarn(LOG_TAG, "The " + getCommandIdAndLabelLogString() + " has an invalid errCode value set in errors list while having ExecutionState.FAILED state.\n" + resultData.errorsList)
            return false
        } else {
            return true
        }
    }

    override fun toString(): String {
        if (!hasExecuted()) {
            return getExecutionInputLogString(this, true, true)
        } else {
            return getExecutionOutputLogString(this, true, true, true)
        }
    }

    open fun getIdLogString(): String {
        if (id != null) {
            return "(" + id + ") "
        } else {
            return ""
        }
    }

    open fun getCurrentStateLogString(): String {
        return "Current State: `" + currentState.getName() + "`"
    }

    open fun getPreviousStateLogString(): String {
        return "Previous State: `" + previousState.getName() + "`"
    }

    open fun getCommandLabelLogString(): String? {
        if (commandLabel != null && !commandLabel!!.isEmpty()) {
            return commandLabel
        } else {
            return "Execution Command"
        }
    }

    open fun getCommandIdAndLabelLogString(): String {
        return getIdLogString() + getCommandLabelLogString()
    }

    open fun getExecutableLogString(): String {
        return "Executable: `" + executable + "`"
    }

    open fun getArgumentsLogString(): String {
        return getArgumentsLogString(arguments)
    }

    open fun getWorkingDirectoryLogString(): String {
        return "Working Directory: `" + workingDirectory + "`"
    }

    open fun getInBackgroundLogString(): String {
        return "inBackground: `" + inBackground + "`"
    }

    open fun getIsFailsafeLogString(): String {
        return "isFailsafe: `" + isFailsafe + "`"
    }

    open fun getStdinLogString(): String {
        if (DataUtils.isNullOrEmpty(stdin)) {
            return "Stdin: -"
        } else {
            return Logger.getMultiLineLogStringEntry("Stdin", stdin, "-")
        }
    }

    open fun getBackgroundCustomLogLevelLogString(): String {
        return "Background Custom Log Level: `" + backgroundCustomLogLevel + "`"
    }

    open fun getSessionActionLogString(): String {
        return Logger.getSingleLineLogStringEntry("Session Action", sessionAction, "-")
    }

    open fun getCommandDescriptionLogString(): String {
        return Logger.getSingleLineLogStringEntry("Command Description", commandDescription, "-")
    }

    open fun getCommandHelpLogString(): String {
        return Logger.getSingleLineLogStringEntry("Command Help", commandHelp, "-")
    }

    open fun getPluginAPIHelpLogString(): String {
        return Logger.getSingleLineLogStringEntry("Plugin API Help", pluginAPIHelp, "-")
    }

    open fun getCommandIntentLogString(): String {
        if (commandIntent == null) {
            return "Command Intent: -"
        } else {
            return Logger.getMultiLineLogStringEntry("Command Intent", IntentUtils.getIntentString(commandIntent), "-")
        }
    }

    open fun getIsPluginExecutionCommandLogString(): String {
        return "isPluginExecutionCommand: `" + isPluginExecutionCommand + "`"
    }

    companion object {

        private const val LOG_TAG: String = "ExecutionCommand"

        /**
         * Get a log friendly [String] for [ExecutionCommand] execution input parameters.
         *
         * @param executionCommand The [ExecutionCommand] to convert.
         * @param ignoreNull Set to `true` if non-critical `null` values are to be ignored.
         * @param logStdin Set to `true` if [.stdin] should be logged.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getExecutionInputLogString(executionCommand: ExecutionCommand?, ignoreNull: Boolean, logStdin: Boolean): String {
            if (executionCommand == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(executionCommand.getCommandIdAndLabelLogString()).append(":")

            if (executionCommand.previousState !== ExecutionState.PRE_EXECUTION) {
                logString.append("\n").append(executionCommand.getPreviousStateLogString())
            }
            logString.append("\n").append(executionCommand.getCurrentStateLogString())

            logString.append("\n").append(executionCommand.getExecutableLogString())
            logString.append("\n").append(executionCommand.getArgumentsLogString())
            logString.append("\n").append(executionCommand.getWorkingDirectoryLogString())
            logString.append("\n").append(executionCommand.getInBackgroundLogString())
            logString.append("\n").append(executionCommand.getIsFailsafeLogString())

            if (executionCommand.inBackground) {
                if (logStdin && (!ignoreNull || !DataUtils.isNullOrEmpty(executionCommand.stdin))) {
                    logString.append("\n").append(executionCommand.getStdinLogString())
                }

                if (!ignoreNull || executionCommand.backgroundCustomLogLevel != null) {
                    logString.append("\n").append(executionCommand.getBackgroundCustomLogLevelLogString())
                }
            }

            if (!ignoreNull || executionCommand.sessionAction != null) {
                logString.append("\n").append(executionCommand.getSessionActionLogString())
            }

            if (!ignoreNull || executionCommand.commandIntent != null) {
                logString.append("\n").append(executionCommand.getCommandIntentLogString())
            }

            logString.append("\n").append(executionCommand.getIsPluginExecutionCommandLogString())
            if (executionCommand.isPluginExecutionCommand) {
                logString.append("\n").append(ResultConfig.getResultConfigLogString(executionCommand.resultConfig, ignoreNull))
            }

            return logString.toString()
        }

        /**
         * Get a log friendly [String] for [ExecutionCommand] execution output parameters.
         *
         * @param executionCommand The [ExecutionCommand] to convert.
         * @param ignoreNull Set to `true` if non-critical `null` values are to be ignored.
         * @param logResultData Set to `true` if [.resultData] should be logged.
         * @param logStdoutAndStderr Set to `true` if [ResultData.stdout] and [ResultData.stderr] should be logged.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getExecutionOutputLogString(executionCommand: ExecutionCommand?, ignoreNull: Boolean, logResultData: Boolean, logStdoutAndStderr: Boolean): String {
            if (executionCommand == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(executionCommand.getCommandIdAndLabelLogString()).append(":")

            logString.append("\n").append(executionCommand.getPreviousStateLogString())
            logString.append("\n").append(executionCommand.getCurrentStateLogString())

            if (logResultData) {
                logString.append("\n").append(ResultData.getResultDataLogString(executionCommand.resultData, logStdoutAndStderr))
            }

            return logString.toString()
        }

        /**
         * Get a log friendly [String] for [ExecutionCommand] with more details.
         *
         * @param executionCommand The [ExecutionCommand] to convert.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getDetailedLogString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(getExecutionInputLogString(executionCommand, false, true))
            logString.append(getExecutionOutputLogString(executionCommand, false, true, true))

            logString.append("\n").append(executionCommand.getCommandDescriptionLogString())
            logString.append("\n").append(executionCommand.getCommandHelpLogString())
            logString.append("\n").append(executionCommand.getPluginAPIHelpLogString())

            return logString.toString()
        }

        /**
         * Get a markdown [String] for [ExecutionCommand].
         *
         * @param executionCommand The [ExecutionCommand] to convert.
         * @return Returns the markdown [String].
         **/
        @JvmStatic
        fun getExecutionCommandMarkdownString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) {
                return "null"
            }

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = "Execution Command"
            }

            val markdownString = StringBuilder()

            markdownString.append("## ").append(executionCommand.commandLabel).append("\n")

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Previous State", executionCommand.previousState.getName(), "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Current State", executionCommand.currentState.getName(), "-"))

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Executable", executionCommand.executable, "-"))
            markdownString.append("\n").append(getArgumentsMarkdownString(executionCommand.arguments))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Working Directory", executionCommand.workingDirectory, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("inBackground", executionCommand.inBackground, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isFailsafe", executionCommand.isFailsafe, "-"))

            if (executionCommand.inBackground) {
                if (!DataUtils.isNullOrEmpty(executionCommand.stdin)) {
                    markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Stdin", executionCommand.stdin, "-"))
                }
                if (executionCommand.backgroundCustomLogLevel != null) {
                    markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Background Custom Log Level", executionCommand.backgroundCustomLogLevel, "-"))
                }
            }

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Session Action", executionCommand.sessionAction, "-"))

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isPluginExecutionCommand", executionCommand.isPluginExecutionCommand, "-"))

            markdownString.append("\n\n").append(ResultConfig.getResultConfigMarkdownString(executionCommand.resultConfig))

            markdownString.append("\n\n").append(ResultData.getResultDataMarkdownString(executionCommand.resultData))

            if (executionCommand.commandDescription != null || executionCommand.commandHelp != null) {
                if (executionCommand.commandDescription != null) {
                    markdownString.append("\n\n### Command Description\n\n").append(executionCommand.commandDescription).append("\n")
                }
                if (executionCommand.commandHelp != null) {
                    markdownString.append("\n\n### Command Help\n\n").append(executionCommand.commandHelp).append("\n")
                }
                markdownString.append("\n##\n")
            }

            if (executionCommand.pluginAPIHelp != null) {
                markdownString.append("\n\n### Plugin API Help\n\n").append(executionCommand.pluginAPIHelp)
                markdownString.append("\n##\n")
            }

            return markdownString.toString()
        }

        /**
         * Get a log friendly [String] for [List<String>] argumentsArray.
         * If argumentsArray are null or of size 0, then `Arguments: -` is returned. Otherwise
         * following format is returned:
         *
         * Arguments:
         * ```
         * Arg 1: `value`
         * Arg 2: 'value`
         * ```
         *
         * @param argumentsArray The [String[]] argumentsArray to convert.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getArgumentsLogString(argumentsArray: Array<String?>?): String {
            val argumentsString = StringBuilder("Arguments:")

            if (argumentsArray != null && argumentsArray.size != 0) {
                argumentsString.append("\n```\n")
                var i = 0
                while (i != argumentsArray.size) {
                    argumentsString.append(Logger.getSingleLineLogStringEntry("Arg " + (i + 1), DataUtils.getTruncatedCommandOutput(argumentsArray[i], Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, true, false, true), "-")).append("\n")
                    i++
                }
                argumentsString.append("```")
            } else {
                argumentsString.append(" -")
            }

            return argumentsString.toString()
        }

        /**
         * Get a markdown [String] for [String[]] argumentsArray.
         * If argumentsArray are null or of size 0, then `**Arguments:** -` is returned. Otherwise
         * following format is returned:
         *
         * **Arguments:**
         *
         * **Arg 1:**
         * ```
         * value
         * ```
         * **Arg 2:**
         * ```
         * value
         *```
         *
         * @param argumentsArray The [String[]] argumentsArray to convert.
         * @return Returns the markdown [String].
         **/
        @JvmStatic
        fun getArgumentsMarkdownString(argumentsArray: Array<String?>?): String {
            val argumentsString = StringBuilder("**Arguments:**")

            if (argumentsArray != null && argumentsArray.size != 0) {
                argumentsString.append("\n")
                var i = 0
                while (i != argumentsArray.size) {
                    argumentsString.append(MarkdownUtils.getMultiLineMarkdownStringEntry("Arg " + (i + 1), argumentsArray[i], "-")).append("\n")
                    i++
                }
            } else {
                argumentsString.append(" -  ")
            }

            return argumentsString.toString()
        }
    }
}
