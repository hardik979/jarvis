package dev.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.jarvis.assistant.util.servicesFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech through the phone's TTS engine, choosing a Hindi or English voice per sentence. */
class SpeechOutput(context: Context) {

    private val requestedEngine = preferredEngine(context)
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val voiceByLang = mutableMapOf<Lang, Voice?>()

    private val tts = TextToSpeech(
        context.applicationContext,
        { status -> ready.complete(status == TextToSpeech.SUCCESS) },
        requestedEngine,
    )

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
        })
    }

    /** Package of the TTS engine in use, e.g. com.google.android.tts. */
    val engine: String get() = requestedEngine ?: tts.defaultEngine

    suspend fun hasVoice(lang: Lang): Boolean =
        ready.await() && tts.isLanguageAvailable(lang.locale) >= TextToSpeech.LANG_AVAILABLE

    /** Speaks [text] and suspends until it has been read out, or was stopped. */
    suspend fun speak(text: String) {
        if (!ready.await()) return
        val utterances = sentences(text).map { sentence ->
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>().also { pending[id] = it }
            useVoiceFor(Lang.of(sentence))
            if (tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
            done
        }
        try {
            utterances.awaitAll()
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    private fun finished(utteranceId: String) {
        pending.remove(utteranceId)?.complete(Unit)
    }

    private fun useVoiceFor(lang: Lang) {
        val voice = voiceByLang.getOrPut(lang) { bestVoice(lang.locale) }
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(lang.locale)
    }

    /** Best installed offline voice for the language, preferring the same country (hi-IN, en-IN). */
    private fun bestVoice(locale: Locale): Voice? =
        tts.voices.orEmpty()
            .filter {
                it.locale.language == locale.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }
            .maxWithOrNull(compareBy<Voice>({ it.locale.country == locale.country }, { it.quality }))

    private fun sentences(text: String): List<String> =
        text.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"

        // Break after . ! ? and the Hindi danda (।) so each sentence can get its own voice.
        private val SENTENCE_BREAK = Regex("(?<=[.!?।])\\s+|\\n+")

        /** Google's engine has the best Hindi voices; otherwise use the phone's default engine. */
        private fun preferredEngine(context: Context): String? =
            GOOGLE_TTS.takeIf { google ->
                context.packageManager.servicesFor(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                    .any { it.serviceInfo.packageName == google }
            }

        /** Opens the engine's screen for downloading voice data. */
        fun installVoicesIntent(engine: String): Intent =
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(engine)
    }
}
