package dev.jarvis.assistant.brain.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import dev.jarvis.assistant.brain.FollowUp
import dev.jarvis.assistant.brain.Reply
import dev.jarvis.assistant.brain.parse.ClockTime
import dev.jarvis.assistant.brain.parse.Command
import dev.jarvis.assistant.brain.parse.MediaAction
import dev.jarvis.assistant.brain.parse.SettingsPanel
import dev.jarvis.assistant.brain.parse.VolumeChange
import dev.jarvis.assistant.voice.Lang
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Carries out a [Command] with Android APIs and words the reply in the user's language. */
class CommandExecutor(private val context: Context) {

    private val apps = AppFinder(context)
    private val contacts = ContactFinder(context)
    private val weather = WeatherService(context)

    suspend fun execute(command: Command, lang: Lang): Reply {
        val say = Say(lang)
        return when (command) {
            Command.Time -> time(say)
            Command.Date -> date(lang, say)
            Command.Battery -> battery(say)
            is Command.Torch -> torch(command.on, say)
            is Command.Volume -> volume(command.change, say)
            is Command.Media -> media(command.action, say)
            is Command.Alarm -> alarm(command.time, say)
            is Command.Timer -> timer(command.seconds, say)
            is Command.OpenApp -> openApp(command.name, say)
            is Command.Call -> call(command.who, say)
            is Command.Message -> message(command.to, command.text, say)
            is Command.Navigate -> navigate(command.place, say)
            is Command.Weather -> weather(command, lang, say)
            is Command.WebSearch -> webSearch(command.query, say("Searching the web.", "वेब पर खोज रहे हैं।"))
            is Command.YouTube -> youTube(command.query, say)
            is Command.OpenSettings -> settings(command.panel, say)
            Command.Hello -> Reply(say("Hi! How can I help?", "नमस्ते! बताइए, क्या करना है?"), followUp = FollowUp())
            Command.HowAreYou -> Reply(
                say("All systems running. What can I do for you?", "सब बढ़िया! बताइए, क्या मदद करूं?"),
                followUp = FollowUp(),
            )
            Command.WhoAreYou -> Reply(
                say("I'm Jarvis, your assistant. I run right here on your phone.", "मैं Jarvis हूं, आपका असिस्टेंट, जो आपके फोन पर ही चलता है।")
            )
            Command.Thanks -> Reply(say("Anytime!", "कोई बात नहीं!"))
            Command.Attention -> Reply(say("Yes?", "जी, बताइए?"), followUp = FollowUp())
            Command.Help -> Reply(
                say(
                    "I can set alarms and timers, call your contacts, open apps, play music, control volume and " +
                        "the flashlight, check the weather, time and battery, give directions and search the web.",
                    "अलार्म और टाइमर, कॉल, ऐप खोलना, गाने, आवाज और टॉर्च, मौसम, समय और बैटरी, रास्ता और वेब सर्च, " +
                        "ये सब बस बोलकर करवाइए।",
                ),
                followUp = FollowUp(),
            )
            is Command.Unknown ->
                if (command.isQuestion) webSearch(command.text, say("Here's what I found on the web.", "वेब पर यह मिला।"))
                else Reply(say("Sorry, I can't do that yet.", "माफ कीजिए, यह अभी मुझसे नहीं हो पाएगा।"))
        }
    }

    private fun time(say: Say): Reply {
        val now = LocalTime.now().let { ClockTime(it.hour, it.minute) }
        return Reply(say("It's ${now.english()}.", "अभी ${now.hindi()} बजे हैं।"))
    }

    private fun date(lang: Lang, say: Say): Reply {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", lang.locale))
        return Reply(say("Today is $today.", "आज $today है।"))
    }

    private fun battery(say: Say): Reply {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Reply(
            if (battery.isCharging) say("Battery is at $percent% and charging.", "बैटरी $percent प्रतिशत है और चार्ज हो रही है।")
            else say("Battery is at $percent%.", "बैटरी $percent प्रतिशत है।")
        )
    }

