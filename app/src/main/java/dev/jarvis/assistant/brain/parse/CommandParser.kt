package dev.jarvis.assistant.brain.parse

import java.time.LocalTime

/**
 * Fast, rule-based understanding of everyday commands in English, Hindi and Hinglish.
 * Pure Kotlin with no Android dependencies, so it's covered by plain JVM unit tests.
 */
object CommandParser {

    fun parse(input: String, now: LocalTime = LocalTime.now()): Command {
        val t = clean(input)
        if (t.isEmpty()) return if (input.isBlank()) Command.Unknown(input, isQuestion = false) else Command.Attention
        // Most specific first: "timer" must win over "time", "torch on" over "open".
        return timer(t) ?: alarm(t, now) ?: youTube(t) ?: call(t) ?: navigate(t) ?: weather(t)
            ?: torch(t) ?: volume(t) ?: media(t) ?: battery(t) ?: settings(t) ?: webSearch(t)
            ?: openApp(t) ?: time(t) ?: date(t) ?: smallTalk(t)
            ?: Command.Unknown(input, isQuestion = QUESTION.isIn(t))
    }

    // The wake word and politeness carry no meaning for the rules.
    private val WAKE_WORD =
        Regex("^(?:${alt("hey", "hi", "ok", "okay", "हे", "ओके")} )?${alt("jarvis", "जार्विस", "जारविस")}(?: |$)")
    private val FILLERS = Words(
        "please", "plz", "pls", "प्लीज", "zara", "जरा", "can you", "could you", "will you", "ek kaam karo",
        "ek kaam kar do", "एक काम करो", "एक काम कर दो", "thoda", "थोड़ा", "yaar", "यार",
    )

    private fun clean(input: String) = FILLERS.removeFrom(normalize(input).replace(WAKE_WORD, ""))

    // ---- Timer & alarm ----

    private val TIMER = Words("timer", "टाइमर", "countdown")

    private fun timer(t: String): Command? =
        if (TIMER.isIn(t)) Command.Timer(TimeParser.durationSeconds(t)) else null

    private val ALARM = Words(
        "alarm", "alarms", "alaram", "अलार्म", "wake me", "utha dena", "utha do", "uthana", "उठा देना", "उठा दो",
        "उठाना", "jaga dena", "jaga do", "जगा देना", "जगा दो", "जगाना",
    )
    private val SET_ALARM = Words(
        "set", "laga", "lagao", "laga do", "lagana", "लगा", "लगाओ", "लगा दो", "लगाना", "सेट", "rakh", "rakho",
        "रख", "रखो", "रख दो", "wake", "utha", "uthana", "उठा", "उठाना", "jaga", "जगा", "जगाना",
    )

    private fun alarm(t: String, now: LocalTime): Command? {
        if (!ALARM.isIn(t)) return null
        val time = TimeParser.alarmTime(t, now)
        // "Open alarms" has neither a time nor a verb like "set", so leave it for openApp.
        if (time == null && !SET_ALARM.isIn(t)) return null
        return Command.Alarm(time)
    }

    // ---- YouTube & media ----

    private val YOUTUBE = Words("youtube", "you tube", "यूट्यूब", "यूटूब", "यू ट्यूब")
    private val PLAY_VERB = Words(
        "play", "chalao", "chala do", "lagao", "laga do", "bajao", "dikhao", "dikha do", "search", "प्ले", "चलाओ",
        "चला दो", "लगाओ", "लगा दो", "बजाओ", "दिखाओ", "दिखा दो", "सर्च",
    )

    // Verbs and particles around the real subject: "YouTube pe X chalao" → X.
    private val PLAY_PARTICLES = wordSet(
        "play", "chalao", "chala", "lagao", "laga", "bajao", "baja", "dikhao", "dikha", "search", "karo", "kar", "do",
        "on", "pe", "par", "mein", "me", "for", "some", "start", "resume", "continue", "shuru", "प्ले", "चलाओ", "चला",
        "लगाओ", "लगा", "बजाओ", "बजा", "दिखाओ", "दिखा", "सर्च", "करो", "कर", "दो", "पे", "पर", "में", "शुरू",
    )

