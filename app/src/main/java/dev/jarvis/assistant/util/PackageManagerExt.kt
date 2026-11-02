package dev.jarvis.assistant.util

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

fun PackageManager.servicesFor(action: String): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) {
        queryIntentServices(Intent(action), PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(Intent(action), 0)
    }

/** Every app icon in the launcher. */
fun PackageManager.launcherActivities(): List<ResolveInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return if (Build.VERSION.SDK_INT >= 33) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }
}

/** Human-readable app name, falling back to the package name. */
fun PackageManager.appLabel(packageName: String): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
    getApplicationLabel(info).toString()
}.getOrDefault(packageName)
