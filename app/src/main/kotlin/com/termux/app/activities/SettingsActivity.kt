package com.termux.app.activities

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.termux.R
import com.termux.app.models.UserAction
import com.termux.app.workspace.WorkspaceLanguage
import com.termux.shared.activities.ReportActivity
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.packages.PackageUtils
import com.termux.shared.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxWidgetAppSharedPreferences
import com.termux.shared.termux.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils

open class SettingsActivity : AppCompatActivity() {

    protected override fun attachBaseContext(base: Context) {
        super.attachBaseContext(WorkspaceLanguage.wrap(base))
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        WorkspaceLanguage.initialize(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.settings, RootPreferencesFragment()).commit()
        }
        val actionBar: ActionBar? = getSupportActionBar()
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    open class RootPreferencesFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context: Context? = getContext()
            if (context == null) {
                return
            }

            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            configureTermuxAPIPreference(context)
            configureTermuxFloatPreference(context)
            configureTermuxTaskerPreference(context)
            configureTermuxWidgetPreference(context)
            configureAboutPreference(context)
            configureDonatePreference(context)
        }

        private fun configureTermuxAPIPreference(context: Context) {
            val termuxAPIPreference: Preference? = findPreference("termux_api")
            if (termuxAPIPreference != null) {
                val preferences: TermuxAPIAppSharedPreferences? = TermuxAPIAppSharedPreferences.build(context, false)
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null)
            }
        }

        private fun configureTermuxFloatPreference(context: Context) {
            val termuxFloatPreference: Preference? = findPreference("termux_float")
            if (termuxFloatPreference != null) {
                val preferences: TermuxFloatAppSharedPreferences? = TermuxFloatAppSharedPreferences.build(context, false)
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null)
            }
        }

        private fun configureTermuxTaskerPreference(context: Context) {
            val termuxTaskerPreference: Preference? = findPreference("termux_tasker")
            if (termuxTaskerPreference != null) {
                val preferences: TermuxTaskerAppSharedPreferences? = TermuxTaskerAppSharedPreferences.build(context, false)
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null)
            }
        }

        private fun configureTermuxWidgetPreference(context: Context) {
            val termuxWidgetPreference: Preference? = findPreference("termux_widget")
            if (termuxWidgetPreference != null) {
                val preferences: TermuxWidgetAppSharedPreferences? = TermuxWidgetAppSharedPreferences.build(context, false)
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null)
            }
        }

        private fun configureAboutPreference(context: Context) {
            val aboutPreference: Preference? = findPreference("about")
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(Preference.OnPreferenceClickListener { preference ->
                    object : Thread() {
                        override fun run() {
                            val title = "About"

                            val aboutString = StringBuilder()
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, false))

                            val termuxPluginAppsInfo: String? = TermuxUtils.getTermuxPluginAppsInfoMarkdownString(context)
                            if (termuxPluginAppsInfo != null) {
                                aboutString.append("\n\n").append(termuxPluginAppsInfo)
                            }

                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))

                            val userActionName: String = UserAction.ABOUT.getName()
                            ReportActivity.startReportActivity(context, ReportInfo(userActionName, TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title, null, aboutString.toString(), null, false, userActionName, "" + Environment.getExternalStorageDirectory() + "/" + FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)))
                        }
                    }.start()

                    return@OnPreferenceClickListener true
                })
            }
        }

        private fun configureDonatePreference(context: Context) {
            val donatePreference: Preference? = findPreference("donate")
            if (donatePreference != null) {
                val signingCertificateSHA256Digest: String? = PackageUtils.getSigningCertificateSHA256DigestForPackage(context)
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Termux isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    val apkRelease: String? = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest)
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false)
                        return
                    } else {
                        donatePreference.setVisible(true)
                    }
                }

                donatePreference.setOnPreferenceClickListener(Preference.OnPreferenceClickListener { preference ->
                    ShareUtils.openURL(context, TermuxConstants.TERMUX_DONATE_URL)
                    return@OnPreferenceClickListener true
                })
            }
        }
    }
}
