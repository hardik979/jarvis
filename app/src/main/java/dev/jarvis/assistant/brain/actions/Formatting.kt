package dev.jarvis.assistant.brain.actions

import dev.jarvis.assistant.brain.parse.ClockTime
import dev.jarvis.assistant.voice.Lang

/** Picks the English or Hindi wording of a reply. */
internal class Say(private val lang: Lang) {
    operator fun invoke(en: String, hi: String) = if (lang == Lang.Hindi) hi else en
}

private val ClockTime.hour12 get() = (hour % 12).let { if (it == 0) 12 else it }

private val ClockTime.minutePart get() = if (minute == 0) "" else ":" + minute.toString().padStart(2, '0')

/** "7 PM", "6:30 AM" */
internal fun ClockTime.english() = "$hour12$minutePart ${if (hour < 12) "AM" else "PM"}"

/** "शाम 7", "सुबह 6:30" (callers add बजे) */
internal fun ClockTime.hindi(): String {
    val partOfDay = when (hour) {
        in 4..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..19 -> "शाम"
        else -> "रात"
    }
    return "$partOfDay $hour12$minutePart"
}

private fun durationParts(seconds: Int) =
    listOf(seconds / 3600, seconds % 3600 / 60, seconds % 60).zip(listOf(0, 1, 2)).filter { it.first > 0 }

/** "1 hour 30 minutes" */
internal fun englishDuration(seconds: Int) = durationParts(seconds).joinToString(" ") { (n, unit) ->
    val word = listOf("hour", "minute", "second")[unit]
    "$n $word${if (n == 1) "" else "s"}"
}

/** "1 घंटे 30 मिनट" */
internal fun hindiDuration(seconds: Int) = durationParts(seconds).joinToString(" ") { (n, unit) ->
    "$n ${listOf("घंटे", "मिनट", "सेकंड")[unit]}"
}