    private fun youTube(t: String): Command? {
        if (!YOUTUBE.isIn(t) || !PLAY_VERB.isIn(t)) return null
        val query = stripEdges(YOUTUBE.removeFrom(t), PLAY_PARTICLES)
        return if (query.isEmpty()) Command.OpenApp("youtube") else Command.YouTube(query)
    }

    private val MUSIC = Words(
        "song", "songs", "gaana", "gana", "gaane", "gane", "गाना", "गाने", "music", "म्यूजिक", "track", "गीत",
        "geet", "playlist",
    )
    private val GENERIC_MUSIC = wordSet(
        "song", "songs", "gaana", "gana", "gaane", "gane", "गाना", "गाने", "music", "म्यूजिक", "track", "गीत",
        "geet", "koi", "कोई", "some", "a", "the", "my", "mere", "मेरे", "ek", "एक", "achha", "अच्छा", "sa", "सा",
    )
    private val NEXT = Words("next", "skip", "agla", "agle", "अगला", "अगले", "aage", "आगे")
    private val PREVIOUS = Words("previous", "pichla", "pichhla", "पिछला", "pichle", "पिछले", "peeche", "पीछे")
    private val PAUSE = Words("pause", "पॉज")
    private val STOP = Words("stop", "स्टॉप", "band", "बंद", "ruko", "रुको", "roko", "रोको", "rok do", "रोक दो")
    private val RESUME = Words(
        "resume", "रिज्यूम", "continue", "play", "प्ले", "chalao", "chala do", "चलाओ", "चला दो", "bajao", "बजाओ",
        "baja do", "बजा दो", "shuru", "शुरू", "lagao", "लगाओ", "start",
    )
    private val PLAY_SOMETHING = Regex("^${alt("play", "bajao", "बजाओ", "प्ले")} (.+)$")

    private fun media(t: String): Command? {
        val music = MUSIC.isIn(t)
        val short = t.split(' ').size <= 3
        return when {
            NEXT.isIn(t) && (music || short) -> Command.Media(MediaAction.Next)
            PREVIOUS.isIn(t) && (music || short) -> Command.Media(MediaAction.Previous)
            PAUSE.isIn(t) || (STOP.isIn(t) && music) -> Command.Media(MediaAction.Pause)
            music && RESUME.isIn(t) -> playOrSearch(t)
            PLAY_SOMETHING.matches(t) -> playOrSearch(t)
            t in wordSet("play", "resume", "continue", "प्ले") -> Command.Media(MediaAction.Play)
            else -> null
        }
    }

    /** "Play some music" resumes the player; "Arijit ke gaane chalao" searches YouTube. */
    private fun playOrSearch(t: String): Command {
        val subject = stripEdges(t, PLAY_PARTICLES)
        val specific = subject.split(' ').any { it.isNotEmpty() && it !in GENERIC_MUSIC }
        return if (specific) Command.YouTube(subject) else Command.Media(MediaAction.Play)
    }

    // ---- Calls & navigation ----

    private val CALL_TO = Regex("^${alt("call", "dial", "ring", "video call", "कॉल")}(?: to)? (.+)$")
    private val TO_CALL =
        Regex("^(.+?) ${alt("ko", "को")} ${alt("call", "phone", "fone", "कॉल", "फोन", "video call", "वीडियो कॉल")}(?: .*)?$")
    private val NOT_A_NAME = wordSet(
        "my", "mere", "meri", "mera", "मेरे", "मेरी", "मेरा", "the", "now", "abhi", "अभी", "karo", "करो", "kar",
        "कर", "do", "दो", "lagao", "लगाओ", "ko", "को",
    )

