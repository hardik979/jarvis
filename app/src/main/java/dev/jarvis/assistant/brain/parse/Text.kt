package dev.jarvis.assistant.brain.parse

import dev.jarvis.assistant.voice.Lang
import java.text.Normalizer

/** One "letter" for word boundaries. Java's \b breaks words at Devanagari vowel signs, so rules use this. */
internal const val LETTER = "[\\p{L}\\p{M}\\p{N}]"

/**
 * Lowercases, folds Devanagari spelling variants (nukta, chandrabindu) and drops punctuation, so a
 * rule written once matches every way the recogniser might spell the same words.
 */
fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace("़", "") // nukta: ज़ → ज, ड़ → ड
        .replace('ँ', 'ं') // chandrabindu ँ → anusvara ं
        .replace(Regex("(\\d)\\.(\\d)"), "$1:$2") // 7.30 → 7:30
        .replace(Regex("(?<!\\p{L})([ap])\\.m\\.?"), "$1m") // p.m. → pm
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}:%]+"), " ")
        .trim()

/** Regex alternation of [words] (normalised, longest first so "laga do" wins over "laga"). */
internal fun alt(vararg words: String): String =
    words.map(::normalize).sortedByDescending { it.length }.joinToString("|", "(?:", ")") { Regex.escape(it) }

internal fun wordSet(vararg words: String): Set<String> = words.map(::normalize).toSet()

/** A set of keywords matched as whole words. */
internal class Words(vararg words: String) {
    private val regex = Regex("(?<!$LETTER)${alt(*words)}(?!$LETTER)")

    fun isIn(text: String) = regex.containsMatchIn(text)

    fun removeFrom(text: String) = text.replace(regex, " ").replace(Regex(" +"), " ").trim()
}

/** Drops [stop] words from both ends: "pe arijit ke gaane chalao" → "arijit ke gaane". */
internal fun stripEdges(text: String, stop: Set<String>): String {
    val words = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
    while (words.isNotEmpty() && words.first() in stop) words.removeAt(0)
    while (words.isNotEmpty() && words.last() in stop) words.removeAt(words.lastIndex)
    return words.joinToString(" ")
}

private val HINGLISH = Words(
    "hai", "hain", "kya", "karo", "kar", "ko", "ka", "ki", "ke", "mein", "mujhe", "batao", "kholo", "chalao",
    "lagao", "kitne", "kitna", "kitni", "baje", "aaj", "kal", "abhi", "kaise", "kaun", "nahi", "haan", "yaar",
    "bhai", "gaana", "mausam",
)

/** Reply in Hindi when the user spoke Hindi, in Devanagari or romanised Hinglish; otherwise in English. */
fun replyLang(input: String): Lang {
    val t = normalize(input)
    return if (Lang.of(t) == Lang.Hindi || HINGLISH.isIn(t)) Lang.Hindi else Lang.English
}
