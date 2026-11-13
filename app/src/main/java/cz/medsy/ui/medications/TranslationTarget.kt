package cz.medsy.ui.medications

import androidx.annotation.StringRes
import cz.medsy.R

/**
 * The languages a medication's text can be translated into.
 *
 * Deliberately short: each language costs a copy of the text in the cache and a request per field,
 * and the labels are endonyms so they read the same whichever language the app is in.
 */
enum class TranslationTarget(val language: String, @StringRes val labelRes: Int) {
    ENGLISH("en", R.string.translation_lang_en),
    GERMAN("de", R.string.translation_lang_de),
    SLOVAK("sk", R.string.translation_lang_sk),
    ;

    companion object {
        fun of(language: String): TranslationTarget? = entries.firstOrNull { it.language == language }
    }
}
