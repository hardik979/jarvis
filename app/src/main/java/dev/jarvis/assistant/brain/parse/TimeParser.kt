package dev.jarvis.assistant.brain.parse

import java.time.LocalTime
import kotlin.math.roundToInt

/** Clock times and durations in English, Hindi and Hinglish: "6:30 pm", "सुबह साढ़े 6 बजे", "dedh ghanta". */
object TimeParser {

    private val NUMBER_WORDS: Map<String, Double> = listOf(
        1.0 to listOf("one", "ek", "एक", "a", "an"),
        2.0 to listOf("two", "do", "दो"),
        3.0 to listOf("three", "teen", "तीन"),
        4.0 to listOf("four", "char", "chaar", "चार"),
        5.0 to listOf("five", "paanch", "panch", "पांच"),
        6.0 to listOf("six", "chhe", "che", "chhah", "छह", "छः", "छे"),
        7.0 to listOf("seven", "saat", "सात"),
        8.0 to listOf("eight", "aath", "आठ"),
        9.0 to listOf("nine", "nau", "नौ"),
        10.0 to listOf("ten", "das", "दस"),
        11.0 to listOf("eleven", "gyarah", "ग्यारह"),
        12.0 to listOf("twelve", "barah", "baarah", "बारह"),
        15.0 to listOf("fifteen", "pandrah", "पंद्रह"),
        20.0 to listOf("twenty", "bees", "बीस"),
        30.0 to listOf("thirty", "tees", "तीस"),
        40.0 to listOf("forty", "chalis", "chaalis", "चालीस"),
        45.0 to listOf("paintalis", "पैंतालीस"),
        50.0 to listOf("fifty", "pachas", "pachaas", "पचास"),
        0.5 to listOf("half", "aadha", "adha", "आधा", "आधे"),
        1.5 to listOf("dedh", "डेढ़"),
        2.5 to listOf("dhai", "ढाई"),
    ).flatMap { (value, words) -> words.map { normalize(it) to value } }.toMap()

    private val NUMBER = "\\d+|" + NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    // साढ़े 6 = 6½, सवा 6 = 6¼, पौने 6 = 5¾
    private val SAADE = arrayOf("saade", "sade", "saadhe", "साढ़े")
    private val SAWA = arrayOf("sawa", "sava", "सवा")
    private val PAUNE = arrayOf("paune", "पौने")
    private val SAADE_RE = Regex(alt(*SAADE))
    private val SAWA_RE = Regex(alt(*SAWA))

    private val HOURS = arrayOf("hours", "hour", "hrs", "hr", "ghante", "ghanta", "ghanton", "घंटे", "घंटा", "घंटों")
    private val MINUTES = arrayOf("minutes", "minute", "mins", "min", "minat", "मिनट", "मिनिट", "मिनटों")
    private val SECONDS = arrayOf("seconds", "second", "secs", "sec", "सेकंड", "सेकेंड", "सेकंड्स")
    private val HOUR_RE = Regex(alt(*HOURS))
    private val MINUTE_RE = Regex(alt(*MINUTES))

    private val DURATION = Regex(
        "(?<!$LETTER)(?:(${alt(*SAADE, *SAWA, *PAUNE)}) )?($NUMBER)\\s*(${alt(*HOURS, *MINUTES, *SECONDS)})(?!$LETTER)"
    )

    /** "5 minutes", "1 घंटा 30 मिनट", "aadha ghanta", "डेढ़ घंटे" → seconds; null if there's no duration. */
    fun durationSeconds(text: String): Int? {
        val t = normalize(text).replace(Regex("half an? hour"), "30 minutes")
        var seconds = 0.0
        for (match in DURATION.findAll(t)) {
            val (modifier, number, unit) = match.destructured
            val amount = (NUMBER_WORDS[number] ?: number.toDoubleOrNull() ?: continue) + fraction(modifier)
            seconds += amount * when {
                HOUR_RE.matches(unit) -> 3600
                MINUTE_RE.matches(unit) -> 60
                else -> 1
            }
        }
        return seconds.roundToInt().takeIf { it > 0 }
    }

    private val RELATIVE = Words("in", "after", "baad", "बाद")

    /** Alarm time: a clock time ("6:30 am", "सुबह साढ़े 6") or a delay ("in 20 minutes", "20 मिनट बाद"). */
    fun alarmTime(text: String, now: LocalTime): ClockTime? {
        val t = normalize(text)
        if (RELATIVE.isIn(t)) {
            durationSeconds(t)?.let { seconds ->
                val at = now.plusSeconds(seconds.toLong())
                return ClockTime(at.hour, at.minute)
            }
        }
        return clockTime(t, now)
    }

    private enum class Period { Morning, Afternoon, Evening, Night }