    private fun torch(on: Boolean, say: Say): Reply {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Reply(say("This phone doesn't have a flashlight.", "इस फोन में टॉर्च नहीं है।"))
        return try {
            camera.setTorchMode(id, on)
            Reply(if (on) say("Flashlight on.", "टॉर्च चालू कर दी।") else say("Flashlight off.", "टॉर्च बंद कर दी।"))
        } catch (e: CameraAccessException) {
            Reply(say("The camera is busy, so the flashlight can't turn on.", "कैमरा अभी इस्तेमाल में है, इसलिए टॉर्च नहीं जल सकती।"))
        }
    }

    private fun volume(change: VolumeChange, say: Say): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val step = (max / 5).coerceAtLeast(1)
        val current = audio.getStreamVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI
        when (change) {
            VolumeChange.Up -> audio.setStreamVolume(stream, (current + step).coerceAtMost(max), flags)
            VolumeChange.Down -> audio.setStreamVolume(stream, (current - step).coerceAtLeast(0), flags)
            VolumeChange.Mute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            VolumeChange.Unmute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            VolumeChange.Max -> audio.setStreamVolume(stream, max, flags)
            is VolumeChange.Percent -> audio.setStreamVolume(stream, max * change.value / 100, flags)
        }
        if (change == VolumeChange.Mute) return Reply(say("Muted.", "आवाज बंद कर दी।"), speak = false)
        val percent = audio.getStreamVolume(stream) * 100 / max
        return Reply(say("Volume $percent%.", "आवाज $percent प्रतिशत।"))
    }

    private fun media(action: MediaAction, say: Say): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
        val key = when (action) {
            MediaAction.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        val text = when (action) {
            MediaAction.Play -> say("Playing.", "चला दिया।")
            MediaAction.Pause -> say("Paused.", "रोक दिया।")
            MediaAction.Next -> say("Next track.", "अगला गाना।")
            MediaAction.Previous -> say("Previous track.", "पिछला गाना।")
        }
        // Talking would interrupt the music the user just asked for.
        return Reply(text, speak = false)
    }

    private fun alarm(time: ClockTime?, say: Say): Reply {
        time ?: return Reply(say("For what time?", "किस समय का?"), followUp = FollowUp("alarm"))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return Reply(say("Alarm set for ${time.english()}.", "${time.hindi()} बजे का अलार्म लगा दिया।"), launch = intent)
    }

    private fun timer(seconds: Int?, say: Say): Reply {
        seconds ?: return Reply(say("For how long?", "कितनी देर का?"), followUp = FollowUp("timer"))
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtMost(24 * 60 * 60))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return Reply(
            say("Timer set for ${englishDuration(seconds)}.", "${hindiDuration(seconds)} का टाइमर शुरू कर दिया।"),
            launch = intent,
        )
    }

    private suspend fun openApp(name: String, say: Say): Reply {
        val app = apps.find(name) ?: return Reply(say("I couldn't find $name on your phone.", "फोन में $name नहीं मिला।"))
        return Reply(say("Opening ${app.label}.", "${app.label} खुल रहा है।"), launch = app.launch, speak = false)
    }

    private suspend fun call(who: String, say: Say): Reply {
        val digits = who.mapNotNull { it.digitToIntOrNull() }.joinToString("")
        val (label, number) = if (digits.length >= 3) {
            digits to digits
        } else {
            if (!contacts.canRead()) {
                return Reply(say("Allow Contacts in the Jarvis app first.", "पहले Jarvis ऐप में Contacts की अनुमति दें।"))
            }
            val contact = contacts.find(who)
                ?: return Reply(say("I couldn't find $who in your contacts.", "कॉन्टैक्ट्स में $who नहीं मिला।"))
            contact.name to contact.number
        }
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        // Without the call permission, open the dialler with the number filled in instead.
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return Reply(say("Calling $label.", "$label को कॉल कर रहे हैं।"), launch = intent, speak = false)
    }

    // Opens the SMS app with the text filled in; the user taps send (no SMS permission needed).
    private suspend fun message(to: String, text: String, say: Say): Reply {
        if (!contacts.canRead()) {
            return Reply(say("Allow Contacts in the Jarvis app first.", "पहले Jarvis ऐप में Contacts की अनुमति दें।"))
        }
        val contact = contacts.find(to)
            ?: return Reply(say("I couldn't find $to in your contacts.", "कॉन्टैक्ट्स में $to नहीं मिला।"))
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null)).putExtra("sms_body", text)
        return Reply(say("Message to ${contact.name} is ready.", "${contact.name} के लिए मैसेज तैयार है।"), launch = intent, speak = false)
    }

    private fun navigate(place: String, say: Say) = Reply(
        say("Getting directions to $place.", "$place का रास्ता दिखा रहे हैं।"),
        launch = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))),
        speak = false,
    )

    private suspend fun weather(command: Command.Weather, lang: Lang, say: Say): Reply = try {
        val report = weather.report(command.place, lang, command.tomorrow)
        val sky = WeatherService.describe(report.code, lang)
        val place = report.place
        Reply(
            if (command.tomorrow) {
                say(
                    "Tomorrow${place?.let { " in $it" }.orEmpty()}: $sky, high ${report.high}°, low ${report.low}°, " +
                        "${report.rainChance}% chance of rain.",
                    "कल${place?.let { " $it" }.orEmpty()} का मौसम: $sky, अधिकतम ${report.high} और न्यूनतम ${report.low} डिग्री, " +
                        "बारिश की संभावना ${report.rainChance} प्रतिशत।",
                )
            } else {
                say(
                    "It's ${report.now}°${place?.let { " in $it" }.orEmpty()} and $sky. Today: high ${report.high}°, " +
                        "low ${report.low}°, ${report.rainChance}% chance of rain.",
                    "${place?.let { "$it में " }.orEmpty()}अभी ${report.now} डिग्री है, $sky। आज अधिकतम ${report.high} और " +
                        "न्यूनतम ${report.low} डिग्री, बारिश की संभावना ${report.rainChance} प्रतिशत।",
                )
            }
        )
    } catch (e: WeatherService.Failure) {
        when (e.problem) {
            // Whatever city they say next answers the question.
            WeatherService.Problem.NoLocation -> Reply(
                say("I don't have your location. Which city?", "आपकी लोकेशन नहीं मिली। कौन सा शहर?"),
                followUp = FollowUp(if (command.tomorrow) "weather tomorrow in" else "weather in"),
            )
            WeatherService.Problem.UnknownPlace -> Reply(say("I couldn't find ${command.place}.", "${command.place} नहीं मिला।"))
            WeatherService.Problem.Offline -> Reply(
                say("I can't reach the weather service. Check your internet.", "मौसम की जानकारी नहीं मिल पाई। इंटरनेट चेक करें।")
            )
        }
    }

    private fun webSearch(query: String, text: String) = Reply(
        text,
        launch = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))),
        speak = false,
    )

    private fun youTube(query: String, say: Say) = Reply(
        say("Searching YouTube for $query.", "YouTube पर $query खोज रहे हैं।"),
        launch = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))),
        speak = false,
    )

    // Android doesn't let apps flip Wi-Fi or Bluetooth directly, so open the quick panel instead.
    private fun settings(panel: SettingsPanel, say: Say): Reply {
        val (action, text) = when (panel) {
            SettingsPanel.Wifi -> Settings.Panel.ACTION_WIFI to say("Here's Wi-Fi.", "वाई-फाई सेटिंग।")
            SettingsPanel.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS to say("Here's Bluetooth.", "ब्लूटूथ सेटिंग।")
            SettingsPanel.Internet -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to say("Here's mobile data.", "इंटरनेट सेटिंग।")
        }
        return Reply(text, launch = Intent(action), speak = false)
    }
}
