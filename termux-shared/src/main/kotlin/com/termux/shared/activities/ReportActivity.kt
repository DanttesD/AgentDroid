package com.termux.shared.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.file.filesystem.FileType
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.models.errors.Error
import com.termux.shared.termux.TermuxConstants
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import io.noties.markwon.recycler.SimpleEntry
import org.commonmark.node.FencedCodeBlock

/**
 * An activity to show reports in markdown format as per CommonMark spec based on config passed as [ReportInfo].
 * Add Following to `AndroidManifest.xml` to use in an app:
 * ``<activity android:name="com.termux.shared.activities.ReportActivity" android:theme="@style/Theme.AppCompat.TermuxReportActivity" android:documentLaunchMode="intoExisting" />` `
 * and
 * ``<receiver android:name="com.termux.shared.activities.ReportActivity$ReportActivityBroadcastReceiver"  android:exported="false" />` `
 * Receiver **must not** be `exported="true"`!!!
 *
 * Also make an incremental call to [.deleteReportInfoFilesOlderThanXDays]
 * in the app to cleanup cached files.
 **/
open class ReportActivity : AppCompatActivity() {

    private var mReportInfo: ReportInfo? = null
    private var mReportInfoFilePath: String? = null
    private var mReportActivityMarkdownString: String? = null
    private var mBundle: Bundle? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.logVerbose(LOG_TAG, "onCreate")

        setContentView(R.layout.activity_report)

        val toolbar: Toolbar? = findViewById(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
        }

        mBundle = null
        val intent: Intent? = getIntent()
        if (intent != null) {
            mBundle = intent.getExtras()
        } else if (savedInstanceState != null) {
            mBundle = savedInstanceState
        }

