package dev.jarvis.assistant.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.jarvis.assistant.assistant.AssistantActivity

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

/** One assist invocation. Its only job is to open [AssistantActivity], which holds the real UI. */
class JarvisSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        // The UI lives in a Compose activity, so the session's own window stays out of the way.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        active = this
        startAssistantActivity(Intent(context, AssistantActivity::class.java))
    }

    override fun onHide() {
        if (active === this) active = null
        super.onHide()
    }

    companion object {
        /** The session on screen right now, so [AssistantActivity] can close it when it finishes. */
        var active: JarvisSession? = null
            private set
    }
}
