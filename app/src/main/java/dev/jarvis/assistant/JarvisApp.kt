package dev.jarvis.assistant

import android.app.Application
import dev.jarvis.assistant.voice.SpeechOutput

class JarvisApp : Application() {

    val settings by lazy { JarvisSettings(this) }

    /** Created on first use; the TTS engine then stays bound so later replies start instantly. */
    val speechOutput by lazy { SpeechOutput(this) }
}
