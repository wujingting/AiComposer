package com.example.aicomposer.i18n

import java.util.Locale

/** UI languages. Anything other than Chinese or Japanese falls back to English. */
enum class Lang { ZH, JA, EN }

object I18n {
    /** Current UI language. Set from the system locale in MainActivity; defaults to English (also in unit tests). */
    @Volatile
    var lang: Lang = Lang.EN

    fun fromLocale(locale: Locale): Lang = when (locale.language) {
        "zh" -> Lang.ZH
        "ja" -> Lang.JA
        else -> Lang.EN
    }

    /** Separator for joining short items in a list ("、" in CJK, ", " in English). */
    val listSep: String get() = if (lang == Lang.EN) ", " else "、"
}

/** Capitalize the first letter (for English sentences that start with a subject name such as "the person"). */
fun String.cap(): String = replaceFirstChar { it.uppercaseChar() }

/** Subject name for short English phrases: drops a leading "the" and capitalizes ("the person" -> "Person"). */
fun String.bare(): String = removePrefix("the ").removePrefix("The ").cap()

/** Pick the string for the current UI language. */
fun tr(zh: String, ja: String, en: String): String = when (I18n.lang) {
    Lang.ZH -> zh
    Lang.JA -> ja
    Lang.EN -> en
}
