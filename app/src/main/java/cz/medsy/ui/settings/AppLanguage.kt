package cz.medsy.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cz.medsy.R

/**
 * The app ships two complete locales, so the choice is a plain three-way switch rather than a
 * list of every language Android knows — SYSTEM keeps following the device.
 *
 * The tags matter beyond the UI: `android:localeConfig` (see `res/xml/locales_config.xml`) tells
 * Android 13+ which locales the app supports, and a tag that is not listed there cannot be chosen
 * from the system settings either.
 */
enum class AppLanguage(val languageTag: String?, val labelRes: Int) {
    SYSTEM(null, R.string.settings_language_system),
    CZECH("cs", R.string.settings_language_czech),
    ENGLISH("en", R.string.settings_language_english),
    ;

    companion object {

        /**
         * Maps a locale list back to the switch position. `""` is what AppCompat reports for the
         * empty list, i.e. "no override", and any other language falls back to SYSTEM too — the
         * app has no strings for it, so pretending a choice was made would be a lie.
         */
        fun selectionOf(languageTags: String): AppLanguage {
            val base = languageTags.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.languageTag == base } ?: SYSTEM
        }

        fun selectionOf(locales: LocaleListCompat): AppLanguage = selectionOf(locales.toLanguageTags())

        fun current(): AppLanguage = selectionOf(AppCompatDelegate.getApplicationLocales())

        /**
         * Applies the choice for the whole process. AppCompat recreates the activities itself, so
         * the caller only has to keep its own state in sync.
         */
        fun apply(language: AppLanguage) {
            val tag = language.languageTag
            AppCompatDelegate.setApplicationLocales(
                if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
            )
        }
    }
}
