package pt.tiago.bubblenight

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import pt.tiago.bubblenight.ui.UiModel
import java.io.File
import java.text.NumberFormat

/** Builds a local result card; Android's chooser sends it only on the player's explicit action. */
internal object ResultShare {
    fun intent(context: Context, model: UiModel): Intent {
        val result = requireNotNull(model.result)
        require(result.showScore)
        val resources = context.resources
        val points = NumberFormat.getIntegerInstance().format(result.score)
        val label = if (result.dailyDate != null) resources.getString(R.string.label_daily_challenge_date, result.dailyDate) else model.game?.levelLabel.orEmpty()
        val difficulty = resources.getString(result.difficulty.label)
        val shots = resources.getQuantityString(R.plurals.shots_count, result.shotsUsed, result.shotsUsed)
        val summary = listOf(resources.getString(R.string.points_value, points),
            resources.getQuantityString(R.plurals.stars_count, result.stars, result.stars), shots).joinToString(" · ")
        val text = "Bubble Night · $label · $difficulty\n${result.title}\n$summary"
        val bitmap = Bitmap.createBitmap(1080, 900, Bitmap.Config.ARGB_8888)
        val directory = File(context.cacheDir, "shared-results")
        val file = File(directory, "result-${java.util.UUID.randomUUID()}.png")
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(26, 27, 38))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            fun line(value: String, y: Float, size: Float, color: Int) {
                paint.textSize = size; paint.color = color
                while (paint.measureText(value) > 960f && paint.textSize > 20f) paint.textSize -= 2f
                canvas.drawText(value, 540f, y, paint)
            }
            val strong = Color.rgb(226, 232, 255)
            val purple = Color.rgb(187, 154, 247)
            line("Bubble Night", 160f, 82f, purple)
            line(label, 265f, 42f, strong)
            line(difficulty, 330f, 34f, purple)
            line(result.title, 420f, 44f, strong)
            line(points, 555f, 100f, strong)
            line(resources.getString(R.string.points_unit), 615f, 34f, strong)
            if (result.stars > 0) line("★".repeat(result.stars), 700f, 65f, Color.rgb(255, 158, 100))
            line(resources.getString(R.string.share_footer, shots), 820f, 30f, purple)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            directory.listFiles()?.filter { it != file && it.extension == "png" }?.sortedByDescending { it.lastModified() }
                ?.drop(31)?.forEach { it.delete() }
        } finally { bitmap.recycle() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = android.content.ClipData.newUri(context.contentResolver, "Bubble Night", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
