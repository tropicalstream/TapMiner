package com.tapminer

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapminer.audio.Sfx
import com.tapminer.audio.Voice
import com.tapminer.engine.Game
import com.tapminer.engine.GameHost
import com.tapminer.gl.GLRenderer
import kotlin.math.abs
import kotlin.math.max

/**
 * TapMiner. Two gestures, no settings menu:
 *  - TAP (arrives as a KEY on the glasses) = hop the rover. Also selects on
 *    the title and restarts after game over.
 *  - SWIPE forward / back = shift up / down a gear (speed). One discrete
 *    gear per gesture, classified on finger-up. Vertical swipe picks the
 *    mode on the title. (Horizontal dx sign is inverted vs the physical
 *    gesture on this pad — the established mapping.)
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: Voice
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    private var lastAction = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this).also { it.load() }
        sfx.duckProvider = { voice.isSpeaking }
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startEngineLoop() = sfx.startEngineLoop()
    override fun stopEngineLoop() = sfx.stopEngineLoop()
    override fun setEngineRate(rate: Float) = sfx.setEngineRate(rate)
    override fun say(id: String, urgent: Boolean) = voice.say(id, urgent)

    // --------------------------------------------------------------- input

    private fun act(run: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 25) return // KEY+touch echo of one physical press
        lastAction = now
        glView.queueEvent(run)
    }

    private fun tap() = act { game.tap() }
    private fun gear(dir: Int) = act { game.shiftGear(dir) }
    private fun select(d: Int) = act { game.select(d) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { tap(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { gear(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { gear(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { select(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { select(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dead = max(16f, 0.02f * resources.displayMetrics.widthPixels)
                if (abs(dx) < dead && abs(dy) < dead) { tap(); return true }
                if (abs(dx) >= abs(dy)) {
                    // Forward swipe = shift up a gear. dx sign inverted on this pad.
                    gear(if (dx < 0) 1 else -1)
                } else {
                    select(if (dy < 0) -1 else 1)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        sfx.stopEngineLoop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        voice.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
