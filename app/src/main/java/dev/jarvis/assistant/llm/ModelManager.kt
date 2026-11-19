package dev.jarvis.assistant.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.io.File

/** The on-device model file: where it lives and downloading it once. */
class ModelManager(private val context: Context) {

    class Model(val name: String, val fileName: String, val url: String, val sizeBytes: Long)

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data object Failed : Status
    }

    // Best balance on the S23 in tools/llm-bench: 8/12 commands with a loose prompt, ~16 tok/s, ~2 GB RAM.
    val model = Model(
        name = "Qwen3.5 2B",
        fileName = "Qwen3.5-2B-Q4_0.gguf",
        url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_0.gguf",
        sizeBytes = 1_214_873_856L,
    )

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    val file: File get() = File(context.getExternalFilesDir("models"), model.fileName)

    val isReady: Boolean get() = file.length() == model.sizeBytes

    /** Downloads through Android's DownloadManager, which survives dropped connections and app restarts. */
    fun startDownload() {
        file.delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Jarvis brain (${model.name})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", model.fileName)
        prefs.edit { putLong(KEY_DOWNLOAD_ID, downloads.enqueue(request)) }
    }

    fun status(): Status {
        if (isReady) return Status.Ready
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1)
        if (id == -1L) return Status.Missing
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return Status.Missing
            val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return when (state) {
                DownloadManager.STATUS_FAILED -> Status.Failed
                DownloadManager.STATUS_SUCCESSFUL -> if (isReady) Status.Ready else Status.Failed
                else -> Status.Downloading(done.toFloat() / model.sizeBytes)
            }
        }
        return Status.Missing
    }

    private companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
    }
}
