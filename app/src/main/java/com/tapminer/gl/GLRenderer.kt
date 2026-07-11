package com.tapminer.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.tapminer.engine.Game
import com.tapminer.engine.GameState
import com.tapminer.engine.ObType
import com.tapminer.engine.Obstacle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * OpenGL ES 3.0 side-scroller renderer for TapMiner: a synthwave lunar
 * elevation. Orthographic front view (X = travel, Y = height). Parallax
 * mountains and an Earthrise sit far back; the regolith and its hazards
 * scroll past a fixed rover in the foreground. Additive neon lines on black
 * (transparent on the waveguide), one draw per eye into SBS viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(26000)
    private val fx = Batch(6000)
    private val hud = Batch(5000)

    // Distant stars, screen-static (drawn in world but far and slow).
    private val stars = Random(7).let { r -> FloatArray(70 * 2) { r.nextFloat() } }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        game.setHalfW(V * aspect)

        game.update(dt)

        buildScene(); buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Front orthographic view; ground (y=0) sits in the lower third. A
        // little shake on crashes / big quakes.
        val sh = game.shake
        val jx = if (sh > 0.01f) (sin(game.time * 47f)) * sh * 0.6f else 0f
        val jy = if (sh > 0.01f) (sin(game.time * 53f)) * sh * 0.4f else 0f
        val cy = V * 0.34f
        Matrix.orthoM(proj, 0, -V * aspect, V * aspect, -V, V, -10f, 10f)
        Matrix.setLookAtM(view, 0, jx, cy + jy, 5f, jx, cy + jy, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 9f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    private val hw get() = game.halfW

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); fx.reset()
        buildSky()
        // The coffee break is a Ms-Pac-Man-style story intermission on a clean
        // stage; skip the scrolling world entirely for it.
        if (game.state == GameState.SECTOR_CLEAR) {
            buildIntermission()
            drawParticles()
            return
        }
        buildParallax()
        buildGround()
        buildHazards()
        buildGizmos()
        if (game.state != GameState.TITLE) {
            if (game.roverAlive) buildRover()
            buildUfos()
            buildBombs()
            buildShots()
        } else {
            // Attract mode: the rover idles menacingly (wheels turning) while
            // the natives' children scatter ahead of it.
            buildRoverAt(game.roverScreenX, 0f, 0f, game.wheelSpin, 1f)
            buildFleeingKids()
        }
        drawParticles()
    }

    /** Auto-cannon bolts streaking up to intercept the aliens' fire. */
    private fun buildShots() {
        val ss = game.shots
        for (i in 0 until ss.size) {
            val s = ss[i]
            hsv((game.time * 0.9f) % 1f, 0.5f, 1f)
            lines.line(s.x, s.y - 0.9f, 0f, s.x, s.y, 0f, rgb[0], rgb[1], rgb[2], 0.9f)
            fx.v(s.x, s.y, 0f, 1f, 1f, 1f, 1f)
        }
    }

    private fun drawParticles() {
        val ps = game.particles
        for (i in 0 until ps.size) {
            val p = ps[i]
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            hsv(p.hue, 1f, 1f)
            fx.v(p.x, p.y + groundScreenY(p.x), p.z, rgb[0], rgb[1], rgb[2], k)
        }
    }

    private fun buildSky() {
        for (i in 0 until stars.size / 2) {
            val x = stars[i * 2] * (hw * 2f) - hw
            val y = 4f + stars[i * 2 + 1] * (V * 1.4f)
            val tw = 0.3f + 0.3f * sin(game.time * 1.5f + i)
            hsv((game.time * 0.02f + i * 0.02f) % 1f, 0.4f, 0.8f)
            fx.v(x, y, -6f, rgb[0], rgb[1], rgb[2], tw)
        }
        // Earthrise, far right-back — a hollow neon world with a crescent.
        val ex = hw * 0.62f; val ey = V * 1.15f; val er = V * 0.28f
        hsv((game.time * 0.05f + 0.55f) % 1f, 0.7f, 1f)
        ring(ex, ey, -6f, er, 22, rgb[0], rgb[1], rgb[2], 0.6f)
        ring(ex + er * 0.35f, ey, -6f, er * 0.8f, 18, rgb[0], rgb[1], rgb[2], 0.25f)
    }

    /** Two ranges of jagged mountains, scrolling at different slow rates. */
    private fun buildParallax() {
        // A low, sleek neon skyline (the 20%-smaller peaks stuck as the default).
        val m = 0.8f
        drawRidge(0.06f, 3.2f * m, 6.5f * m, 11, 0.55f, 0.7f, 0.35f)
        drawRidge(0.14f, 1.4f * m, 4.2f * m, 15, 0.75f, 0.62f, 0.5f)
    }

    private fun drawRidge(rate: Float, baseY: Float, amp: Float, teeth: Int, sat: Float, hueOrNeg: Float, alpha: Float) {
        val off = game.scroll * rate
        if (hueOrNeg < 0f) { rgb[0] = 0.5f; rgb[1] = 0.35f; rgb[2] = 0.15f } else hsv((hueOrNeg + game.time * 0.02f) % 1f, sat, 0.7f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val span = hw * 2f + 8f
        val step = span / teeth
        var px = -hw - 4f
        var py = baseY + peak(px + off, amp)
        var x = px + step
        while (x <= hw + 4f) {
            val y = baseY + peak(x + off, amp)
            lines.line(px, py, -4f, x, y, -4f, r, g, b, alpha)
            px = x; py = y; x += step
        }
    }

    private fun peak(x: Float, amp: Float): Float {
        val s = sin(x * 0.5f) + 0.5f * sin(x * 1.3f + 1f) + 0.3f * sin(x * 0.21f)
        return (kotlin.math.abs(s) * amp)
    }

    /** Screen y of the regolith surface at screen x (accounts for craters). */
    private fun groundScreenY(screenX: Float): Float = game.groundY(game.scroll + screenX)

    private fun buildGround() {
        hsv((game.time * 0.04f + 0.5f) % 1f, 0.75f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // The surface line, sampled across the screen so craters dip through it.
        val step = 0.7f
        var sx = -hw - 2f
        var py = groundScreenY(sx)
        var x = sx + step
        while (x <= hw + 2f) {
            val y = groundScreenY(x)
            lines.line(sx, py, 0f, x, y, 0f, r, g, b, 0.95f)
            // short hatch ticks below for texture
            if (((x + game.scroll) * 0.5f).toInt() % 2 == 0) lines.line(x, y, 0f, x - 0.25f, y - 0.7f, 0f, r, g, b, 0.3f)
            sx = x; py = y; x += step
        }
    }

    private fun buildHazards() {
        val obs = game.obstacles
        for (i in 0 until obs.size) {
            val o = obs[i]
            if (o.dead) continue
            val sx = o.worldX - game.scroll
            if (sx < -hw - 3f || sx > hw + 3f) continue
            when (o.type) {
                ObType.ORE -> if (!o.mined) buildOre(sx)
                ObType.ROCK -> buildRock(sx, o.w)
                ObType.SPIRE -> buildSpire(sx, o.w)
                ObType.MINE -> buildMine(sx)
                ObType.CRATER -> buildCraterRim(sx, o.w)
            }
        }
    }

    private fun buildOre(sx: Float) {
        val gy = groundScreenY(sx)
        val pulse = 0.6f + 0.4f * sin(game.time * 5f + sx)
        hsv((game.time * 0.6f + sx * 0.1f) % 1f, 0.8f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // a little crystal cluster
        lines.line(sx, gy, 0f, sx, gy + 1.1f, 0f, r, g, b, pulse)
        lines.line(sx - 0.4f, gy + 0.2f, 0f, sx - 0.4f, gy + 0.8f, 0f, r, g, b, pulse)
        lines.line(sx + 0.4f, gy + 0.1f, 0f, sx + 0.45f, gy + 0.95f, 0f, r, g, b, pulse)
        lines.line(sx - 0.4f, gy + 0.8f, 0f, sx, gy + 1.1f, 0f, r, g, b, pulse)
        lines.line(sx + 0.45f, gy + 0.95f, 0f, sx, gy + 1.1f, 0f, r, g, b, pulse)
        fx.v(sx, gy + 1.15f, 0f, 1f, 1f, 1f, pulse)
    }

    private fun buildRock(sx: Float, w: Float) {
        val gy = groundScreenY(sx)
        hsv((game.time * 0.03f + 0.08f) % 1f, 0.6f, 0.9f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val h = 1.3f
        lines.line(sx - w, gy, 0f, sx - w * 0.5f, gy + h, 0f, r, g, b, 0.9f)
        lines.line(sx - w * 0.5f, gy + h, 0f, sx + w * 0.4f, gy + h * 0.9f, 0f, r, g, b, 0.9f)
        lines.line(sx + w * 0.4f, gy + h * 0.9f, 0f, sx + w, gy, 0f, r, g, b, 0.9f)
        lines.line(sx - w, gy, 0f, sx + w, gy, 0f, r, g, b, 0.5f)
    }

    private fun buildSpire(sx: Float, w: Float) {
        val gy = groundScreenY(sx)
        hsv((game.time * 0.4f + 0.85f) % 1f, 0.8f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        lines.line(sx - w, gy, 0f, sx, gy + 2.4f, 0f, r, g, b, 0.9f)
        lines.line(sx + w, gy, 0f, sx, gy + 2.4f, 0f, r, g, b, 0.9f)
        fx.v(sx, gy + 2.5f, 0f, 1f, 1f, 1f, 0.8f)
    }

    private fun buildMine(sx: Float) {
        val gy = groundScreenY(sx)
        val blink = if (((game.time * 3f).toInt()) and 1 == 0) 1f else 0.3f
        rgb[0] = 1f; rgb[1] = 0.3f; rgb[2] = 0.25f
        ring(sx, gy + 0.4f, 0f, 0.4f, 8, rgb[0], rgb[1], rgb[2], 0.9f)
        for (k in 0 until 6) {
            val a = k * 1.047f
            lines.line(sx, gy + 0.4f, 0f, sx + cos(a) * 0.7f, gy + 0.4f + sin(a) * 0.7f, 0f, 1f, 0.3f, 0.25f, 0.6f)
        }
        fx.v(sx, gy + 0.4f, 0f, 1f, 0.4f, 0.3f, blink)
    }

    private fun buildCraterRim(sx: Float, w: Float) {
        hsv((game.time * 0.04f + 0.5f) % 1f, 0.7f, 0.9f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // little rim lips flanking the pit
        lines.line(sx - w, 0f, 0f, sx - w - 0.6f, 0.5f, 0f, r, g, b, 0.7f)
        lines.line(sx + w, 0f, 0f, sx + w + 0.6f, 0.5f, 0f, r, g, b, 0.7f)
    }

    private fun buildGizmos() {
        val gs = game.gizmos
        for (i in 0 until gs.size) {
            val g = gs[i]
            if (g.dead) continue
            val sx = g.worldX - game.scroll
            if (sx < -hw - 2f || sx > hw + 2f) continue
            val gy = groundScreenY(sx) + 2.2f
            hsv(gizHue(g.type), 0.85f, 1f)
            val r = rgb[0]; val gg = rgb[1]; val b = rgb[2]
            val pulse = 0.6f + 0.4f * sin(game.time * 6f + i)
            val rot = game.time * 3f
            val s = 0.6f
            var px = sx + cos(rot) * s; var py = gy + sin(rot) * s
            for (k in 1..4) {
                val a2 = rot + k * 1.5708f
                val vx = sx + cos(a2) * s; val vy = gy + sin(a2) * s
                lines.line(px, py, 0f, vx, vy, 0f, r, gg, b, pulse)
                px = vx; py = vy
            }
            lines.line(sx, groundScreenY(sx), 0f, sx, gy - s, 0f, r, gg, b, 0.25f * pulse)
            fx.v(sx, gy, 0f, 1f, 1f, 1f, pulse)
        }
    }

    private fun gizHue(type: Int) = when (type) {
        Game.GIZ_CANNON -> 0.0f      // hot red — the weapon
        Game.GIZ_SHIELD -> 0.33f     // green
        Game.GIZ_SLOW -> 0.72f       // violet
        Game.GIZ_MAGNET -> 0.08f     // ember
        else -> 0.5f                 // drill cyan
    }

    private fun buildRover() {
        val x = game.roverScreenX
        val gy = if (game.airborne) game.roverY else groundScreenY(x)
        val y = if (game.airborne) game.roverY else gy
        val blink = if (game.invuln > 0f) (0.45f + 0.55f * sin(game.time * 20f)) else 1f
        buildRoverAt(x, y, game.tilt, game.wheelSpin, blink)
        // thruster flare when hopping
        if (game.airborne && game.roverVy > -2f) {
            hsv(0.09f, 1f, 1f)
            val k = (game.roverVy / Game.JUMP_V).coerceIn(0f, 1f)
            lines.line(x - 0.5f, y + 0.15f, 0f, x - 0.2f, y - 0.9f - k, 0f, rgb[0], rgb[1], rgb[2], k)
            lines.line(x + 0.5f, y + 0.15f, 0f, x + 0.2f, y - 0.9f - k, 0f, rgb[0], rgb[1], rgb[2], k)
        }
        if (game.invuln > 0f && game.activeGizmo == Game.GIZ_SHIELD) {
            hsv((game.time * 0.5f) % 1f, 0.6f, 1f)
            ring(x, y + 0.9f, 0f, 2.4f, 12, rgb[0], rgb[1], rgb[2], 0.4f * blink)
        }
    }

    /** The lunar rover: a domed cockpit on a sprung chassis with two big wheels. */
    private fun buildRoverAt(x: Float, y: Float, tilt: Float, spin: Float, a: Float) {
        val cr = 0.4f; val cg = 0.95f; val cb = 1f
        val c = cos(tilt); val s = sin(tilt)
        // local segment helper (rotate around chassis center by tilt)
        fun ln(x0: Float, y0: Float, x1: Float, y1: Float, al: Float = a) {
            lines.line(
                x + x0 * c - y0 * s, y + 0.95f + x0 * s + y0 * c, 0f,
                x + x1 * c - y1 * s, y + 0.95f + x1 * s + y1 * c, 0f,
                cr, cg, cb, al
            )
        }
        // chassis
        ln(-1.5f, -0.1f, 1.5f, -0.1f)
        ln(-1.5f, -0.1f, -1.2f, 0.4f)
        ln(1.5f, -0.1f, 1.2f, 0.4f)
        ln(-1.2f, 0.4f, 1.2f, 0.4f)
        // cockpit dome
        ln(-0.6f, 0.4f, -0.5f, 0.95f)
        ln(-0.5f, 0.95f, 0.4f, 0.95f)
        ln(0.4f, 0.95f, 0.7f, 0.4f)
        // headlight
        lines.line(x + 1.5f * c, y + 0.95f + 1.5f * s, 0f, x + 2.3f * c, y + 0.95f + 1.5f * s + 0.1f, 0f, cr, cg, cb, 0.4f * a)
        fx.v(x + 1.5f * c - (-0.1f) * s, y + 0.95f + 1.5f * s + (-0.1f) * c, 0f, 1f, 1f, 0.8f, a)
        // two spinning wheels (spokes) sitting on the ground under the chassis
        wheel(x - 1.0f, y + 0.2f, 0.55f, spin, cr, cg, cb, a)
        wheel(x + 1.0f, y + 0.2f, 0.55f, spin, cr, cg, cb, a)
    }

    private fun wheel(cx: Float, cy: Float, rad: Float, spin: Float, r: Float, g: Float, b: Float, a: Float) {
        ring(cx, cy, 0f, rad, 9, r, g, b, a)
        // A "T" emblem spinning inside each hub (the brand). Its stem/bar rotate
        // with the wheel so it reads as painted-on.
        val c = cos(spin); val s = sin(spin)
        val tr = rad * 0.62f
        // top bar of the T (horizontal in wheel-local space)
        lines.line(cx - tr * c, cy - tr * s, 0f, cx + tr * c, cy + tr * s, 0f, r, g, b, a)
        // stem of the T (perpendicular, from bar center downward)
        lines.line(cx, cy, 0f, cx + tr * s, cy - tr * c, 0f, r, g, b, a)
    }

    /** Little alien children sprinting away from the wheeled menace, arms up. */
    private fun buildFleeingKids() {
        val span = hw * 2f + 6f
        for (k in 0 until 5) {
            val spd = 3.2f + k * 0.5f
            // run rightward (away from the rover on the left), wrapping the screen
            val x = -hw + 1f + ((game.time * spd + k * 6.1f) % span)
            if (x > hw + 1f) continue
            val gy = groundScreenY(x)
            val phase = game.time * 11f + k
            hsv((0.28f + k * 0.12f + game.time * 0.1f) % 1f, 0.8f, 1f)
            drawKid(x, gy, phase, rgb[0], rgb[1], rgb[2])
        }
    }

    private fun drawKid(x: Float, gy: Float, phase: Float, r: Float, g: Float, b: Float) {
        val bob = kotlin.math.abs(sin(phase)) * 0.18f
        val hipY = gy + 0.7f + bob
        val shoulderY = hipY + 0.5f
        // round head
        ring(x, shoulderY + 0.35f, 0f, 0.26f, 7, r, g, b, 0.9f)
        // two antennae with glowing tips (terror-erect)
        lines.line(x - 0.08f, shoulderY + 0.58f, 0f, x - 0.22f, shoulderY + 0.95f, 0f, r, g, b, 0.8f)
        lines.line(x + 0.08f, shoulderY + 0.58f, 0f, x + 0.22f, shoulderY + 0.95f, 0f, r, g, b, 0.8f)
        fx.v(x - 0.22f, shoulderY + 0.98f, 0f, 1f, 1f, 1f, 0.8f)
        fx.v(x + 0.22f, shoulderY + 0.98f, 0f, 1f, 1f, 1f, 0.8f)
        // torso
        lines.line(x, shoulderY, 0f, x, hipY, 0f, r, g, b, 0.9f)
        // panic arms flung up
        val sw = sin(phase) * 0.18f
        lines.line(x, shoulderY, 0f, x - 0.38f, shoulderY + 0.45f + sw, 0f, r, g, b, 0.85f)
        lines.line(x, shoulderY, 0f, x + 0.38f, shoulderY + 0.45f - sw, 0f, r, g, b, 0.85f)
        // running legs, alternating
        val ls = sin(phase) * 0.32f
        lines.line(x, hipY, 0f, x - 0.22f + ls, gy, 0f, r, g, b, 0.9f)
        lines.line(x, hipY, 0f, x + 0.22f + ls, gy, 0f, r, g, b, 0.9f)
    }

    // -------------------------------------------------- story intermission

    val actTitles = arrayOf(
        "ACT I  -  THEY MEET",
        "ACT II  -  THE MISUNDERSTANDING",
        "ACT III  -  THEY ARM",
        "ACT IV  -  THE RECKONING",
    )

    /**
     * Ms-Pac-Man-style cutscene played on the remix coffee break. A flat stage,
     * the rover on the left, the natives acting out the next beat of the
     * story on the right — from first contact to the vow that becomes Part 2.
     */
    private fun buildIntermission() {
        val t = game.time
        val act = (game.sector - 1).coerceIn(0, actTitles.size - 1)
        // lit stage floor
        hsv((t * 0.04f + 0.5f) % 1f, 0.75f, 1f)
        lines.line(-hw - 2f, 0f, 0f, hw + 2f, 0f, 0f, rgb[0], rgb[1], rgb[2], 0.9f)

        val rx = -hw * 0.5f
        buildRoverAt(rx, 0f, 0f, t * 6f, 1f)

        when (act) {
            0 -> {  // They meet: a peaceful saucer, a waving native, a little heart
                val ux = hw * 0.32f; val uy = 5f + 0.5f * sin(t * 1.8f)
                drawSaucer(ux, uy, 1.1f, hostile = false, t = t)
                hsv(0.4f, 0.5f, 1f)
                lines.line(ux, uy - 1.2f, 0f, ux, 0.6f, 0f, rgb[0], rgb[1], rgb[2], 0.2f + 0.15f * sin(t * 4f))
                drawWavingAlien(ux - 0.3f, 0f, t, wave = true)
                hsv(0.95f, 0.7f, 1f)
                fx.v((rx + ux) / 2f, 3.2f + 0.4f * sin(t * 3f), 0f, rgb[0], rgb[1], rgb[2], 0.5f + 0.5f * sin(t * 3f))
            }
            1 -> {  // The misunderstanding: rover hauls a glowing hill away; native dismayed
                val hx = rx - 3.4f
                hsv((t * 0.6f) % 1f, 0.85f, 1f)
                var px = hx + 1f; var py = 0f
                for (k in 1..6) {
                    val a = k * 1.047f; val vx = hx + cos(a); val vy = 1f + sin(a)
                    lines.line(px, py, 0f, vx, vy, 0f, rgb[0], rgb[1], rgb[2], 0.85f); px = vx; py = vy
                }
                lines.line(rx - 1.4f, 0.6f, 0f, hx + 1f, 0.6f, 0f, 0.6f, 0.6f, 0.7f, 0.5f) // tow line
                drawWavingAlien(hw * 0.34f, 0f, t, wave = false)
                // an alarmed exclamation over the native's head
                hsv(0.03f, 0.9f, 1f)
                val bl = 0.5f + 0.5f * sin(t * 8f)
                lines.line(hw * 0.34f, 2.7f, 0f, hw * 0.34f, 3.6f, 0f, rgb[0], rgb[1], rgb[2], bl)
                fx.v(hw * 0.34f, 2.45f, 0f, 1f, 0.4f, 0.3f, bl)
            }
            2 -> {  // They arm: saucers under construction, welding sparks
                for (k in 0 until 3) {
                    val sx = hw * 0.08f + k * 3.2f; val sy = 4.6f + 0.3f * sin(t * 2f + k)
                    drawSaucer(sx, sy, 0.85f, hostile = true, t = t)
                    if (((t * 7f + k).toInt()) and 1 == 0) {
                        hsv(0.13f, 0.35f, 1f)
                        fx.v(sx + (k - 1) * 0.3f, sy - 1.1f, 0f, rgb[0], rgb[1], rgb[2], 0.9f)
                    }
                }
                drawWavingAlien(hw * 0.5f, 0f, t, wave = false)
            }
            else -> {  // The reckoning: a formation advances — the shape of Part 2
                val adv = (t * 1.1f) % 6f
                for (row in 0 until 2) for (col in 0 until 4) {
                    val sx = hw * 0.55f - col * 2.3f - adv + row * 0.5f
                    val sy = 6.2f - row * 1.7f
                    if (sx < -hw - 2f) continue
                    drawSaucer(sx, sy, 0.7f, hostile = true, t = t)
                }
            }
        }
    }

    private fun drawSaucer(x: Float, y: Float, sc: Float, hostile: Boolean, t: Float) {
        if (hostile) hsv((t * 0.5f) % 1f, 0.9f, 1f) else hsv(0.42f, 0.5f, 1f)
        ring(x, y, 0f, 1.3f * sc, 12, rgb[0], rgb[1], rgb[2], 0.95f)
        if (hostile) hsv((t * 0.5f + 0.33f) % 1f, 0.9f, 1f) else hsv(0.47f, 0.5f, 1f)
        ring(x, y + 0.45f * sc, 0f, 0.65f * sc, 10, rgb[0], rgb[1], rgb[2], 0.95f)
        fx.v(x, y + 0.75f * sc, 0f, 1f, 1f, 1f, 0.8f)
    }

    private fun drawWavingAlien(x: Float, gy: Float, t: Float, wave: Boolean) {
        val phase = t * 3f
        val hipY = gy + 0.7f; val shoulderY = hipY + 0.5f
        hsv(0.34f, 0.7f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        ring(x, shoulderY + 0.35f, 0f, 0.26f, 7, r, g, b, 0.9f)
        lines.line(x - 0.08f, shoulderY + 0.58f, 0f, x - 0.2f, shoulderY + 0.95f, 0f, r, g, b, 0.8f)
        lines.line(x + 0.08f, shoulderY + 0.58f, 0f, x + 0.2f, shoulderY + 0.95f, 0f, r, g, b, 0.8f)
        fx.v(x - 0.2f, shoulderY + 0.98f, 0f, 1f, 1f, 1f, 0.8f)
        fx.v(x + 0.2f, shoulderY + 0.98f, 0f, 1f, 1f, 1f, 0.8f)
        lines.line(x, shoulderY, 0f, x, hipY, 0f, r, g, b, 0.9f)
        lines.line(x, hipY, 0f, x - 0.22f, gy, 0f, r, g, b, 0.9f)
        lines.line(x, hipY, 0f, x + 0.22f, gy, 0f, r, g, b, 0.9f)
        // one arm waves (friendly) or both hang (dismayed)
        val w = if (wave) kotlin.math.abs(sin(phase)) * 0.5f else -0.35f
        lines.line(x, shoulderY, 0f, x + 0.4f, shoulderY + 0.35f + w, 0f, r, g, b, 0.85f)
        lines.line(x, shoulderY, 0f, x - 0.35f, shoulderY - (if (wave) 0.05f else 0.35f), 0f, r, g, b, 0.85f)
    }

    private fun buildUfos() {
        val us = game.ufos
        for (i in 0 until us.size) {
            val u = us[i]
            val bob = 0.12f * sin(u.t * 6f)
            if (!u.hostile) {
                // Peaceful sightseer: calm cyan-green, a gentle scanning beam,
                // no grabbers — just watching, which is somehow worse.
                hsv(0.42f, 0.5f, 1f)
                val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
                ring(u.x, u.y + bob, 0f, 1.4f, 12, r, g, b, 0.9f)
                hsv(0.47f, 0.5f, 1f)
                ring(u.x, u.y + 0.45f + bob, 0f, 0.7f, 10, rgb[0], rgb[1], rgb[2], 0.9f)
                fx.v(u.x, u.y + 0.75f + bob, 0f, 0.8f, 1f, 0.9f, 0.6f + 0.3f * sin(u.t * 3f))
                lines.line(u.x, u.y - 1f + bob, 0f, u.x, 0.4f, 0f, r, g, b, 0.12f + 0.08f * sin(u.t * 3f))
                continue
            }
            hsv((game.time * 0.5f + i * 0.2f) % 1f, 0.9f, 1f)
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            ring(u.x, u.y + bob, 0f, 1.4f, 12, r, g, b, 0.95f)
            hsv((game.time * 0.5f + i * 0.2f + 0.33f) % 1f, 0.9f, 1f)
            ring(u.x, u.y + 0.45f + bob, 0f, 0.7f, 10, rgb[0], rgb[1], rgb[2], 0.95f)
            fx.v(u.x, u.y + 0.75f + bob, 0f, 1f, 1f, 1f, 0.8f + 0.2f * sin(u.t * 9f))
            // three angry legs / grabbers
            for (k in -1..1) fx.v(u.x + k * 0.7f, u.y - 0.5f + bob, 0f, r, g, b, 0.7f)
        }
    }

    private fun buildBombs() {
        val bs = game.bombs
        for (i in 0 until bs.size) {
            val b = bs[i]
            val flick = 0.6f + 0.4f * sin(game.time * 22f + i)
            rgb[0] = 1f; rgb[1] = 0.4f + 0.3f * flick; rgb[2] = 0.2f
            lines.line(b.x, b.y, 0f, b.x, b.y + 0.7f, 0f, rgb[0], rgb[1], rgb[2], 0.9f)
            fx.v(b.x, b.y, 0f, 1f, 0.5f, 0.3f, flick)
        }
    }

    private fun ring(x: Float, y: Float, z: Float, rad: Float, seg: Int, r: Float, g: Float, b: Float, a: Float) {
        var px = x + rad; var py = y
        for (i in 1..seg) {
            val ang = i * (6.2832f / seg)
            val vx = x + cos(ang) * rad; val vy = y + sin(ang) * rad
            lines.line(px, py, z, vx, vy, z, r, g, b, a)
            px = vx; py = vy
        }
    }

    // -------------------------------------------------------------- hud

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) { hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca) }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.time * 0.05f, 0.8f, 1f)
        val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]

        when (game.state) {
            GameState.TITLE -> {
                text("TAPMINER", 320f, 96f, 4.4f, hr, hg, hb)
                text("PART 1", 320f, 138f, 1.7f, 1f, 0.85f, 0.4f, pulse)
                text("BEFORE HE SWEPT - HE CONQUERED", 320f, 176f, 1.6f, 0.7f, 0.9f, 1f)
                text("HOP THE CRATERS - SHIFT THE GEARS", 320f, 262f, 1.5f, 0.8f, 0.9f, 1f)
                text("MINE THE ORE - DODGE THEIR FIRE", 320f, 296f, 1.5f, 0.8f, 0.9f, 1f)
                if (game.highScore > 0) text("HI ${game.highScore}", 320f, 344f, 1.6f, 0.6f, 1f, 0.7f)
                text("TAP TO CLOCK IN", 320f, 400f, 2.1f, 0.5f, 1f, 0.6f, pulse)
                text("CONTINUES IN PART 2: TAPINVADERS", 320f, 448f, 1.05f, 0.6f, 0.65f, 0.75f, 0.8f)
            }
            GameState.GAME_OVER -> {
                bar()
                text("CONTRACT TERMINATED", 320f, 192f, 2.7f, 1f, 0.4f, 0.35f)
                text("SCORE ${game.score}", 320f, 244f, 2.2f, 1f, 1f, 1f)
                text("SECTOR ${game.sector} - HI ${game.highScore}", 320f, 282f, 1.5f, 0.7f, 0.9f, 1f)
                val fl = 0.55f + 0.45f * sin(game.time * 2.2f)
                text("THE LOCALS WILL REMEMBER THIS", 320f, 320f, 1.4f, 1f, 0.55f, 0.35f, fl)
                text("TAP TO CLOCK BACK IN", 320f, 372f, 1.9f, 0.5f, 1f, 0.6f, pulse)
                text("NEXT - PART 2: TAPINVADERS - THEY FIGHT BACK", 320f, 424f, 1.15f, 0.85f, 0.7f, 1f, 0.8f)
            }
            GameState.LIFE_LOST -> { bar(); text("NEW RIG DEPLOYING", 320f, 250f, 2f, 1f, 0.7f, 0.4f, pulse) }
            GameState.SECTOR_CLEAR -> {
                bar()
                // The intermission title card, Ms-Pac-Man style.
                val act = (game.sector - 1).coerceIn(0, actTitles.size - 1)
                hsv((game.time * 0.25f) % 1f, 0.75f, 1f)
                text(actTitles[act], 320f, 66f, 2.1f, rgb[0], rgb[1], rgb[2])
                text("INTERMISSION - OUTPOST ${game.sector} SECURED", 320f, 96f, 1.2f, 0.75f, 0.9f, 1f, pulse * 0.6f + 0.4f)
            }
            else -> bar()
        }

        game.message?.let {
            hsv((game.messageHue + game.time * 0.4f) % 1f, 0.85f, 1f)
            text(it, 320f, 168f, 2.3f, rgb[0], rgb[1], rgb[2], 0.6f + 0.4f * pulse)
        }
    }

    private fun bar() {
        text("${game.score}", 16f, 40f, 2.2f, 1f, 1f, 1f, 1f, center = false)
        val md = "SECTOR ${game.sector}"
        text(md, 320f - StrokeFont.width(md, 1.4f) / 2f, 40f, 1.4f, 0.7f, 0.85f, 1f, 1f, center = false)
        // lives as little wheels, top right
        for (i in 0 until game.lives.coerceAtMost(6)) {
            val cx = 626f - i * 24f
            hud.line(cx - 7f, 30f, 0f, cx + 7f, 30f, 0f, 0.4f, 0.95f, 1f, 1f)
            hud.line(cx - 7f, 22f, 0f, cx + 7f, 22f, 0f, 0.4f, 0.95f, 1f, 0.6f)
        }
        // sector progress bar (colony spread)
        hud.line(60f, 60f, 0f, 580f, 60f, 0f, 0.4f, 0.5f, 0.6f, 0.5f)
        hud.line(60f, 60f, 0f, 60f + 520f * game.sectorProgress, 60f, 0f, 0.5f, 1f, 0.7f, 0.9f)

        // ANGER meter, bottom — how furious the locals are
        val tier = game.angerTier
        val ar = 0.5f + 0.5f * game.anger; val ag = 0.8f * (1f - game.anger)
        text("FURY", 60f, 452f, 1.3f, ar, ag, 0.3f, 0.9f, center = false)
        hud.line(120f, 448f, 0f, 120f + 240f * game.anger, 448f, 0f, ar, ag, 0.3f, 0.9f)
        if (tier >= 3) {
            val fl = 0.5f + 0.5f * sin(game.time * 10f)
            text("!! LOCALS ENRAGED !!", 430f, 452f, 1.4f, 1f, 0.3f, 0.25f, fl, center = false)
        }
        if (game.activeGizmo >= 0) {
            hsv(gizHue(game.activeGizmo), 0.85f, 1f)
            val name = Game.GIZMO_NAMES[game.activeGizmo].trimEnd('!')
            text(name, 320f - StrokeFont.width(name, 1.15f) / 2f, 452f, 1.15f, rgb[0], rgb[1], rgb[2], 0.9f, center = false)
        }
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMiner", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMiner", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val V = 15f   // ortho half-height in world units

        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
