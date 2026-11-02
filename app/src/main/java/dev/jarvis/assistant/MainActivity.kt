package dev.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jarvis.assistant.assistant.AssistantActivity
import dev.jarvis.assistant.setup.SetupScreen
import dev.jarvis.assistant.ui.theme.JarvisTheme

/** Launcher screen: one-time setup and engine diagnostics. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as JarvisApp
        setContent {
            JarvisTheme {
                SetupScreen(
                    app = app,
                    onTalk = { startActivity(Intent(this, AssistantActivity::class.java)) },
                )
            }
        }
    }
}
