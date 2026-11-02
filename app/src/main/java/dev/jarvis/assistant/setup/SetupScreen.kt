package dev.jarvis.assistant.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.jarvis.assistant.JarvisApp
import dev.jarvis.assistant.util.appLabel
import dev.jarvis.assistant.voice.Lang
import dev.jarvis.assistant.voice.SpeechInput
import dev.jarvis.assistant.voice.SpeechLanguage
import dev.jarvis.assistant.voice.SpeechOutput

private data class Diagnostics(
    val speechEngine: String,
    val ttsEngine: String,
    val ttsEnginePackage: String,
    val hindiVoice: Boolean,
    val englishVoice: Boolean,
)

@Composable
fun SetupScreen(app: JarvisApp, onTalk: () -> Unit) {
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    var isAssistant by remember { mutableStateOf(isDefaultAssistant(context)) }
    var language by remember { mutableStateOf(app.settings.speechLanguage) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    var extrasGranted by remember { mutableStateOf(hasAll(context, OPTIONAL_PERMISSIONS)) }
    val extrasLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        extrasGranted = hasAll(context, OPTIONAL_PERMISSIONS)
    }

    // These can change in system Settings, so re-check whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        micGranted = hasMicPermission(context)
        isAssistant = isDefaultAssistant(context)
        extrasGranted = hasAll(context, OPTIONAL_PERMISSIONS)
        onPauseOrDispose {}
    }

    val diagnostics by produceState<Diagnostics?>(initialValue = null) {
        val tts = app.speechOutput
        val hindi = tts.hasVoice(Lang.Hindi)
        val english = tts.hasVoice(Lang.English)
        val pm = context.packageManager
        value = Diagnostics(
            speechEngine = SpeechInput.pickService(context)?.let { pm.appLabel(it.packageName) } ?: "System default",
            ttsEngine = pm.appLabel(tts.engine),
            ttsEnginePackage = tts.engine,
            hindiVoice = hindi,
            englishVoice = english,
        )
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Jarvis", style = MaterialTheme.typography.displaySmall)
            Text(
                "Your on-device voice assistant. Finish these steps once.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                title = "Microphone",
                body = "Jarvis only listens after you call it.",
                done = micGranted,
                actionLabel = "Allow",
                onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            StepCard(
                title = "Default digital assistant",
                body = "Choose Jarvis as the Digital assistant app. Then swipe up from a bottom corner " +
                    "(or long-press Home) to call it.",
                done = isAssistant,
                actionLabel = "Open settings",
                onAction = { openAssistantSettings(context) },
            )
            StepCard(
                title = "Calls, contacts & location",
                body = "Optional. For \"call Mummy\" and the weather where you are.",
                done = extrasGranted,
                actionLabel = "Allow",
                onAction = { extrasLauncher.launch(OPTIONAL_PERMISSIONS) },
            )
            LanguageCard(language) {
                language = it
                app.settings.speechLanguage = it
            }
            ExamplesCard()
            DiagnosticsCard(diagnostics, onInstallVoices = { installVoices(context, it) })

            Button(onClick = onTalk, enabled = micGranted, modifier = Modifier.fillMaxWidth()) {
                Text("Talk to Jarvis")
            }
            Text(
                "Tip: Settings → Advanced features → Side button → Double press → Open app → Ask Jarvis",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, done: Boolean, actionLabel: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = if (done) "Done" else "To do",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!done) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun LanguageCard(selected: SpeechLanguage, onSelect: (SpeechLanguage) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("I'll speak in", style = MaterialTheme.typography.titleMedium)
            SpeechLanguage.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Text(option.label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private val EXAMPLES = listOf(
    "सुबह 7 बजे का अलार्म लगा दो",
    "5 minute ka timer lagao",
    "आज मौसम कैसा है?",
    "Mummy ko call karo",
    "YouTube kholo",
    "Torch on karo",
    "Arijit ke gaane chalao",
    "अगला गाना",
    "India Gate ka rasta batao",
    "Battery kitni hai?",
)

@Composable
private fun ExamplesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Try saying", style = MaterialTheme.typography.titleMedium)
            EXAMPLES.forEach {
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: Diagnostics?, onInstallVoices: (enginePackage: String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Engines", style = MaterialTheme.typography.titleMedium)
            if (diagnostics == null) {
                Text("Checking…")
            } else {
                Text("Speech-to-text: ${diagnostics.speechEngine}")
                Text("Text-to-speech: ${diagnostics.ttsEngine}")
                Text("Hindi voice: ${if (diagnostics.hindiVoice) "ready" else "missing"}")
                Text("English (India) voice: ${if (diagnostics.englishVoice) "ready" else "missing"}")
                if (!diagnostics.hindiVoice || !diagnostics.englishVoice) {
                    TextButton(onClick = { onInstallVoices(diagnostics.ttsEnginePackage) }) { Text("Download voices") }
                }
            }
        }
    }
}

private val OPTIONAL_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasAll(context: Context, permissions: Array<String>) =
    permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun hasMicPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun isDefaultAssistant(context: Context) =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

/** Samsung and stock Android put the assistant picker in different places; try the most direct first. */
private fun openAssistantSettings(context: Context) {
    val actions = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in actions) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}

private fun installVoices(context: Context, enginePackage: String) {
    try {
        context.startActivity(SpeechOutput.installVoicesIntent(enginePackage))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    }
}
