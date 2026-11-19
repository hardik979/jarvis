package dev.jarvis.assistant.brain.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class CommandParserTest {

    private val tenAm = LocalTime.of(10, 0)

    private fun parse(text: String) = CommandParser.parse(text, tenAm)

    @Test fun time() {
        assertEquals(Command.Time, parse("What time is it?"))
        assertEquals(Command.Time, parse("टाइम क्या हुआ है"))
        assertEquals(Command.Time, parse("abhi kitne baje hain"))
    }

    @Test fun dateAndBattery() {
        assertEquals(Command.Date, parse("आज क्या तारीख है"))
        assertEquals(Command.Battery, parse("battery kitni hai"))
        assertEquals(Command.Battery, parse("बैटरी कितनी है"))
    }

    @Test fun torch() {
        assertEquals(Command.Torch(on = true), parse("torch on karo"))
        assertEquals(Command.Torch(on = false), parse("टॉर्च बंद करो"))
        assertEquals(Command.Torch(on = false), parse("Turn off the flashlight"))
    }

    @Test fun volume() {
        assertEquals(Command.Volume(VolumeChange.Up), parse("volume badhao"))
        assertEquals(Command.Volume(VolumeChange.Down), parse("आवाज़ कम करो"))
        assertEquals(Command.Volume(VolumeChange.Percent(50)), parse("Set volume to 50%"))
        assertEquals(Command.Volume(VolumeChange.Mute), parse("mute"))
    }

    @Test fun media() {
        assertEquals(Command.Media(MediaAction.Next), parse("next song"))
        assertEquals(Command.Media(MediaAction.Next), parse("अगला गाना"))
        assertEquals(Command.Media(MediaAction.Pause), parse("gaana band karo"))
        assertEquals(Command.Media(MediaAction.Pause), parse("pause"))
        assertEquals(Command.Media(MediaAction.Play), parse("koi gaana chalao"))
        assertEquals(Command.Media(MediaAction.Play), parse("play"))
    }

    @Test fun alarms() {
        assertEquals(Command.Alarm(ClockTime(7, 0)), parse("Set an alarm for 7 AM"))
        assertEquals(Command.Alarm(ClockTime(6, 30)), parse("subah 6:30 baje ka alarm laga do"))
        assertEquals(Command.Alarm(ClockTime(6, 30)), parse("कल सुबह साढ़े 6 बजे का अलार्म लगा दो"))
        assertEquals(Command.Alarm(ClockTime(19, 0)), parse("शाम 7 बजे का अलार्म लगाओ"))
        assertEquals(Command.Alarm(ClockTime(17, 45)), parse("Wake me up at 5:45 p.m."))
        assertEquals(Command.Alarm(ClockTime(10, 30)), parse("alarm in 30 minutes"))
        assertEquals(Command.Alarm(ClockTime(23, 0)), parse("रात 11 बजे का अलार्म लगा दो"))
    }

    @Test fun alarmWithoutAmPmPicksNextOccurrence() {
        // At 10 AM, "7" means 7 PM; "11" means 11 AM.
        assertEquals(Command.Alarm(ClockTime(19, 0)), parse("saat baje ka alarm lagao"))
        assertEquals(Command.Alarm(ClockTime(11, 0)), parse("11 बजे का अलार्म"))
        assertEquals(Command.Alarm(null), parse("alarm laga do"))
    }

    @Test fun timers() {
        assertEquals(Command.Timer(300), parse("Set a timer for 5 minutes"))
        assertEquals(Command.Timer(300), parse("5 मिनट का टाइमर लगा दो"))
        assertEquals(Command.Timer(5400), parse("dedh ghante ka timer"))
        assertEquals(Command.Timer(5400), parse("1 hour 30 minutes timer"))
        assertEquals(Command.Timer(1800), parse("aadha ghanta timer lagao"))
        assertEquals(Command.Timer(30), parse("टाइमर 30 सेकंड"))
    }

    @Test fun openApps() {
        assertEquals(Command.OpenApp("youtube"), parse("Open YouTube"))
        assertEquals(Command.OpenApp("व्हाट्सएप"), parse("व्हाट्सएप खोलो"))
        assertEquals(Command.OpenApp("instagram"), parse("instagram kholo"))
        assertEquals(Command.OpenApp("camera"), parse("camera open karo"))
        assertEquals(Command.OpenApp("youtube"), parse("youtube chalao"))
        // Said on the S23 during testing: fillers must not end up in a search query.
        assertEquals(Command.OpenApp("youtube"), parse("एक काम कर दो यूट्यूब चला दो थोड़ा"))
    }

    @Test fun calls() {
        assertEquals(Command.Call("mom"), parse("Call mom"))
        assertEquals(Command.Call("mummy"), parse("mummy ko call karo"))
        assertEquals(Command.Call("राहुल"), parse("राहुल को फोन लगाओ"))
        assertEquals(Command.Call("पापा"), parse("कॉल करो पापा को"))
        assertEquals(Command.Call("9876543210"), parse("call 9876543210"))
    }

    @Test fun navigation() {
        assertEquals(Command.Navigate("india gate"), parse("Navigate to India Gate"))
        assertEquals(Command.Navigate("इंडिया गेट"), parse("इंडिया गेट का रास्ता बताओ"))
        assertEquals(Command.Navigate("india gate"), parse("mujhe india gate jana hai"))
    }

    @Test fun weather() {
        assertEquals(Command.Weather(null, tomorrow = false), parse("weather"))
        assertEquals(Command.Weather(null, tomorrow = false), parse("आज मौसम कैसा है"))
        assertEquals(Command.Weather("delhi", tomorrow = true), parse("kal delhi ka mausam kaisa hoga"))
        assertEquals(Command.Weather("mumbai", tomorrow = true), parse("What's the weather in Mumbai tomorrow?"))
        assertEquals(Command.Weather("दिल्ली", tomorrow = true), parse("कल दिल्ली में बारिश होगी क्या"))
    }

    @Test fun youTubeAndMusicSearch() {
        assertEquals(Command.YouTube("arijit singh songs"), parse("Play Arijit Singh songs on YouTube"))
        assertEquals(Command.YouTube("अरिजीत सिंह के गाने"), parse("यूट्यूब पे अरिजीत सिंह के गाने चलाओ"))
        assertEquals(Command.YouTube("arijit ke gaane"), parse("arijit ke gaane chalao"))
        assertEquals(Command.YouTube("despacito"), parse("play despacito"))
    }

    @Test fun searchAndSettings() {
        assertEquals(Command.WebSearch("best biryani near me"), parse("search for best biryani near me"))
        assertEquals(Command.WebSearch("ipl score"), parse("ipl score google karo"))
        assertEquals(Command.OpenSettings(SettingsPanel.Wifi), parse("wifi on karo"))
        assertEquals(Command.OpenSettings(SettingsPanel.Bluetooth), parse("ब्लूटूथ चालू करो"))
    }

    @Test fun smallTalkAndWakeWord() {
        assertEquals(Command.Hello, parse("Hello"))
        assertEquals(Command.Hello, parse("नमस्ते"))
        assertEquals(Command.WhoAreYou, parse("तुम कौन हो"))
        assertEquals(Command.Thanks, parse("thank you"))
        assertEquals(Command.Attention, parse("Jarvis"))
        assertEquals(Command.Time, parse("Hey Jarvis, what time is it"))
    }

    @Test fun unknownQuestionsAreMarked() {
        val result = parse("who is the prime minister of India")
        assertTrue(result is Command.Unknown && result.isQuestion)
    }
}
