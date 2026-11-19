package dev.jarvis.assistant.brain.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTakingTest {

    @Test fun completeCommandsDoNotWait() {
        assertFalse(TurnTaking.wantsMore("torch on karo"))
        assertFalse(TurnTaking.wantsMore("volume up"))
        assertFalse(TurnTaking.wantsMore("सुबह 7 बजे का अलार्म लगा दो"))
        assertFalse(TurnTaking.wantsMore("mummy ko call karo"))
        assertFalse(TurnTaking.wantsMore("what time is it"))
        assertFalse(TurnTaking.wantsMore("who is the prime minister of india"))
    }

    @Test fun unfinishedSentencesWait() {
        assertTrue(TurnTaking.wantsMore("mummy ko"))
        assertTrue(TurnTaking.wantsMore("राहुल को"))
        assertTrue(TurnTaking.wantsMore("set an alarm for"))
        assertTrue(TurnTaking.wantsMore("navigate to"))
        assertTrue(TurnTaking.wantsMore("call mom and"))
        assertTrue(TurnTaking.wantsMore("youtube par"))
    }

    @Test fun missingDetailsWait() {
        assertTrue(TurnTaking.wantsMore("alarm laga do"))
        assertTrue(TurnTaking.wantsMore("टाइमर लगाओ"))
        assertTrue(TurnTaking.wantsMore("rahul ko message karo"))
    }
}
