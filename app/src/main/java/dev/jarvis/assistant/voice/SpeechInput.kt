package dev.jarvis.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.jarvis.assistant.util.servicesFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** Speech-to-text on top of Android's SpeechRecognizer. Each [listen] call captures one utterance. */
class SpeechInput(private val context: Context) {

    sealed interface Event {
        /** Mic loudness from 0 to 1, for animating the orb. */
        data class Level(val value: Float) : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Failed(val code: Int) : Event
    }

    /** Emits events for one utterance and completes after [Event.Final] or [Event.Failed]. */
    fun listen(language: SpeechLanguage): Flow<Event> = callbackFlow {
        val recognizer = createRecognizer()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // Recognisers report roughly -2..10 dB.
                trySend(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onEndOfSpeech() {
                trySend(Event.Level(0f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestText()?.let { trySend(Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                trySend(Event.Final(results.bestText().orEmpty()))
                close()
            }

            override fun onError(error: Int) {
                trySend(Event.Failed(error))
                close()
            }
        })
        recognizer.startListening(recognizerIntent(language))
        awaitClose { recognizer.destroy() }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer only works on the main thread.

    private fun createRecognizer(): SpeechRecognizer {
        val service = pickService(context)
        return when {
            service != null -> SpeechRecognizer.createSpeechRecognizer(context, service)
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun recognizerIntent(language: SpeechLanguage) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    companion object {
        // Best Hindi + English recognisers first; anything else installed is a last resort.
        private val PREFERRED = listOf(
            "com.google.android.googlequicksearchbox", // Google app
            "com.google.android.tts", // Speech Recognition & Synthesis
            "com.google.android.as", // Android System Intelligence
        )

        /**
         * Chooses the recogniser explicitly instead of using the system default, because the
         * default can end up pointing at Jarvis's own stub once Jarvis is the digital assistant.
         */
        fun pickService(context: Context): ComponentName? =
            context.packageManager.servicesFor(RecognitionService.SERVICE_INTERFACE)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .filter { it.packageName != context.packageName }
                .minByOrNull { PREFERRED.indexOf(it.packageName).takeIf { i -> i >= 0 } ?: PREFERRED.size }

        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't catch that. Tap the orb to try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "The speech engine needs internet for this language right now."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "This language isn't available in the speech engine."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech engine is busy. Try again."
            else -> "Speech error ($code)."
        }
    }
}

private fun Bundle?.bestText(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
