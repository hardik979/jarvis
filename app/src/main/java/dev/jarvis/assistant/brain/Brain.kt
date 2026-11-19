package dev.jarvis.assistant.brain

import android.content.Intent

/** Turns what the user said into what Jarvis says and does. Phase 1 uses rules; Phase 2 adds the on-device LLM. */
fun interface Brain {
    suspend fun respond(input: String): Reply

    /** True when [heard] sounds unfinished, so Jarvis should keep listening through the pause. */
    fun wantsMore(heard: String): Boolean = false

    /** Called when Jarvis opens, while the user is still talking: a chance to load anything slow. */
    suspend fun prepare() {}

    /** A fresh conversation: forget earlier exchanges. */
    fun newConversation() {}
}

/** Jarvis's answer to one request. */
data class Reply(
    val text: String,
    /** Activity to start, e.g. the app the user asked to open. */
    val launch: Intent? = null,
    /** False when there's nothing worth saying aloud, e.g. we're switching to another app or the music. */
    val speak: Boolean = true,
    /** Set when Jarvis asked something or invited a reply: listen again without being called. */
    val followUp: FollowUp? = null,
    /** Small print under the reply, e.g. LLM timings. */
    val debug: String? = null,
)

/**
 * The answer to a follow-up is read as [prefix] + answer, so "7 baje" after "For what time?"
 * becomes "alarm 7 baje".
 */
data class FollowUp(val prefix: String = "")
