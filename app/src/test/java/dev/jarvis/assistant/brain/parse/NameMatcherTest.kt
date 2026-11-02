package dev.jarvis.assistant.brain.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameMatcherTest {

    private val apps = listOf(
        "YouTube", "YouTube Music", "Spotify", "WhatsApp", "WhatsApp Business", "Instagram", "Camera", "Settings",
        "Phone", "Google Maps", "Calculator",
    )

    private fun app(spoken: String) = NameMatcher.best(listOf(spoken), apps) { it }

    @Test fun phoneticKeysMatchAcrossScripts() {
        assertEquals(Phonetic.key("YouTube"), Phonetic.key("यूट्यूब"))
        assertEquals(Phonetic.key("WhatsApp"), Phonetic.key("व्हाट्सएप"))
        assertEquals(Phonetic.key("Instagram"), Phonetic.key("इंस्टाग्राम"))
        assertEquals(Phonetic.key("Spotify"), Phonetic.key("स्पॉटिफाई"))
        assertEquals(Phonetic.key("Calculator"), Phonetic.key("कैलकुलेटर"))
    }

    @Test fun findsAppsSpokenInHindi() {
        assertEquals("YouTube", app("यूट्यूब"))
        assertEquals("WhatsApp", app("व्हाट्सएप"))
        assertEquals("Spotify", app("स्पॉटिफाई"))
        assertEquals("Camera", app("कैमरा"))
        assertEquals("Settings", app("सेटिंग्स"))
        assertEquals("Phone", app("फोन"))
        assertEquals("Google Maps", app("गूगल मैप्स"))
    }

    @Test fun findsAppsSpokenInEnglish() {
        assertEquals("Instagram", app("instagram"))
        assertEquals("YouTube Music", app("youtube music"))
        assertEquals("Google Maps", app("maps"))
    }

    @Test fun unknownAppIsNull() {
        assertNull(app("netflix"))
    }

    @Test fun findsContactsByRelationAndFirstName() {
        val contacts = listOf("Mummy", "Rahul Sharma", "Papa", "Priya")
        fun contact(spoken: String) = NameMatcher.best(NameMatcher.relationVariants(spoken), contacts) { it }
        assertEquals("Mummy", contact("mom"))
        assertEquals("Mummy", contact("मम्मी"))
        assertEquals("Papa", contact("dad"))
        assertEquals("Rahul Sharma", contact("राहुल"))
        assertEquals("Priya", contact("प्रिया"))
    }
}
