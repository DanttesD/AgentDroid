package com.termux.app.workspace

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** App-local language preference; no activity restart and no changes to shell locales.*/
object WorkspaceLanguage {
    private const val PREFS: String = "workspace-language"
    private const val KEY: String = "language"

    @JvmStatic
    fun choice(context: Context): String? {
        val value: String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system")
        return if (value!!.equals("ru") || value.equals("en")) value else "system"
    }

    @JvmStatic
    fun resolve(choice: String?, system: Locale): String? {
        return if (choice!!.equals("ru") || choice.equals("en")) choice else if (system.getLanguage().equals("ru")) "ru" else "en"
    }

    @JvmStatic
    fun code(context: Context): String? {
        return resolve(choice(context), Resources.getSystem().getConfiguration().getLocales().get(0))
    }

    @JvmStatic
    fun wrap(context: Context): Context {
        // Override only language. Copying the whole configuration pins portrait
        // orientation/window bounds/density in this context after phone rotation.
        val config = Configuration()
        config.setLocale(Locale(code(context)))
        return context.createConfigurationContext(config)
    }

    @JvmStatic
    fun initialize(context: Context) {
        WorkspaceText.resources = wrap(context).getResources()
        val english = Configuration()
        english.setLocale(Locale.ENGLISH)
        WorkspaceText.englishResources = context.createConfigurationContext(english).getResources()
        val app: Context? = context.getApplicationContext()
        if (app != null) {
            val config = Configuration(app.getResources().getConfiguration())
            config.setLocale(Locale(code(context)))
            app.getResources().updateConfiguration(config, app.getResources().getDisplayMetrics())
        }
    }

    @JvmStatic
    fun apply(activity: Activity) {
        initialize(activity)
        val config = Configuration(activity.getResources().getConfiguration())
        config.setLocale(Locale(code(activity)))
        activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics())
    }

    /** Preserve the selected UI language, but take window geometry from the system event.*/
    @JvmStatic
    fun configurationChanged(activity: Activity, current: Configuration) {
        val localized = Configuration(current)
        localized.setLocale(Locale(code(activity)))
        activity.getResources().updateConfiguration(localized, activity.getResources().getDisplayMetrics())
    }

    @JvmStatic
    fun select(context: Context, value: String) {
        if (!value.equals("system") && !value.equals("ru") && !value.equals("en")) {
            throw IllegalArgumentException("Invalid app language")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    @JvmStatic
    fun showPicker(activity: Activity, changed: Runnable) {
        val codes: Array<String> = arrayOf<String>("system", "ru", "en")
        val current: String? = choice(activity)
        var selected = 0
        for (i in 0 until (codes.size)) {
            if (codes[i].equals(current)) {
                selected = i
            }
        }
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Язык / Language")).setSingleChoiceItems(arrayOf<String?>(WorkspaceText.tr("Системный"), "Русский", "English"), selected, DialogInterface.OnClickListener { dialog, which ->
            select(activity, codes[which])
            apply(activity)
            dialog.dismiss()
            changed.run()
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }
}
