package dev.jarvis.assistant

import android.content.Context
import androidx.core.content.edit
import dev.jarvis.assistant.voice.ListenPatience
import dev.jarvis.assistant.voice.SpeechLanguage

class JarvisSettings(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var speechLanguage: SpeechLanguage
        get() = enumPref(KEY_SPEECH_LANGUAGE, SpeechLanguage.Hinglish)
        set(value) = prefs.edit { putString(KEY_SPEECH_LANGUAGE, value.name) }

    var listenPatience: ListenPatience
        get() = enumPref(KEY_LISTEN_PATIENCE, ListenPatience.Normal)
        set(value) = prefs.edit { putString(KEY_LISTEN_PATIENCE, value.name) }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().find { it.name == name } } ?: default

    private companion object {
        const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val KEY_LISTEN_PATIENCE = "listen_patience"
    }
}
