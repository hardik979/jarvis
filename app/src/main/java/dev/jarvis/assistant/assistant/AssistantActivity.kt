package dev.jarvis.assistant.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jarvis.assistant.system.JarvisSession
import dev.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.launch

/**
 * The assistant overlay. Opened by the assist gesture (via [JarvisSession]), the side key
 * ("Ask Jarvis"), or the setup screen. It starts listening immediately.
 */
class AssistantActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels { AssistantViewModel.Factory }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startTurn() else viewModel.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AssistantScreen(
                    state = state,
                    onOrbTap = {
                        when (state.phase) {
                            Phase.Idle -> listen()
                            Phase.Listening -> viewModel.finishSpeaking()
                            Phase.Thinking, Phase.Speaking -> viewModel.stopTurn()
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is Effect.Launch -> startSafely(effect.intent)
                        Effect.Close -> finish()
                    }
                }
            }
        }
        if (savedInstanceState == null) listen()
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onLaunchFailed()
        } catch (e: SecurityException) {
            viewModel.onLaunchFailed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listen()
    }

    override fun onStop() {
        super.onStop()
        // Like Siri, the overlay goes away as soon as the user moves on.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (isFinishing) JarvisSession.active?.hide()
        super.onDestroy()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startTurn()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
