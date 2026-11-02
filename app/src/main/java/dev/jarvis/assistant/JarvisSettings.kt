package dev.jarvis.assistant

import android.content.Context
import androidx.core.content.edit
import dev.jarvis.assistant.voice.SpeechLanguage

class JarvisSettings(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var speechLanguage: SpeechLanguage
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, null)
            ?.let { name -> SpeechLanguage.entries.find { it.name == name } }
            ?: SpeechLanguage.Hinglish
        set(value) = prefs.edit { putString(KEY_SPEECH_LANGUAGE, value.name) }

    private companion object {
        const val KEY_SPEECH_LANGUAGE = "speech_language"
    }
}
