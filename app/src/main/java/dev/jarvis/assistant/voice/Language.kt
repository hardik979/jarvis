package dev.jarvis.assistant.voice

import java.util.Locale

/** What the user speaks in. Google's Hindi recogniser handles code-mixed Hinglish well, so it's the default. */
enum class SpeechLanguage(val tag: String, val label: String) {
    Hinglish("hi-IN", "Hindi / Hinglish"),
    English("en-IN", "English (India)"),
}

/** Which voice a piece of text should be read with. */
enum class Lang(val locale: Locale) {
    Hindi(Locale.forLanguageTag("hi-IN")),
    English(Locale.forLanguageTag("en-IN"));

    companion object {
        /** Any Devanagari means a Hindi voice; romanised Hinglish sounds better in the Indian English voice. */
        fun of(text: String): Lang = if (text.any { it in 'ऀ'..'ॿ' }) Hindi else English
    }
}
