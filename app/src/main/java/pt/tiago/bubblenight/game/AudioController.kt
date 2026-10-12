package pt.tiago.bubblenight.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import pt.tiago.bubblenight.R

/** Local sound cues, independent from the deterministic game state. */
class AudioController(context: Context) {
    enum class Cue { LAUNCH, BOUNCE, MATCH, DROP, VICTORY, DEFEAT }
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val streams = ArrayDeque<Int>()
    private var active = true
    private var released = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }.build()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Vibrator::class.java)
    private val sounds: Map<Cue, Int>

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        sounds = mapOf(
            Cue.LAUNCH to pool.load(context, R.raw.launch, 1),
            Cue.BOUNCE to pool.load(context, R.raw.bounce, 1),
            Cue.MATCH to pool.load(context, R.raw.match, 1),
            Cue.DROP to pool.load(context, R.raw.drop, 1),
            Cue.VICTORY to pool.load(context, R.raw.victory, 1),
            Cue.DEFEAT to pool.load(context, R.raw.defeat, 1),
        )
    }

    fun play(cue: Cue, sound: Boolean, haptics: Boolean) {
        if (!active || released) return
        if (sound) {
            val id = sounds.getValue(cue)
            if (id in loaded && manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                if (streams.size >= 4) pool.stop(streams.removeFirst())
                streams.add(pool.play(id, .45f, .45f, 1, 0, 1f))
            }
        }
        if (haptics && cue in setOf(Cue.MATCH, Cue.DROP, Cue.VICTORY)) {
            vibrator?.vibrate(VibrationEffect.createOneShot(if (cue == Cue.VICTORY) 28L else 12L, 60))
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) stop()
    }

    fun stop() {
        if (released) return
        streams.forEach(pool::stop)
        streams.clear()
        vibrator?.cancel()
        manager.abandonAudioFocusRequest(focus)
    }

    fun release() {
        if (released) return
        stop()
        pool.release()
        released = true
    }
}
