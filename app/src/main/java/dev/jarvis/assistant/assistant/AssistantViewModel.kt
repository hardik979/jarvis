package dev.jarvis.assistant.assistant

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jarvis.assistant.JarvisApp
import dev.jarvis.assistant.JarvisSettings
import dev.jarvis.assistant.brain.Brain
import dev.jarvis.assistant.brain.Reply
import dev.jarvis.assistant.brain.RuleBrain
import dev.jarvis.assistant.brain.actions.CommandExecutor
import dev.jarvis.assistant.voice.SpeechInput
import dev.jarvis.assistant.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    val error: String? = null,
)

/** One-off things only the activity can do. */
sealed interface Effect {
    data class Launch(val intent: Intent) : Effect
    data object Close : Effect
}

/** Runs one conversation turn at a time: listen → think → act → speak. */
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

    /** Starts a fresh turn, cancelling whatever Jarvis was doing. */
    fun startTurn() {
        stopTurn()
        turn = viewModelScope.launch {
            val heard = listen() ?: return@launch
            _state.update { it.copy(phase = Phase.Thinking) }
            val reply = think(heard)
            _state.update { it.copy(phase = if (reply.speak) Phase.Speaking else Phase.Idle, reply = reply.text) }
            reply.launch?.let { _effects.send(Effect.Launch(it)) }
            if (reply.speak) speechOutput.speak(reply.text)
            _state.update { it.copy(phase = Phase.Idle) }
            // Like Siri, get out of the way once the job is done. A new turn cancels this.
            delay(AUTO_CLOSE_MS)
            _effects.send(Effect.Close)
        }
    }

    fun stopTurn() {
        turn?.cancel()
        speechOutput.stop()
        _state.update { it.copy(phase = Phase.Idle, level = 0f) }
    }

    fun onMicDenied() {
        _state.value = AssistantUiState(error = "Jarvis needs the microphone. Allow it in the Jarvis app.")
    }

    fun onLaunchFailed() {
        turn?.cancel()
        _state.update { it.copy(phase = Phase.Idle, error = "No app on this phone can do that.") }
    }

    private suspend fun think(heard: String): Reply = try {
        brain.respond(heard)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Reply("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Returns what the user said, or null if nothing usable was heard. */
    private suspend fun listen(): String? {
        _state.value = AssistantUiState(phase = Phase.Listening)
        var heard: String? = null
        speechInput.listen(settings.speechLanguage).collect { event ->
            when (event) {
                is SpeechInput.Event.Level -> _state.update { it.copy(level = event.value) }
                is SpeechInput.Event.Partial -> _state.update { it.copy(heard = event.text) }
                is SpeechInput.Event.Final -> {
                    heard = event.text.ifBlank { null }
                    _state.update { it.copy(heard = event.text, level = 0f) }
                }
                is SpeechInput.Event.Failed -> _state.update {
                    it.copy(phase = Phase.Idle, level = 0f, error = SpeechInput.describe(event.code))
                }
            }
        }
        if (heard == null && _state.value.error == null) {
            _state.update { it.copy(phase = Phase.Idle, error = "I didn't catch that. Tap the orb to try again.") }
        }
        return heard
    }

    override fun onCleared() {
        speechOutput.stop()
    }

    companion object {
        private const val AUTO_CLOSE_MS = 2_500L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JarvisApp
                AssistantViewModel(SpeechInput(app), app.speechOutput, RuleBrain(CommandExecutor(app)), app.settings)
            }
        }
    }
}
