package dev.jarvis.assistant.voice

/**
 * How long Jarvis waits through pauses. [silenceMillis] is asked of the recogniser (some ignore it);
 * [continueMillis] is how long Jarvis keeps listening after a sentence that sounds unfinished.
 */
enum class ListenPatience(val label: String, val silenceMillis: Long, val continueMillis: Long) {
    Quick("Quick", silenceMillis = 1_000, continueMillis = 1_200),
    Normal("Normal", silenceMillis = 2_000, continueMillis = 2_500),
    Relaxed("Relaxed: take your time", silenceMillis = 3_000, continueMillis = 4_000),
}