    private fun call(t: String): Command? {
        val who = (CALL_TO.find(t) ?: TO_CALL.find(t))?.groupValues?.get(1) ?: return null
        return stripEdges(who, NOT_A_NAME).takeIf { it.isNotEmpty() }?.let { Command.Call(it) }
    }

    private val NAVIGATE_TO = Regex(
        "^${alt("navigate", "navigation", "directions", "direction", "route", "take me", "get me", "how do i get",
            "how to get", "how do i go", "how to go", "नेविगेट")}(?: me)?(?: to| till)? (.+)$"
    )
    private val ROUTE_TO = Regex(
        "^(.+?) ${alt("ka", "का", "ke liye", "के लिए", "tak ka", "तक का")} " +
            "${alt("rasta", "raasta", "रास्ता", "route", "रूट", "direction", "directions", "डायरेक्शन")}(?: .*)?$"
    )
    private val GO_TO = Regex(
        "^(.+?) ${alt("navigate karo", "नेविगेट करो", "le chalo", "ले चलो", "jana hai", "जाना है", "kaise jaun",
            "कैसे जाऊं", "kaise jaye", "कैसे जाएं", "kaise pahunchu", "कैसे पहुंचूं")}(?: .*)?$"
    )
    private val NOT_A_PLACE = wordSet("mujhe", "मुझे", "the", "me", "tak", "तक", "yahan", "यहां", "se", "से")

    private fun navigate(t: String): Command? {
        val place = (NAVIGATE_TO.find(t) ?: ROUTE_TO.find(t) ?: GO_TO.find(t))?.groupValues?.get(1) ?: return null
        return stripEdges(place, NOT_A_PLACE).takeIf { it.isNotEmpty() }?.let { Command.Navigate(it) }
    }

    // ---- Weather ----

    private val WEATHER = Words(
        "weather", "mausam", "mosam", "mausum", "मौसम", "temperature", "temp", "तापमान", "tapman", "forecast",
        "barish", "baarish", "बारिश", "rain", "raining", "बरसात", "garmi", "गर्मी", "thand", "ठंड", "umbrella",
        "chhata", "छाता",
    )
    private val TOMORROW = Words("tomorrow", "kal", "कल")
    private val PLACE_AFTER = Regex(
        "(?<!$LETTER)${alt("in", "at", "of", "for")} ([\\p{L}\\p{M} ]+?)" +
            "(?: ${alt("today", "tomorrow", "now", "right now", "this week", "aaj", "kal")})?$"
    )
    private val PLACE_BEFORE = Regex(
        "([\\p{L}\\p{M}]+) ${alt("mein", "me", "में", "ka", "का", "ki", "की", "ke", "के")} " +
            alt("mausam", "मौसम", "weather", "tapman", "तापमान", "temperature", "barish", "baarish", "बारिश")
    )
    private val NOT_A_CITY = wordSet(
        "aaj", "आज", "kal", "कल", "abhi", "अभी", "yahan", "यहां", "here", "mere", "मेरे", "shahar", "शहर", "today",
        "tomorrow", "the", "bahar", "बाहर", "now", "you", "it", "this", "आपके", "aapke", "tumhare",
    )

    private fun weather(t: String): Command? {
        if (!WEATHER.isIn(t)) return null
        val place = (PLACE_AFTER.find(t) ?: PLACE_BEFORE.find(t))?.groupValues?.get(1)
            ?.let { stripEdges(it, NOT_A_CITY) }
            ?.takeIf { it.isNotEmpty() }
        return Command.Weather(place, tomorrow = TOMORROW.isIn(t))
    }

    // ---- Phone controls ----

    private val TORCH = Words(
        "torch", "flashlight", "flash light", "flash", "light", "lights", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ्लैश", "लाइट",
    )
    private val OFF = Words("off", "band", "bujha", "bujhao", "bujha do", "बंद", "बुझा", "बुझाओ", "बुझा दो", "ऑफ", "disable")

    private fun torch(t: String): Command? = if (TORCH.isIn(t)) Command.Torch(on = !OFF.isIn(t)) else null

    private val VOLUME = Words(
        "volume", "वॉल्यूम", "वॉल्युम", "वोल्यूम", "awaz", "awaaz", "aawaz", "आवाज", "sound", "साउंड", "louder",
        "quieter", "mute", "unmute", "म्यूट", "अनम्यूट",
    )
    private val UP = Words(
        "up", "increase", "raise", "louder", "badhao", "badha", "badha do", "zyada", "jyada", "tez", "बढ़ाओ",
        "बढ़ा", "बढ़ा दो", "ज्यादा", "तेज", "high",
    )
    private val DOWN = Words(
        "down", "decrease", "lower", "reduce", "quieter", "kam", "ghatao", "ghata", "dheema", "dhire", "कम", "घटाओ",
        "घटा", "धीमा", "धीमी", "low",
    )
    private val MUTE = Words("mute", "म्यूट", "silent", "साइलेंट", "band", "बंद")
    private val UNMUTE = Words("unmute", "अनम्यूट")
    private val MAX = Words("max", "maximum", "full", "फुल", "पूरा", "pura", "poori", "पूरी")
    private val PERCENT = Regex("(?<!$LETTER)(\\d{1,3})(?!$LETTER)")

    private fun volume(t: String): Command? {
        if (!VOLUME.isIn(t)) return null
        val percent = PERCENT.find(t)?.groupValues?.get(1)?.toInt()
        val change = when {
            UNMUTE.isIn(t) -> VolumeChange.Unmute
            MUTE.isIn(t) -> VolumeChange.Mute
            MAX.isIn(t) -> VolumeChange.Max
            percent != null -> VolumeChange.Percent(percent.coerceIn(0, 100))
            DOWN.isIn(t) -> VolumeChange.Down
            UP.isIn(t) -> VolumeChange.Up
            else -> return null
        }
        return Command.Volume(change)
    }

    private val BATTERY = Words("battery", "बैटरी", "charge", "charging", "चार्ज", "चार्जिंग")

    private fun battery(t: String): Command? = if (BATTERY.isIn(t)) Command.Battery else null

    private val WIFI = Words("wifi", "wi fi", "वाईफाई", "वाई फाई", "वाइफाइ", "वायफाय")
    private val BLUETOOTH = Words("bluetooth", "blue tooth", "ब्लूटूथ")
    private val INTERNET = Words("mobile data", "data", "internet", "इंटरनेट", "डेटा", "डाटा")
    private val TOGGLE = Words(
        "on", "off", "chalu", "band", "enable", "disable", "turn", "settings", "setting", "kholo", "open", "connect",
        "ऑन", "ऑफ", "चालू", "बंद", "सेटिंग", "सेटिंग्स", "खोलो", "कनेक्ट",
    )

    private fun settings(t: String): Command? {
        if (!TOGGLE.isIn(t)) return null
        return when {
            WIFI.isIn(t) -> Command.OpenSettings(SettingsPanel.Wifi)
            BLUETOOTH.isIn(t) -> Command.OpenSettings(SettingsPanel.Bluetooth)
            INTERNET.isIn(t) -> Command.OpenSettings(SettingsPanel.Internet)
            else -> null
        }
    }

    // ---- Search & apps ----

    private val SEARCH_FOR = Regex(
        "^${alt("search for", "search about", "search", "google", "look up", "find")} (.+?)" +
            "(?: ${alt("on google", "on the web", "on internet", "online")})?$"
    )
    private val FOR_SEARCH = Regex(
        "^(?:${alt("google", "गूगल", "internet", "इंटरनेट")} ${alt("pe", "par", "पे", "पर", "mein", "में")} )?(.+?) " +
            alt("search karo", "search kar do", "search karke batao", "google karo", "google kar do", "सर्च करो",
                "सर्च कर दो", "गूगल करो", "गूगल कर दो", "dhundo", "ढूंढो", "khojo", "खोजो") + "$"
    )

    private fun webSearch(t: String): Command? =
        (SEARCH_FOR.find(t) ?: FOR_SEARCH.find(t))?.groupValues?.get(1)?.let { Command.WebSearch(it) }

    private val OPEN_X = Regex("^${alt("open", "launch", "start", "run", "kholo", "खोलो", "ओपन")}(?: ${alt("the", "my")})? (.+)$")
    private val X_OPEN = Regex(
        "^(.+?) " + alt("kholo", "khol do", "kholna", "khol", "open karo", "open kar do", "open", "launch karo",
            "start karo", "chalu karo", "chalao", "खोलो", "खोल दो", "खोलना", "खोल", "ओपन करो", "ओपन कर दो", "ओपन",
            "लॉन्च करो", "स्टार्ट करो", "चालू करो", "चलाओ") + "$"
    )
    private val APP_WORDS = wordSet("app", "ऐप", "एप", "एप्प", "application", "the", "my", "mera", "meri", "मेरा", "मेरी")

    private fun openApp(t: String): Command? {
        val name = (OPEN_X.find(t) ?: X_OPEN.find(t))?.groupValues?.get(1) ?: return null
        return stripEdges(name, APP_WORDS).takeIf { it.isNotEmpty() }?.let { Command.OpenApp(it) }
    }

    // ---- Time, date & small talk ----

    private val TIME = Words(
        "time", "टाइम", "samay", "समय", "waqt", "वक्त", "kitne baje", "कितने बजे", "kya baja", "क्या बजा",
        "baj rahe", "बज रहे", "baje hain", "बजे हैं",
    )

    private fun time(t: String): Command? = if (TIME.isIn(t)) Command.Time else null

    private val DATE = Words(
        "date", "डेट", "तारीख", "tarikh", "tareekh", "दिनांक", "what day", "which day", "kaun sa din", "kya din",
        "कौन सा दिन", "क्या दिन", "day today", "aaj ka din", "आज का दिन",
    )

    private fun date(t: String): Command? = if (DATE.isIn(t)) Command.Date else null

    private val HELLO = Regex(
        "^${alt("hi", "hello", "hey", "hii", "namaste", "namaskar", "नमस्ते", "नमस्कार", "हेलो", "हैलो", "हाय", "हाई")}(?: there)?$"
    )
    private val HOW_ARE_YOU = Words(
        "how are you", "kaise ho", "kaisi ho", "kaise hain", "कैसे हो", "कैसी हो", "कैसे हैं", "kya haal", "क्या हाल",
        "what s up", "whats up",
    )
    private val WHO_ARE_YOU = Words(
        "who are you", "your name", "what are you", "tum kaun ho", "aap kaun ho", "aap kaun hain", "तुम कौन हो",
        "आप कौन हो", "आप कौन हैं", "tumhara naam", "aapka naam", "तुम्हारा नाम", "आपका नाम",
    )
    private val THANKS = Words(
        "thank you", "thanks", "thank", "shukriya", "dhanyavad", "dhanyawad", "शुक्रिया", "धन्यवाद", "थैंक यू", "थैंक्स",
    )
    private val HELP = Words(
        "what can you do", "help", "madad", "मदद", "kya kar sakte", "क्या कर सकते", "kya kya kar", "क्या क्या कर",
    )

    private fun smallTalk(t: String): Command? = when {
        HELLO.matches(t) -> Command.Hello
        HOW_ARE_YOU.isIn(t) -> Command.HowAreYou
        WHO_ARE_YOU.isIn(t) -> Command.WhoAreYou
        THANKS.isIn(t) -> Command.Thanks
        HELP.isIn(t) -> Command.Help
        else -> null
    }

    private val QUESTION = Words(
        "who", "what", "when", "where", "why", "how", "which", "kaun", "kya", "kab", "kahan", "kyun", "kyon", "kaise",
        "kitna", "kitne", "kitni", "कौन", "क्या", "कब", "कहां", "क्यों", "कैसे", "कितना", "कितने", "कितनी",
    )
}
