package dev.jarvis.assistant.assistant

import android.content.Intent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jarvis.assistant.JarvisApp
import dev.jarvis.assistant.JarvisSettings
import dev.jarvis.assistant.brain.Brain
import dev.jarvis.assistant.brain.FollowUp
import dev.jarvis.assistant.brain.HybridBrain
import dev.jarvis.assistant.brain.Reply
import dev.jarvis.assistant.brain.actions.CommandExecutor
import dev.jarvis.assistant.voice.SpeechInput
import dev.jarvis.assistant.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Idle, Listening, Thinking, Speaking }

data class AssistantUiState(
    val phase: Phase = Phase.Idle,
    /** Mic loudness from 0 to 1 while listening. */
    val level: Float = 0f,
    val heard: String = "",
    val reply: String = "",
    val debug: String? = null,
    /** Listening through a pause because the sentence sounded unfinished. */
    val waitingForMore: Boolean = false,
    val error: String? = null,
)

/** One-off things only the activity can do. */
sealed interface Effect {
    data class Launch(val intent: Intent) : Effect
    data object Close : Effect
}

/** Runs the conversation: listen → think → act → speak, and again while Jarvis expects an answer. */
class AssistantViewModel(
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
    private val brain: Brain,
    private val settings: JarvisSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = _effects.receiveAsFlow()

    private var turn: Job? = null

    /** Set when the user taps the orb to say they're done talking. */
    private var userFinished = false

    /** Starts a fresh conversation, cancelling whatever Jarvis was doing. */
    fun startTurn() {
        stopTurn()
        brain.newConversation()
        // Load the LLM while the user is still talking, so the answer isn't held up by it.
        viewModelScope.launch { runCatching { brain.prepare() } }
        turn = viewModelScope.launch { converse() }
    }

    fun stopTurn() {
        turn?.cancel()
        speechOutput.stop()
        _state.update { it.copy(phase = Phase.Idle, level = 0f, waitingForMore = false) }
    }

    /** Orb tapped while listening: stop waiting and use what was said. */
    fun finishSpeaking() {
        userFinished = true
        speechInput.finish()
    }

    fun onMicDenied() {
        _state.value = AssistantUiState(error = "Jarvis needs the microphone. Allow it in the Jarvis app.")
    }

    fun onLaunchFailed() {
        turn?.cancel()
        _state.update { it.copy(phase = Phase.Idle, error = "No app on this phone can do that.") }
    }

    private suspend fun converse() {
        var followUp: FollowUp? = null
        while (true) {
            val heard = hear(isFollowUp = followUp != null)
            if (heard == null) {
                // Silence after a follow-up question just means "nothing more"; real errors stay on screen.
                if (followUp != null && _state.value.error == null) _effects.send(Effect.Close)
                return
            }
            _state.update { it.copy(phase = Phase.Thinking) }
            val reply = think(listOfNotNull(followUp?.prefix, heard).joinToString(" ").trim())
            _state.update {
                it.copy(phase = if (reply.speak) Phase.Speaking else Phase.Idle, reply = reply.text, debug = reply.debug)
            }
            reply.launch?.let { _effects.send(Effect.Launch(it)) }
            if (reply.speak) speechOutput.speak(reply.text)
            followUp = reply.followUp ?: break
        }
        _state.update { it.copy(phase = Phase.Idle) }
        // Like Siri, get out of the way once the job is done. A new turn cancels this.
        delay(AUTO_CLOSE_MS)
        _effects.send(Effect.Close)
    }

    private suspend fun think(input: String): Reply = try {
        brain.respond(input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Reply("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * Listens until the user has really finished. After a pause, if the sentence sounds unfinished
     * ("Mummy ko…"), keeps listening and joins the parts. Returns null if nothing usable was said.
     */
    private suspend fun hear(isFollowUp: Boolean): String? {
        userFinished = false
        _state.value = AssistantUiState(phase = Phase.Listening)
        val first = listenOnce(before = "", startWithin = if (isFollowUp) FOLLOW_UP_WINDOW_MS else null)
        if (first == null) {
            val code = lastError ?: SpeechRecognizer.ERROR_NO_MATCH
            val error = if (isFollowUp && SpeechInput.isSilence(code)) null else SpeechInput.describe(code)
            _state.update { it.copy(phase = Phase.Idle, level = 0f, error = error) }
            return null
        }
        var text: String = first
        while (!userFinished && brain.wantsMore(text)) {
            _state.update { it.copy(waitingForMore = true) }
            val more = listenOnce(before = text, startWithin = settings.listenPatience.continueMillis) ?: break
            text = "$text $more"
        }
        _state.update { it.copy(heard = text, waitingForMore = false) }
        return text
    }

    private var lastError: Int? = null

    /**
     * One recogniser session; [before] is shown ahead of the live transcript. With [startWithin], gives
     * up if the user hasn't started talking by then. Returns null if nothing was said.
     */
    private suspend fun listenOnce(before: String, startWithin: Long?): String? {
        repeat(2) { attempt ->
            val text = session(before, startWithin)
            // Restarting right after a session can briefly find the recogniser still busy.
            if (text != null || lastError != SpeechRecognizer.ERROR_RECOGNIZER_BUSY || attempt == 1) return text
            delay(300)
        }
        return null
    }

    private suspend fun session(before: String, startWithin: Long?): String? = coroutineScope {
        var started = false
        var heard: String? = null
        lastError = null
        val recording = launch {
            speechInput.listen(settings.speechLanguage, settings.listenPatience.silenceMillis).collect { event ->
                when (event) {
                    SpeechInput.Event.SpeechStarted -> started = true
                    is SpeechInput.Event.Level -> _state.update { it.copy(level = event.value) }
                    is SpeechInput.Event.Partial -> {
                        started = true
                        _state.update { it.copy(heard = "$before ${event.text}".trim()) }
                    }
                    is SpeechInput.Event.Final -> heard = event.text.ifBlank { null }
                    is SpeechInput.Event.Failed -> lastError = event.code
                }
            }
        }
        val watchdog = startWithin?.let { window ->
            launch {
                delay(window)
                if (!started) recording.cancel()
            }
        }
        recording.join()
        watchdog?.cancel()
        _state.update { it.copy(level = 0f) }
        heard
    }

    override fun onCleared() {
        speechOutput.stop()
    }

    companion object {
        private const val AUTO_CLOSE_MS = 2_500L

        /** How long to wait for an answer after Jarvis asked something. */
        private const val FOLLOW_UP_WINDOW_MS = 6_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JarvisApp
                AssistantViewModel(SpeechInput(app), app.speechOutput, HybridBrain(CommandExecutor(app), app.llm), app.settings)
            }
        }
    }
}