    private val MORNING = Words("am", "morning", "subah", "subha", "सुबह", "savere", "सवेरे")
    private val AFTERNOON = Words("afternoon", "dopahar", "dopaher", "दोपहर")
    private val EVENING = Words("pm", "evening", "shaam", "sham", "शाम")
    private val NIGHT = Words("night", "tonight", "raat", "रात")

    private val HH_MM = Regex("(?<!$LETTER)(\\d{1,2}):(\\d{2})(?!$LETTER)")
    private val H_BAJKAR_M = Regex("(?<!$LETTER)($NUMBER) ${alt("bajkar", "baj kar", "बजकर")} ($NUMBER)(?!$LETTER)")
    private val FRACTION_H = Regex("(?<!$LETTER)(${alt(*SAADE, *SAWA, *PAUNE)}) ($NUMBER)(?!$LETTER)")
    private val DEDH_DHAI = Regex("(?<!$LETTER)(${alt("dedh", "डेढ़", "dhai", "ढाई")})(?!$LETTER)")
    private val H_BAJE = Regex(
        "(?<!$LETTER)($NUMBER)\\s*${alt("baje", "बजे", "o clock", "oclock", "am", "pm")}(?!$LETTER)"
    )
    private val AT_H = Regex("(?<!$LETTER)${alt("at", "for", "ko", "को", "pe", "par", "पे", "पर")} (\\d{1,2})(?!$LETTER)")
    private val ANY_H = Regex("(?<!$LETTER)(\\d{1,2})(?!$LETTER)")

    /** "7", "7:30 pm", "शाम 6 बजे", "saade saat" → 24-hour time; picks the next 7 o'clock when am/pm is unsaid. */
    fun clockTime(text: String, now: LocalTime): ClockTime? {
        val t = normalize(text)
        val (hour, minute) = hourAndMinute(t) ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val hour24 = when (period(t)) {
            Period.Morning -> if (hour == 12) 0 else hour
            Period.Afternoon, Period.Evening -> if (hour in 1..11) hour + 12 else hour
            Period.Night -> when (hour) {
                12 -> 0
                in 1..4 -> hour
                in 5..11 -> hour + 12
                else -> hour
            }
            null -> if (hour == 0 || hour > 12) hour else nextOccurrence(hour, minute, now)
        }
        return ClockTime(hour24, minute)
    }

    private fun hourAndMinute(t: String): Pair<Int, Int>? {
        HH_MM.find(t)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        H_BAJKAR_M.find(t)?.let { match ->
            val hour = wholeNumber(match.groupValues[1]) ?: return null
            val minute = wholeNumber(match.groupValues[2]) ?: return null
            return hour to minute
        }
        FRACTION_H.find(t)?.let { match ->
            val hour = wholeNumber(match.groupValues[2]) ?: return null
            val modifier = match.groupValues[1]
            return when {
                SAADE_RE.matches(modifier) -> hour to 30
                SAWA_RE.matches(modifier) -> hour to 15
                else -> (if (hour == 1) 12 else hour - 1) to 45
            }
        }
        DEDH_DHAI.find(t)?.let { return (if (NUMBER_WORDS[it.value] == 1.5) 1 else 2) to 30 }
        H_BAJE.find(t)?.let { match -> wholeNumber(match.groupValues[1])?.let { return it to 0 } }
        AT_H.find(t)?.let { return it.groupValues[1].toInt() to 0 }
        ANY_H.find(t)?.let { return it.groupValues[1].toInt() to 0 }
        return null
    }

    private fun period(t: String): Period? = when {
        MORNING.isIn(t) -> Period.Morning
        AFTERNOON.isIn(t) -> Period.Afternoon
        EVENING.isIn(t) -> Period.Evening
        NIGHT.isIn(t) -> Period.Night
        else -> null
    }

    /** "7" means whichever of 7:00 and 19:00 comes next. */
    private fun nextOccurrence(hour: Int, minute: Int, now: LocalTime): Int {
        val nowMinutes = now.hour * 60 + now.minute
        fun wait(h: Int) = ((h * 60 + minute - nowMinutes) % 1440 + 1440) % 1440
        val morning = hour % 12
        return listOf(morning, morning + 12).minBy { wait(it).takeIf { w -> w > 0 } ?: 1440 }
    }

    private fun wholeNumber(token: String): Int? =
        token.toIntOrNull() ?: NUMBER_WORDS[token]?.takeIf { it % 1.0 == 0.0 }?.toInt()

    private fun fraction(modifier: String): Double = when {
        modifier.isEmpty() -> 0.0
        SAADE_RE.matches(modifier) -> 0.5
        SAWA_RE.matches(modifier) -> 0.25
        else -> -0.25
    }
}
