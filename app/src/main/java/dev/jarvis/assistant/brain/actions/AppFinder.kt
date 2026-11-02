package dev.jarvis.assistant.brain.actions

import android.content.Context
import android.content.Intent
import dev.jarvis.assistant.brain.parse.NameMatcher
import dev.jarvis.assistant.brain.parse.normalize
import dev.jarvis.assistant.util.launcherActivities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the installed app the user named, in any script. */
class AppFinder(private val context: Context) {

    class App(val label: String, val launch: Intent)

    suspend fun find(spoken: String): App? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.launcherActivities()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                App(
                    label = info.loadLabel(pm).toString(),
                    launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
        val variants = listOfNotNull(spoken, HINDI_NAMES[normalize(spoken)])
        NameMatcher.best(variants, apps) { it.label }
    }

    private companion object {
        // Hindi words for built-in apps whose labels are English.
        val HINDI_NAMES = mapOf(
            "घड़ी" to "clock", "ghadi" to "clock", "फोटो" to "gallery", "photo" to "gallery", "तस्वीरें" to "gallery",
            "मैसेज" to "messages", "संदेश" to "messages", "संपर्क" to "contacts", "कैलेंडर" to "calendar",
        ).mapKeys { normalize(it.key) }
    }
}
