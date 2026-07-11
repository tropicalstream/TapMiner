package com.tapminer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for TapMiner (no audio binaries ship). The bed is the
 * ENGINE loop — a chugging rover motor whose playback RATE the game nudges
 * with the gear, so shifting up literally revs it. Mining pings, the springy
 * lunar hop, and the aliens' rising fury round it out.
 */
class Sfx(private val context: Context) {

    companion object {
        const val JUMP = 0
        const val LAND = 1
        const val GEAR = 2
        const val MINE = 3
        const val SMASH = 4
        const val CRASH = 5
        const val ENGINE = 6       // looping rover motor (rate-modulated)
        const val UFO = 7          // an angry local warps in
        const val BOMB_DROP = 8
        const val BOMB_HIT = 9
        const val ANGER = 10       // the locals' fury deepens a tier
        const val WAVE = 11        // new sector
        const val CLEAR = 12       // outpost staked
        const val LIFE = 13
        const val SPAWN = 14
        const val START = 15
        const val GAMEOVER = 16
        const val HISCORE = 17
        const val GIZ_GET = 18
        const val GIZ_END = 19
        const val UI = 20
        const val SHOOT = 21       // auto-cannon bolt
        const val SHOT_HIT = 22    // bolt destroys a falling bomb
        const val UFO_DIE = 23     // a hostile ship shot down
        private const val COUNT = 24
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.6f
    @Volatile var duckProvider: (() -> Boolean)? = null
    private var engineStream = 0
    @Volatile private var engineRate = 1f
    private val rng = Random(5)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun loadAsync() {
        thread = HandlerThread("tapminer-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                // springy up-chirp for the hop
                ids[JUMP] = load(dir, "jump", buf(260) { t -> (sine(300f + 900f * (t / 0.26f).coerceAtMost(1f), t) * 0.5f + sq(150f + 500f * t, t) * 0.15f) * exp(-t * 5.5f) })
                ids[LAND] = load(dir, "land", buf(150) { t -> (noise() * 0.4f + sine(120f, t) * 0.5f) * exp(-t * 16f) })
                ids[GEAR] = load(dir, "gear", buf(120) { t -> (sq(220f + 260f * t, t) * 0.4f + 0.2f * noise()) * exp(-t * 12f) })
                ids[MINE] = load(dir, "mine", buf(220) { t -> (sine(900f, t) * 0.4f + sine(1350f, t) * 0.3f + sine(1800f, t) * 0.2f) * exp(-t * 9f) })
                ids[SMASH] = load(dir, "smash", buf(240) { t ->
                    val crush = if ((t * 40f).toInt() % 2 == 0) 1f else 0.5f
                    ((noise() * 0.7f + sq(180f - 90f * t, t) * 0.3f) * crush) * exp(-t * 10f)
                })
                ids[CRASH] = load(dir, "crash", buf(800) { t ->
                    val f = 200f - t * 130f
                    val crush = if ((t * 26f).toInt() % 2 == 0) 1f else 0.4f
                    ((noise() * 0.6f + saw(f, t) * 0.4f) * crush) * exp(-t * 3.4f)
                })
                // rover motor: detuned pulse chug, loopable
                ids[ENGINE] = load(dir, "engine", buf(500) { t ->
                    val chug = 0.6f + 0.4f * sq(9f, t)
                    (saw(70f, t) * 0.3f + saw(70.7f, t) * 0.3f + sine(140f, t) * 0.2f) * chug * 0.6f
                })
                ids[UFO] = load(dir, "ufo", buf(480) { t -> sine(1200f - 500f * t + 80f * sin(2f * PI.toFloat() * 20f * t), t) * exp(-t * 3.5f) * 0.4f })
                ids[BOMB_DROP] = load(dir, "bdrop", buf(500) { t -> sine(1400f - 1100f * t, t) * exp(-t * 2.5f) * 0.35f })
                ids[BOMB_HIT] = load(dir, "bhit", buf(360) { t -> (noise() * 0.7f + sine(90f, t) * 0.5f) * exp(-t * 8f) })
                ids[ANGER] = load(dir, "anger", buf(700) { t -> (saw(120f + 40f * sin(2f * PI.toFloat() * 6f * t), t) * 0.4f + sine(60f, t) * 0.4f) * exp(-t * 2.5f) })
                ids[WAVE] = load(dir, "wave", arpeggio(intArrayOf(330, 440, 554, 659), 85, 0.7f))
                ids[CLEAR] = load(dir, "clear", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 80, 0.7f))
                ids[LIFE] = load(dir, "life", arpeggio(intArrayOf(784, 1046, 1318, 1568, 2093), 65, 0.7f))
                ids[SPAWN] = load(dir, "spawn", buf(320) { t -> sine(280f + 900f * t, t) * exp(-t * 6f) * 0.5f })
                ids[START] = load(dir, "start", arpeggio(intArrayOf(262, 330, 392, 523, 659), 75, 0.7f))
                ids[GAMEOVER] = load(dir, "over", buf(1000) { t ->
                    val f = if (t < 0.4f) 300f - t * 150f else 240f - (t - 0.4f) * 120f
                    (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 2f)
                })
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568, 2093), 80, 0.7f))
                ids[GIZ_GET] = load(dir, "gizget", arpeggio(intArrayOf(659, 880, 1174, 1568), 55, 0.75f))
                ids[GIZ_END] = load(dir, "gizend", buf(260) { t -> sine(700f - 380f * t, t) * exp(-t * 8f) * 0.4f })
                ids[UI] = load(dir, "ui", buf(35) { t -> sine(950f, t) * exp(-t * 60f) * 0.5f })
                ids[SHOOT] = load(dir, "shoot", buf(90) { t -> (saw(1400f - 800f * t, t) + 0.2f * noise()) * exp(-t * 28f) * 0.45f })
                ids[SHOT_HIT] = load(dir, "shhit", buf(150) { t ->
                    val crush = if ((t * 46f).toInt() % 2 == 0) 1f else 0.5f
                    ((noise() * 0.6f + sine(520f - 240f * t, t) * 0.4f) * crush) * exp(-t * 16f)
                })
                ids[UFO_DIE] = load(dir, "udie", buf(650) { t ->
                    val f = 900f - t * 750f
                    (saw(f, t) * 0.5f + sine(f * 0.5f, t) * 0.3f + noise() * 0.25f * exp(-t * 8f)) * exp(-t * 4.2f)
                })
                loaded = true
            }
        }
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        handler?.post {
            val s = ids[id]
            if (s == 0) return@post
            val duckMul = if (duckProvider?.invoke() == true) 0.4f else 1f
            val v = (volume * vol * duckMul).coerceIn(0f, 1f)
            if (v <= 0f) return@post
            pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
        }
    }

    fun startEngineLoop() {
        handler?.post {
            if (!loaded || engineStream != 0) return@post
            val v = (volume * 0.4f).coerceIn(0f, 1f)
            engineStream = pool.play(ids[ENGINE], v, v, 0, -1, engineRate.coerceIn(0.5f, 2f))
        }
    }

    fun setEngineRate(rate: Float) {
        engineRate = rate
        handler?.post { if (engineStream != 0) pool.setRate(engineStream, rate.coerceIn(0.5f, 2f)) }
    }

    fun stopEngineLoop() {
        handler?.post { if (engineStream != 0) { pool.stop(engineStream); engineStream = 0 } }
    }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
