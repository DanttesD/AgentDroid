package com.termux.shared.models

import androidx.annotation.Keep
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.termux.AndroidUtils
import java.io.Serializable

/**
 * An object that stored info for [com.termux.shared.activities.ReportActivity].
 **/
open class ReportInfo : Serializable {

    /** The user action that was being processed for which the report was generated.*/
    @JvmField val userAction: String
    /** The internal app component that sent the report.*/
    @JvmField val sender: String?
    /** The report title.*/
    @JvmField val reportTitle: String?
    /** The markdown report text prefix. Will not be part of copy and share operations, etc.*/
    @JvmField var reportStringPrefix: String? = null
    /** The markdown report text.*/
    @JvmField var reportString: String
    /** The markdown report text suffix. Will not be part of copy and share operations, etc.*/
    @JvmField var reportStringSuffix: String? = null
    /** If set to `true`, then report, app and device info will be added to the report when
     * markdown is generated.
     **/
    @JvmField val addReportInfoHeaderToMarkdown: Boolean
    /** The timestamp for the report.*/
    @JvmField val reportTimestamp: String

    /** The label for the report file to save if user selects menu_item_save_report_to_file.*/
    @JvmField val reportSaveFileLabel: String
    /** The path for the report file to save if user selects menu_item_save_report_to_file.*/
    @JvmField val reportSaveFilePath: String?

    constructor(userAction: String, sender: String?, reportTitle: String?, reportStringPrefix: String?, reportString: String, reportStringSuffix: String?, addReportInfoHeaderToMarkdown: Boolean, reportSaveFileLabel: String, reportSaveFilePath: String?) {
        this.userAction = userAction
        this.sender = sender
        this.reportTitle = reportTitle
        this.reportStringPrefix = reportStringPrefix
        this.reportString = reportString
        this.reportStringSuffix = reportStringSuffix
        this.addReportInfoHeaderToMarkdown = addReportInfoHeaderToMarkdown
        this.reportSaveFileLabel = reportSaveFileLabel
        this.reportSaveFilePath = reportSaveFilePath
        this.reportTimestamp = AndroidUtils.getCurrentMilliSecondUTCTimeStamp()
    }

    companion object {

        /**
         * Explicitly define `serialVersionUID` to prevent exceptions on deserialization.
         *
         * Like when calling `Bundle.getSerializable()` on Android.
         * `android.os.BadParcelableException: Parcelable encountered IOException reading a Serializable object` (name = <class_name>)
         * `java.io.InvalidClassException: <class_name>; local class incompatible`
         *
         * The `@Keep` annotation is necessary to prevent the field from being removed by proguard when
         * app is compiled, even if its kept during library compilation.
         *
         * **See Also:**
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/version.html#a6678
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/class.html#a4100
         **/
        @Keep
        private const val serialVersionUID: Long = 1L

        /**
         * Get a markdown [String] for [ReportInfo].
         *
         * @param reportInfo The [ReportInfo] to convert.
         * @return Returns the markdown [String].
         **/
        @JvmStatic
        fun getReportInfoMarkdownString(reportInfo: ReportInfo?): String {
            if (reportInfo == null) {
                return "null"
            }

            val markdownString = StringBuilder()

            if (reportInfo.addReportInfoHeaderToMarkdown) {
                markdownString.append("## Report Info\n\n")
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("User Action", reportInfo.userAction, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Sender", reportInfo.sender, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Report Timestamp", reportInfo.reportTimestamp, "-"))
                markdownString.append("\n##\n\n")
            }

            markdownString.append(reportInfo.reportString)

            return markdownString.toString()
        }
    }
}
