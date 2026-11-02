package dev.jarvis.assistant.brain

import android.content.Intent

/** Turns what the user said into what Jarvis says and does. Phase 1 uses rules; Phase 2 adds the on-device LLM. */
fun interface Brain {
    suspend fun respond(input: String): Reply
}

/** Jarvis's answer to one request. */
data class Reply(
    val text: String,
    /** Activity to start, e.g. the app the user asked to open. */
    val launch: Intent? = null,
    /** False when there's nothing worth saying aloud, e.g. we're switching to another app or the music. */
    val speak: Boolean = true,
)
