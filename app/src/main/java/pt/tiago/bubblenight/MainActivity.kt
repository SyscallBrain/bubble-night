package pt.tiago.bubblenight

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.bubblenight.game.GameViewModel
import pt.tiago.bubblenight.crash.CrashLog
import pt.tiago.bubblenight.ui.BubbleNightApp
import pt.tiago.bubblenight.ui.UiCallbacks
import pt.tiago.bubblenight.ui.UiScreen
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val model: GameViewModel by lazy { ViewModelProvider(this)[GameViewModel::class.java] }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }
    private val diagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = CrashLog.latestReport(this@MainActivity)
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error(getString(R.string.error_open_destination))
                    output.use { it.write(bytes) }
                }
                model.reportMessage(getString(R.string.diagnostics_saved))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { model.reportMessage(getString(R.string.diagnostics_failed, failure.message.orEmpty())) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Dark navigation bar on every API level (the theme attribute needs API 27).
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        val callbacks = object : UiCallbacks by model {
            override fun onExportBackup() { exportDocument.launch("bubble-night-progress.bubblenight.json") }
            override fun onImportBackup() { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            override fun onResumeFreeGame() { model.resumeFree() }
            override fun onShareResult() { shareResult() }
            override fun onExportDiagnostics() {
                if (CrashLog.hasReport(this@MainActivity)) diagnosticDocument.launch("bubble-night-diagnostics.txt")
                else model.reportMessage(getString(R.string.no_error_recorded))
            }
        }
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            BackHandler(enabled = state.screen != UiScreen.START) { model.onBack() }
            BubbleNightApp(state, callbacks)
        }
    }

    override fun onResume() { super.onResume(); model.onForeground() }
    override fun onPause() { model.onBackground(); super.onPause() }

    private fun shareResult() = lifecycleScope.launch {
        try {
            val snapshot = model.ui.value
            model.flushPendingSaves()
            val share = withContext(Dispatchers.IO) { ResultShare.intent(this@MainActivity, snapshot) }
            startActivity(android.content.Intent.createChooser(share, getString(R.string.action_share_result)))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { model.reportMessage(getString(R.string.share_failed, failure.message.orEmpty())) }
    }

    private fun exportTo(uri: Uri) = lifecycleScope.launch {
        try {
            val payload = model.exportPayload()
            withContext(Dispatchers.IO) {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error(getString(R.string.error_open_destination))
                output.use { it.write(payload); it.flush() }
            }
            model.reportMessage(getString(R.string.backup_saved))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { model.reportMessage(getString(R.string.export_failed, failure.message.orEmpty())) }
    }

    private fun importFrom(uri: Uri) = lifecycleScope.launch {
        try {
            val payload = withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: error(getString(R.string.error_open_file))
                input.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 2 * 1024 * 1024) { getString(R.string.backup_too_large) }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            model.importPayload(payload)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { model.reportMessage(getString(R.string.import_failed, failure.message.orEmpty())) }
    }
}
