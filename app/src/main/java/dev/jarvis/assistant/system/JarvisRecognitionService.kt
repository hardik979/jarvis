package dev.jarvis.assistant.system

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires every assistant to name a RecognitionService. Jarvis doesn't offer speech
 * recognition to other apps (yet), so this one declines. Jarvis itself uses Google's recogniser.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
