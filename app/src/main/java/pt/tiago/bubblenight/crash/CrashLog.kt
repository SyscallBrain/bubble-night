package pt.tiago.bubblenight.crash

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the stack trace of every uncaught crash so it can be read without adb or logcat.
 *
 * The latest report always goes to the app's private storage, where the player can export it
 * from Settings. Debug builds also write a copy to Download/crashlogs/.
 * Install as early as possible: Application.onCreate().
 */
object CrashLog {
    private const val SUBDIR = "crashlogs"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failure while writing must never hide the original crash.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun hasReport(context: Context): Boolean = File(context.filesDir, "diagnostics/last-crash.txt").isFile
    fun latestReport(context: Context): ByteArray = File(context.filesDir, "diagnostics/last-crash.txt").readBytes()

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val name = "${context.packageName}-$stamp.txt"

        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val text = buildString {
            appendLine("app:     ${context.packageName} $versionName")
            appendLine("when:    ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread:  ${thread.name}")
            appendLine()
            append(trace)
        }.toByteArray()

        val privateReport = File(context.filesDir, "diagnostics/last-crash.txt")
        privateReport.parentFile!!.mkdirs()
        privateReport.writeBytes(text.copyOf(minOf(text.size, 256 * 1024)))
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore, no permission needed. The file lands in /storage/emulated/0/Download/crashlogs/.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text) }
        } else {
            // Android 8–9: app-specific external folder (Android/data/<pkg>/files/crashlogs/), no permission needed.
            val dir = File(context.getExternalFilesDir(null), SUBDIR).apply { mkdirs() }
            File(dir, name).writeBytes(text)
        }
    }
}