        updateUI()
    }

    protected override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Logger.logVerbose(LOG_TAG, "onNewIntent")

        setIntent(intent)

        if (intent != null) {
            deleteReportInfoFile(this, mReportInfoFilePath)
            mBundle = intent.getExtras()
            updateUI()
        }
    }

    private fun updateUI() {
        if (mBundle == null) {
            finish()
            return
        }

        mReportInfo = null
        mReportInfoFilePath = null

        if (mBundle!!.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
            mReportInfoFilePath = mBundle!!.getString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)
            Logger.logVerbose(LOG_TAG, ReportInfo::class.java.getSimpleName() + " serialized object will be read from file at path \"" + mReportInfoFilePath + "\"")
            if (mReportInfoFilePath != null) {
                try {
                    val result: FileUtils.ReadSerializableObjectResult = FileUtils.readSerializableObjectFromFile(ReportInfo::class.java.getSimpleName(), mReportInfoFilePath, ReportInfo::class.java, false)
                    if (result.error != null) {
                        Logger.logErrorExtended(LOG_TAG, result.error!!.toString())
                        Logger.showToast(this, Error.getMinimalErrorString(result.error), true)
                        finish()
                        return
                    } else {
                        if (result.serializableObject != null) {
                            mReportInfo = result.serializableObject as ReportInfo?
                        }
                    }
                } catch (e: Exception) {
                    Logger.logErrorAndShowToast(this, LOG_TAG, e.message)
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failure while getting " + ReportInfo::class.java.getSimpleName() + " serialized object from file at path \"" + mReportInfoFilePath + "\"", e)
                }
            }
        } else {
            mReportInfo = mBundle!!.getSerializable(EXTRA_REPORT_INFO_OBJECT) as ReportInfo?
        }

        if (mReportInfo == null) {
            finish()
            return
        }

        val actionBar: ActionBar? = getSupportActionBar()
        if (actionBar != null) {
            if (mReportInfo!!.reportTitle != null) {
                actionBar.setTitle(mReportInfo!!.reportTitle)
            } else {
                actionBar.setTitle(TermuxConstants.TERMUX_APP_NAME + " App Report")
            }
        }

        val recyclerView: RecyclerView? = findViewById(R.id.recycler_view)

        val markwon: Markwon = MarkdownUtils.getRecyclerMarkwonBuilder(this)

        val adapter: MarkwonAdapter = MarkwonAdapter.builderTextViewIsRoot(R.layout.markdown_adapter_node_default).include(FencedCodeBlock::class.java, SimpleEntry.create(R.layout.markdown_adapter_node_code_block, R.id.code_text_view)).build()

        recyclerView!!.setLayoutManager(LinearLayoutManager(this))
        recyclerView!!.setAdapter(adapter)

        generateReportActivityMarkdownString()
        adapter.setMarkdown(markwon, mReportActivityMarkdownString)
        adapter.notifyDataSetChanged()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mBundle!!.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
            outState.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, mReportInfoFilePath)
        } else {
            outState.putSerializable(EXTRA_REPORT_INFO_OBJECT, mReportInfo)
        }
    }

    protected override fun onDestroy() {
        super.onDestroy()
        Logger.logVerbose(LOG_TAG, "onDestroy")

        deleteReportInfoFile(this, mReportInfoFilePath)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = getMenuInflater()
        inflater.inflate(R.menu.menu_report, menu)

        if (mReportInfo!!.reportSaveFilePath == null) {
            val item: MenuItem? = menu.findItem(R.id.menu_item_save_report_to_file)
            if (item != null) {
                item.setEnabled(false)
            }
        }

        return true
    }

    override fun onBackPressed() {
        // Remove activity from recents menu on back button press
        finishAndRemoveTask()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id: Int = item.getItemId()
        if (id == R.id.menu_item_share_report) {
            ShareUtils.shareText(this, getString(R.string.title_report_text), ReportInfo.getReportInfoMarkdownString(mReportInfo))
        } else if (id == R.id.menu_item_copy_report) {
            ShareUtils.copyTextToClipboard(this, ReportInfo.getReportInfoMarkdownString(mReportInfo), null)
        } else if (id == R.id.menu_item_save_report_to_file) {
            ShareUtils.saveTextToFile(this, mReportInfo!!.reportSaveFileLabel, mReportInfo!!.reportSaveFilePath, ReportInfo.getReportInfoMarkdownString(mReportInfo), true, REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE)
        }

        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.")
            if (requestCode == REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE) {
                ShareUtils.saveTextToFile(this, mReportInfo!!.reportSaveFileLabel, mReportInfo!!.reportSaveFilePath, ReportInfo.getReportInfoMarkdownString(mReportInfo), true, -1)
            }
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.")
        }
    }

    /**
     * Generate the markdown [String] to be shown in [ReportActivity].
     **/
    private fun generateReportActivityMarkdownString() {
        // We need to reduce chances of OutOfMemoryError happening so reduce new allocations and
        // do not keep output of getReportInfoMarkdownString in memory
        val reportString = StringBuilder()

        if (mReportInfo!!.reportStringPrefix != null) {
            reportString.append(mReportInfo!!.reportStringPrefix)
        }

        var reportMarkdownString: String? = ReportInfo.getReportInfoMarkdownString(mReportInfo)
        val reportMarkdownStringSize: Int = (reportMarkdownString!! as java.lang.String).getBytes().size
        var truncated = false
        if (reportMarkdownStringSize > ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES) {
            Logger.logVerbose(LOG_TAG, mReportInfo!!.reportTitle + " report string size " + reportMarkdownStringSize + " is greater than " + ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES + " and will be truncated")
            reportString.append(DataUtils.getTruncatedCommandOutput(reportMarkdownString, ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES, true, false, true))
            truncated = true
        } else {
            reportString.append(reportMarkdownString)
        }

        // Free reference
        reportMarkdownString = null

        if (mReportInfo!!.reportStringSuffix != null) {
            reportString.append(mReportInfo!!.reportStringSuffix)
        }

        val reportStringSize: Int = reportString.length
        if (reportStringSize > ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES) {
            // This may break markdown formatting
            Logger.logVerbose(LOG_TAG, mReportInfo!!.reportTitle + " report string total size " + reportStringSize + " is greater than " + ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES + " and will be truncated")
            mReportActivityMarkdownString = this.getString(R.string.msg_report_truncated) + DataUtils.getTruncatedCommandOutput(reportString.toString(), ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES, true, false, false)
        } else if (truncated) {
            mReportActivityMarkdownString = this.getString(R.string.msg_report_truncated) + reportString.toString()
        } else {
            mReportActivityMarkdownString = reportString.toString()
        }
    }

    open class NewInstanceResult {
        /** An intent that can be used to start the [ReportActivity].*/
        @JvmField var contentIntent: Intent? = null
        /** An intent that can should be adding as the [android.app.Notification.deleteIntent]
         * by a call to [android.app.PendingIntent.getBroadcast]
         * so that [ReportActivityBroadcastReceiver] can do cleanup of [.EXTRA_REPORT_INFO_OBJECT_FILE_PATH].*/
        @JvmField var deleteIntent: Intent? = null

        constructor(contentIntent: Intent?, deleteIntent: Intent?) {
            this.contentIntent = contentIntent
            this.deleteIntent = deleteIntent
        }
    }

    /**
     * The [BroadcastReceiver] for [ReportActivity] that currently does cleanup when
     * [android.app.Notification.deleteIntent] is called. It must be registered in `AndroidManifest.xml`.
     **/
    open class ReportActivityBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) {
                return
            }

            val action: String? = intent.getAction()
            Logger.logVerbose(LOG_TAG, "onReceive: \"" + action + "\" action")

            if (ACTION_DELETE_REPORT_INFO_OBJECT_FILE.equals(action)) {
                val bundle: Bundle? = intent.getExtras()
                if (bundle == null) {
                    return
                }
                if (bundle.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
                    deleteReportInfoFile(context, bundle.getString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH))
                }
            }
        }

        companion object {
            private const val LOG_TAG: String = "ReportActivityBroadcastReceiver"
        }
    }

    companion object {

        private val CLASS_NAME: String? = ReportActivity::class.java.getCanonicalName()
        private val ACTION_DELETE_REPORT_INFO_OBJECT_FILE: String = CLASS_NAME + ".ACTION_DELETE_REPORT_INFO_OBJECT_FILE"

        private val EXTRA_REPORT_INFO_OBJECT: String = CLASS_NAME + ".EXTRA_REPORT_INFO_OBJECT"
        private val EXTRA_REPORT_INFO_OBJECT_FILE_PATH: String = CLASS_NAME + ".EXTRA_REPORT_INFO_OBJECT_FILE_PATH"

        private const val CACHE_DIR_BASENAME: String = "report_activity"
        private const val CACHE_FILE_BASENAME_PREFIX: String = "report_info_"

        const val REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE: Int = 1000

        const val ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES: Int = 1000 * 1024 // 1MB

        private const val LOG_TAG: String = "ReportActivity"

        /**
         * Start the [ReportActivity].
         *
         * @param context The [Context] for operations.
         * @param reportInfo The [ReportInfo] containing info that needs to be displayed.
         **/
        @JvmStatic
        fun startReportActivity(context: Context, reportInfo: ReportInfo) {
            val result: NewInstanceResult = newInstance(context, reportInfo)
            if (result.contentIntent == null) {
                return
            }
            context.startActivity(result.contentIntent)
        }

        /**
         * Get content and delete intents for the [ReportActivity] that can be used to start it
         * and do cleanup.
         *
         * If [ReportInfo] size is too large, then a TransactionTooLargeException will be thrown
         * so its object may be saved to a file in the [Context.getCacheDir]. Then when activity
         * starts, its read back and the file is deleted in [.onDestroy].
         * Note that files may still be left if [.onDestroy] is not called or doesn't finish.
         * A separate cleanup routine is implemented from that case by
         * [.deleteReportInfoFilesOlderThanXDays] which should be called
         * incrementally or at app startup.
         *
         * @param context The [Context] for operations.
         * @param reportInfo The [ReportInfo] containing info that needs to be displayed.
         * @return Returns [NewInstanceResult].
         **/
        @JvmStatic
        fun newInstance(context: Context, reportInfo: ReportInfo): NewInstanceResult {
            val size: Long = DataUtils.getSerializedSize(reportInfo)
            if (size > DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES) {
                val reportInfoDirectoryPath: String = getReportInfoDirectoryPath(context)
                val reportInfoFilePath: String = reportInfoDirectoryPath + "/" + CACHE_FILE_BASENAME_PREFIX + reportInfo.reportTimestamp
                Logger.logVerbose(LOG_TAG, reportInfo.reportTitle + " " + ReportInfo::class.java.getSimpleName() + " serialized object size " + size + " is greater than " + DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES + " and it will be written to file at path \"" + reportInfoFilePath + "\"")
                val error: Error? = FileUtils.writeSerializableObjectToFile(ReportInfo::class.java.getSimpleName(), reportInfoFilePath, reportInfo)
                if (error != null) {
                    Logger.logErrorExtended(LOG_TAG, error.toString())
                    Logger.showToast(context, Error.getMinimalErrorString(error), true)
                    return NewInstanceResult(null, null)
                }

                return NewInstanceResult(createContentIntent(context, null, reportInfoFilePath), createDeleteIntent(context, reportInfoFilePath))
            } else {
                return NewInstanceResult(createContentIntent(context, reportInfo, null), null)
            }
        }

        @JvmStatic
        private fun createContentIntent(context: Context, reportInfo: ReportInfo?, reportInfoFilePath: String?): Intent {
            val intent = Intent(context, ReportActivity::class.java)
            val bundle = Bundle()

            if (reportInfoFilePath != null) {
                bundle.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, reportInfoFilePath)
            } else {
                bundle.putSerializable(EXTRA_REPORT_INFO_OBJECT, reportInfo)
            }

            intent.putExtras(bundle)

            // Note that ReportActivity should have `documentLaunchMode="intoExisting"` set in `AndroidManifest.xml`
            // which has equivalent behaviour to FLAG_ACTIVITY_NEW_DOCUMENT.
            // FLAG_ACTIVITY_SINGLE_TOP must also be passed for onNewIntent to be called.
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            return intent
        }

        @JvmStatic
        private fun createDeleteIntent(context: Context, reportInfoFilePath: String?): Intent? {
            if (reportInfoFilePath == null) {
                return null
            }

            val intent = Intent(context, ReportActivityBroadcastReceiver::class.java)
            intent.setAction(ACTION_DELETE_REPORT_INFO_OBJECT_FILE)

            val bundle = Bundle()
            bundle.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, reportInfoFilePath)
            intent.putExtras(bundle)

            return intent
        }

        @JvmStatic
        private fun getReportInfoDirectoryPath(context: Context): String {
            // Canonicalize to solve /data/data and /data/user/0 issues when comparing with reportInfoFilePath
            return FileUtils.getCanonicalPath(context.getCacheDir().getAbsolutePath(), null) + "/" + CACHE_DIR_BASENAME
        }

        @JvmStatic
        private fun deleteReportInfoFile(context: Context?, reportInfoFilePath: String?) {
            var reportInfoFilePath = reportInfoFilePath
            if (context == null || reportInfoFilePath == null) {
                return
            }

            // Extra protection for mainly if someone set `exported="true"` for ReportActivityBroadcastReceiver
            val reportInfoDirectoryPath: String = getReportInfoDirectoryPath(context)
            reportInfoFilePath = FileUtils.getCanonicalPath(reportInfoFilePath, null)
            if (!reportInfoFilePath!!.equals(reportInfoDirectoryPath) && reportInfoFilePath!!.startsWith(reportInfoDirectoryPath + "/")) {
                Logger.logVerbose(LOG_TAG, "Deleting " + ReportInfo::class.java.getSimpleName() + " serialized object file at path \"" + reportInfoFilePath + "\"")
                val error: Error? = FileUtils.deleteRegularFile(ReportInfo::class.java.getSimpleName(), reportInfoFilePath, true)
                if (error != null) {
                    Logger.logErrorExtended(LOG_TAG, error.toString())
                }
            } else {
                Logger.logError(LOG_TAG, "Not deleting " + ReportInfo::class.java.getSimpleName() + " serialized object file at path \"" + reportInfoFilePath + "\" since its not under \"" + reportInfoDirectoryPath + "\"")
            }
        }

        /**
         * Delete [ReportInfo] serialized object files from cache older than x days. If a notification
         * has still not been opened after x days that's using a PendingIntent to ReportActivity, then
         * opening the notification will throw a file not found error, so choose days value appropriately
         * or check if a notification is still active if tracking notification ids.
         * The [Context] object passed must be of the same package with which [.newInstance]
         * was called since a call to [Context.getCacheDir] is made.
         *
         * @param context The [Context] for operations.
         * @param days The x amount of days before which files should be deleted. This must be `>=0`.
         * @param isSynchronous If set to `true`, then the command will be executed in the
         *                      caller thread and results returned synchronously.
         *                      If set to `false`, then a new thread is started run the commands
         *                      asynchronously in the background and control is returned to the caller thread.
         * @return Returns the `error` if deleting was not successful, otherwise `null`.
         **/
        @JvmStatic
        fun deleteReportInfoFilesOlderThanXDays(context: Context, days: Int, isSynchronous: Boolean): Error? {
            if (isSynchronous) {
                return deleteReportInfoFilesOlderThanXDaysInner(context, days)
            } else {
                object : Thread() {
                    override fun run() {
                        val error: Error? = deleteReportInfoFilesOlderThanXDaysInner(context, days)
                        if (error != null) {
                            Logger.logErrorExtended(LOG_TAG, error.toString())
                        }
                    }
                }.start()
                return null
            }
        }

        @JvmStatic
        private fun deleteReportInfoFilesOlderThanXDaysInner(context: Context, days: Int): Error? {
            // Only regular files are deleted and subdirectories are not checked
            val reportInfoDirectoryPath: String = getReportInfoDirectoryPath(context)
            Logger.logVerbose(LOG_TAG, "Deleting " + ReportInfo::class.java.getSimpleName() + " serialized object files under directory path \"" + reportInfoDirectoryPath + "\" older than " + days + " days")
            return FileUtils.deleteFilesOlderThanXDays(ReportInfo::class.java.getSimpleName(), reportInfoDirectoryPath, null, days, true, FileType.REGULAR.getValue())
        }
    }
}
