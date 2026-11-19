package dev.jarvis.assistant.brain.parse

/** Decides whether a pause means "I'm done" or "I'm still thinking". */
object TurnTaking {

    // Words a sentence doesn't end on. "on", "in" and "up" are left out: "torch on" and "volume up" are complete.
    private val JOINING_WORDS = wordSet(
        "and", "or", "but", "to", "for", "the", "a", "an", "of", "with", "my", "then", "that", "so", "because",
        "about", "from", "aur", "ya", "lekin", "par", "pe", "ki", "ke", "ka", "ko", "se", "mein", "toh", "phir",
        "jo", "kyunki", "wala", "wali", "और", "या", "लेकिन", "पर", "पे", "कि", "की", "के", "का", "को", "से", "में",
        "तो", "फिर", "जो", "क्योंकि", "वाला", "वाली",
    )

    /** "Call mummy and…", "राहुल को…": a sentence that stops on a joining word isn't finished. */
    fun soundsUnfinished(text: String): Boolean = normalize(text).substringAfterLast(' ') in JOINING_WORDS

    /** Keep listening after a pause unless what we heard is already a complete request. */
    fun wantsMore(heard: String): Boolean = soundsUnfinished(heard) || when (val command = CommandParser.parse(heard)) {
        is Command.Unknown -> !command.isQuestion
        is Command.Alarm -> command.time == null
        is Command.Timer -> command.seconds == null
        else -> false
    }
}
