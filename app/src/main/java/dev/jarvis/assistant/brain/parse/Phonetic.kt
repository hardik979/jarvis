package dev.jarvis.assistant.brain.parse

/**
 * Sound-alike keys, so "यूट्यूब", "YouTube" and "you tube" compare equal. A key keeps only consonant
 * sounds, folded into broad classes (aspirated = plain, w = v, z = j); vowels, h and y are dropped.
 */
object Phonetic {

    private val DEVANAGARI: Map<Char, Char?> = mapOf(
        'क' to 'k', 'ख' to 'k', 'ग' to 'g', 'घ' to 'g', 'ङ' to 'n',
        'च' to 'c', 'छ' to 'c', 'ज' to 'j', 'झ' to 'j', 'ञ' to 'n',
        'ट' to 't', 'ठ' to 't', 'ड' to 'd', 'ढ' to 'd', 'ण' to 'n',
        'त' to 't', 'थ' to 't', 'द' to 'd', 'ध' to 'd', 'न' to 'n',
        'प' to 'p', 'फ' to 'f', 'ब' to 'b', 'भ' to 'b', 'म' to 'm',
        'य' to null, 'र' to 'r', 'ल' to 'l', 'ळ' to 'l', 'व' to 'v', 'ह' to null,
        'श' to 's', 'ष' to 's', 'स' to 's', 'ं' to 'n',
    )

    fun key(text: String): String {
        val t = normalize(text)
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            val next = t.getOrNull(i + 1)
            val sound: Char? = when {
                c in 'ऀ'..'ॿ' -> DEVANAGARI[c] // vowels, vowel signs and virama map to nothing
                c == 'p' && next == 'h' -> { i++; 'f' }
                c == 'c' && next == 'h' -> { i++; 'c' }
                c == 's' && next == 'h' -> { i++; 's' }
                c == 'c' && next == 'k' -> { i++; 'k' }
                c == 'c' -> if (next == 'e' || next == 'i' || next == 'y') 's' else 'k'
                c == 'q' -> 'k'
                c == 'x' -> { out.append('k'); 's' }
                c == 'w' -> 'v'
                c == 'z' -> 'j'
                c in "bdfgjklmnprstv" -> c
                else -> null // vowels, h, y, digits, spaces
            }
            if (sound != null && out.lastOrNull() != sound) out.append(sound)
            i++
        }
        return out.toString()
    }
}

/** Finds which installed app or contact the user meant. */
object NameMatcher {

    private val RELATIONS: List<Set<String>> = listOf(
        wordSet("mom", "mummy", "mumma", "mommy", "mother", "maa", "ma", "mammi", "मम्मी", "मां", "माता", "मॉम"),
        wordSet("dad", "papa", "pappa", "daddy", "father", "pitaji", "पापा", "पिताजी", "डैडी", "डैड"),
        wordSet("brother", "bhai", "bhaiya", "भाई", "भैया"),
        wordSet("sister", "didi", "behen", "दीदी", "बहन"),
        wordSet("wife", "biwi", "बीवी", "पत्नी"),
        wordSet("husband", "pati", "पति"),
    )

    /** "Mom" might be saved as "Mummy" or "Maa": try every way of saying the relation. */
    fun relationVariants(spoken: String): List<String> {
        val s = normalize(spoken)
        return listOf(s) + (RELATIONS.firstOrNull { s in it }.orEmpty() - s)
    }

    /** The candidate whose name best matches any of the [spoken] variants, or null if none is close. */
    fun <T> best(spoken: List<String>, candidates: List<T>, name: (T) -> String): T? =
        candidates
            .map { candidate -> candidate to spoken.maxOf { score(it, name(candidate)) } }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<T, Int>> { it.second }.thenByDescending { name(it.first).length })
            ?.first

    internal fun score(spoken: String, name: String): Int {
        val s = normalize(spoken)
        val n = normalize(name)
        if (s.isEmpty() || n.isEmpty()) return 0
        if (s == n) return 100
        val sKey = Phonetic.key(s)
        val nKey = Phonetic.key(n)
        val nWords = n.split(' ')
        return when {
            sKey.isNotEmpty() && sKey == nKey -> 90
            s in nWords -> 85
            sKey.isNotEmpty() && nWords.any { Phonetic.key(it) == sKey } -> 80
            s.length >= 3 && n.startsWith(s) -> 75
            sKey.length >= 3 && distance(sKey, nKey) == 1 -> 60
            sKey.length >= 3 && nWords.any { distance(sKey, Phonetic.key(it)) == 1 } -> 55
            s.length >= 4 && s in n -> 50
            else -> 0
        }
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }
}
