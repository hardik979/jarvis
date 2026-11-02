package dev.jarvis.assistant.brain.parse

/**
 * Everything Jarvis knows how to do. Today the rules in [CommandParser] produce these; in Phase 2
 * the on-device LLM will emit the same commands as tool calls, so both share one executor.
 */
sealed interface Command {
    data object Time : Command
    data object Date : Command
    data object Battery : Command
    data class Torch(val on: Boolean) : Command
    data class Volume(val change: VolumeChange) : Command
    data class Media(val action: MediaAction) : Command

    /** [time] is null when the user didn't say one. */
    data class Alarm(val time: ClockTime?) : Command

    /** [seconds] is null when the user didn't say how long. */
    data class Timer(val seconds: Int?) : Command
    data class OpenApp(val name: String) : Command

    /** [who] is a contact name or a phone number. */
    data class Call(val who: String) : Command
    data class Navigate(val place: String) : Command

    /** [place] is null for "here". */
    data class Weather(val place: String?, val tomorrow: Boolean) : Command
    data class WebSearch(val query: String) : Command
    data class YouTube(val query: String) : Command
    data class OpenSettings(val panel: SettingsPanel) : Command
    data object Hello : Command
    data object HowAreYou : Command
    data object WhoAreYou : Command
    data object Thanks : Command
    data object Help : Command

    /** Just the wake word: "Jarvis?" */
    data object Attention : Command
    data class Unknown(val text: String, val isQuestion: Boolean) : Command
}

/** A time of day, 24-hour. */
data class ClockTime(val hour: Int, val minute: Int)

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
    data object Max : VolumeChange
    data class Percent(val value: Int) : VolumeChange
}

enum class MediaAction { Play, Pause, Next, Previous }

enum class SettingsPanel { Wifi, Bluetooth, Internet }
