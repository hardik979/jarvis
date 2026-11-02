package dev.jarvis.assistant.brain

import dev.jarvis.assistant.brain.actions.CommandExecutor
import dev.jarvis.assistant.brain.parse.CommandParser
import dev.jarvis.assistant.brain.parse.replyLang

/** Phase 1 brain: instant rules for everyday commands, answering in the language the user spoke. */
class RuleBrain(private val executor: CommandExecutor) : Brain {
    override suspend fun respond(input: String): Reply =
        executor.execute(CommandParser.parse(input), replyLang(input))
}
