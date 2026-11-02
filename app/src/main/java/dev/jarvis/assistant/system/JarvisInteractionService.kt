package dev.jarvis.assistant.system

import android.service.voice.VoiceInteractionService

/**
 * Android keeps this bound while Jarvis is the default digital assistant, which also keeps our
 * process warm. The assist gesture goes through [JarvisSessionService].
 */
class JarvisInteractionService : VoiceInteractionService()
