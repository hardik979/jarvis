package dev.jarvis.assistant.llm

import android.os.SystemClock
import android.util.Log
import dev.jarvis.assistant.brain.parse.ClockTime
import dev.jarvis.assistant.brain.parse.Command
import dev.jarvis.assistant.brain.parse.MediaAction
import dev.jarvis.assistant.brain.parse.VolumeChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** What the LLM decided to do with a request the rules didn't understand. */
sealed interface LlmDecision {
    data class Act(val command: Command) : LlmDecision
    data class Chat(val reply: String) : LlmDecision
}

/**
 * The on-device LLM as Jarvis uses it: picks an action or chats back, in strict compact JSON
 * (prompt v2 from tools/llm-bench). Remembers the last couple of exchanges for follow-ups.
 */
class LocalLlm(private val models: ModelManager) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: LlamaEngine? = null
    private var unloadJob: Job? = null
    private val history = ArrayDeque<Pair<String, String>>()

    val isAvailable: Boolean get() = models.isReady

    /** Timings of the last [decide], shown under the reply so slowness can be diagnosed without logs. */
    var lastStats: String = ""
        private set

    private var loadMillis: Long? = null

    /** Loads the model and caches the system prompt; called while the user is still talking. */
    suspend fun warmUp() {
        if (!isAvailable) return
        mutex.withLock { engine()?.generate(PREFIX, "", null, 0) }
    }

    fun newConversation() {
        history.clear()
    }

    suspend fun decide(input: String): LlmDecision? {
        val requested = SystemClock.elapsedRealtime()
        return mutex.withLock { decideLocked(input, waitedMillis = SystemClock.elapsedRealtime() - requested) }
    }

    private suspend fun decideLocked(input: String, waitedMillis: Long): LlmDecision? {
        val engine = engine() ?: return null
        val rest = buildString {
            history.forEach { (user, assistant) -> append(turn("user", user)).append(turn("assistant", assistant)) }
            append(turn("user", input))
            append(ASSISTANT_START)
        }
        val started = SystemClock.elapsedRealtime()
        val json = engine.generate(PREFIX, rest, GRAMMAR, MAX_TOKENS)
        val total = (SystemClock.elapsedRealtime() - started) / 1000.0
        lastStats = buildString {
            if (waitedMillis > 100) append("waited for warm-up %.1fs · ".format(waitedMillis / 1000.0))
            loadMillis?.let { append("loaded model in %.1fs · ".format(it / 1000.0)) }
            append(engine.lastStats())
            append(" · total %.1fs".format(total))
        }
        loadMillis = null
        Log.i(TAG, "\"$input\" -> $json  [$lastStats]")
        if (looksBroken(json)) {
            // Never show noise: drop the model so the next request starts from a clean load.
            Log.w(TAG, "discarding broken output and unloading the model")
            engine.close()
            this.engine = null
            history.clear()
            return null
        }
        history.addLast(input to json)
        while (history.size > HISTORY_TURNS) history.removeFirst()
        return parse(json)
    }

    private suspend fun engine(): LlamaEngine? {
        scheduleUnload()
        engine?.let { return it }
        if (!isAvailable) return null
        val started = SystemClock.elapsedRealtime()
        return LlamaEngine.load(models.file.absolutePath).also {
            engine = it
            loadMillis = SystemClock.elapsedRealtime() - started
        }
    }

    // ~2 GB of RAM is a lot to hold on a phone; give it back when Jarvis hasn't been used for a while.
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    /** Users speak Hindi and English; Cyrillic, CJK or Hangul in a reply means the model's state is broken. */
    private fun looksBroken(text: String) = text.any {
        it in 'Ѐ'..'ӿ' || it in '぀'..'ヿ' || it in '一'..'鿿' || it in '가'..'힯'
    }

    private fun parse(json: String): LlmDecision? = runCatching {
        val o = JSONObject(json)
        val command: Command = when (o.getString("action")) {
            "chat" -> return@runCatching LlmDecision.Chat(o.getString("reply"))
            "alarm" -> {
                val (hour, minute) = o.getString("time").split(":").map { it.toInt() }
                if (hour !in 0..23 || minute !in 0..59) return@runCatching null
                Command.Alarm(ClockTime(hour, minute))
            }
            "timer" -> Command.Timer(o.getInt("seconds"))
            "call" -> Command.Call(o.getString("who"))
            "message" -> Command.Message(o.getString("to"), o.getString("text"))
            "open_app" -> Command.OpenApp(o.getString("name"))
            "navigate" -> Command.Navigate(o.getString("place"))
            "weather" -> Command.Weather(
                place = if (o.isNull("place")) null else o.getString("place").ifBlank { null },
                tomorrow = o.getString("day") == "tomorrow",
            )
            "torch" -> Command.Torch(o.getBoolean("on"))
            "volume" -> Command.Volume(
                when (o.getString("change")) {
                    "up" -> VolumeChange.Up
                    "down" -> VolumeChange.Down
                    else -> VolumeChange.Mute
                }
            )
            "media" -> Command.Media(MediaAction.entries.first { it.name.equals(o.getString("control"), ignoreCase = true) })
            "web_search" -> Command.WebSearch(o.getString("query"))
            else -> return@runCatching null
        }
        LlmDecision.Act(command)
    }.getOrNull()

    private companion object {
        const val TAG = "Jarvis/LLM"
        const val MAX_TOKENS = 160
        const val HISTORY_TURNS = 2
        const val IDLE_UNLOAD_MS = 5 * 60 * 1000L

        fun turn(role: String, content: String) = "<|im_start|>$role\n$content<|im_end|>\n"

        // Qwen's chat format; the empty think block switches off its slow "thinking out loud" mode.
        const val ASSISTANT_START = "<|im_start|>assistant\n<think>\n\n</think>\n\n"

        // Same as tools/llm-bench/prompt_v2.py. Keep it byte-identical between calls: it's cached.
        val PREFIX = turn(
            "system",
            """
            You are Jarvis, a voice assistant on the user's phone. Answer with one JSON object and nothing else.
            Actions: alarm(time HH:MM 24h), timer(seconds), call(who), message(to,text), open_app(name), navigate(place),
            weather(place or null, day today|tomorrow), torch(on), volume(change up|down|mute), media(control play|pause|next|previous),
            web_search(query), chat(reply).
            Use timer when they want a reminder or wake-up after some time. Use chat for questions, feelings and small talk.
            If the user spoke Hindi or Hinglish, write reply in Hindi (Devanagari). Keep replies to one or two short sentences.
            Examples:
            bhai 7:30 ka alarm laga de -> {"action":"alarm","time":"07:30"}
            das minute ka timer -> {"action":"timer","seconds":600}
            Priya ko bolo main aa raha hoon -> {"action":"message","to":"Priya","text":"main aa raha hoon"}
            torch band karo -> {"action":"torch","on":false}
            awaaz badhao -> {"action":"volume","change":"up"}
            agla gaana -> {"action":"media","control":"next"}
            Mumbai ka mausam kal -> {"action":"weather","place":"Mumbai","day":"tomorrow"}
            WhatsApp kholo -> {"action":"open_app","name":"WhatsApp"}
            what is photosynthesis -> {"action":"chat","reply":"It's how plants turn sunlight, water and air into food."}
            मुझे बोर हो रहा है -> {"action":"chat","reply":"चलो, कोई अच्छा गाना सुनते हैं या एक मज़ेदार किस्सा सुनाऊं?"}
            """.trimIndent(),
        )

        // Forces one flat JSON object per action with exact fields and no whitespace (each token costs ~60 ms).
        // Strings are unbounded on purpose: a bounded repeat like {1,240} expands into 240 nested rules and
        // makes every grammar check slow; MAX_TOKENS caps the length anyway.
        val GRAMMAR = """
            root ::= "{\"action\":" ( alarm | timer | call | message | openapp | navigate | weather | torch | volume | media | websearch | chat ) "}"
            alarm ::= "\"alarm\",\"time\":\"" [0-2] [0-9] ":" [0-5] [0-9] "\""
            timer ::= "\"timer\",\"seconds\":" [1-9] [0-9]{0,5}
            call ::= "\"call\",\"who\":" str
            message ::= "\"message\",\"to\":" str ",\"text\":" str
            openapp ::= "\"open_app\",\"name\":" str
            navigate ::= "\"navigate\",\"place\":" str
            weather ::= "\"weather\",\"place\":" ( str | "null" ) ",\"day\":" ( "\"today\"" | "\"tomorrow\"" )
            torch ::= "\"torch\",\"on\":" ( "true" | "false" )
            volume ::= "\"volume\",\"change\":" ( "\"up\"" | "\"down\"" | "\"mute\"" )
            media ::= "\"media\",\"control\":" ( "\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"" )
            websearch ::= "\"web_search\",\"query\":" str
            chat ::= "\"chat\",\"reply\":" str
            str ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] )* "\""
        """.trimIndent()
    }
}
