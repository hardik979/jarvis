package dev.jarvis.assistant.brain

import dev.jarvis.assistant.brain.actions.CommandExecutor
import dev.jarvis.assistant.brain.parse.Command
import dev.jarvis.assistant.brain.parse.CommandParser
import dev.jarvis.assistant.brain.parse.TurnTaking
import dev.jarvis.assistant.brain.parse.replyLang
import dev.jarvis.assistant.llm.LlmDecision
import dev.jarvis.assistant.llm.LocalLlm

/**
 * Phase 2 brain: the rules answer known commands instantly; anything they don't understand goes to the
 * on-device LLM, which either picks an action (run by the same executor) or chats back.
 * Without the model downloaded it behaves exactly like the rules alone.
 */
class HybridBrain(private val executor: CommandExecutor, private val llm: LocalLlm) : Brain {

    override suspend fun respond(input: String): Reply {
        val lang = replyLang(input)
        val command = CommandParser.parse(input)
        if (command !is Command.Unknown || !llm.isAvailable) return executor.execute(command, lang)
        val reply = when (val decision = llm.decide(input)) {
            is LlmDecision.Act -> executor.execute(decision.command, lang)
            // Keep listening after a chat reply, so it feels like a conversation.
            is LlmDecision.Chat -> Reply(decision.reply, followUp = FollowUp())
            null -> executor.execute(command, lang)
        }
        return reply.copy(debug = llm.lastStats)
    }

    override fun wantsMore(heard: String) = TurnTaking.wantsMore(heard)

    override suspend fun prepare() = llm.warmUp()

    override fun newConversation() = llm.newConversation()
}
