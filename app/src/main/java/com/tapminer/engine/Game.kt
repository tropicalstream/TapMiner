package com.tapminer.engine

import com.tapminer.SettingsStore
import com.tapminer.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, PLAYING, LIFE_LOST, SECTOR_CLEAR, GAME_OVER }

/** Two shifts of the same miserable job. */
enum class Mode(val label: String, val blurb: String) {
    CLASSIC("CLASSIC", "THE OLD SHIFT: AMBER DUST, NO MERCY"),
    REMIX("REMIX", "NEON ORE, GADGETS, FURIOUS LOCALS, BACKTALK"),
}

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startEngineLoop()
    fun stopEngineLoop()
    fun setEngineRate(rate: Float)
    /** The young indentured miner mutters a pre-generated line (audio only). */
    fun say(id: String, urgent: Boolean = false)
}

/** Terrain hazards, laid out along worldX ahead of the rover. */
enum class ObType { CRATER, ROCK, ORE, MINE, SPIRE }
class Obstacle(val type: ObType, val worldX: Float, val w: Float) {
    var mined = false          // ORE collected
    var dead = false
}

/** An angry local's saucer, strafing overhead and dropping bombs. */
class Ufo(var x: Float, var y: Float, val dir: Float) {
    var t = 0f
    var dropT = 1.2f
    var dead = false
}
class Bomb(var x: Float, var y: Float, var vy: Float) { var dead = false }

/** A collectible gadget (remix only). */
class Gizmo(val worldX: Float, val type: Int) { var dead = false }

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f; var hue = 0f
}

/**
 * TapMiner — the prequel. A young indentured servant hops a lunar rover across
 * the mare, strip-mining ore to colonize the moon and, in the process,
 * thoroughly enraging the things that already live here. Moon-Patrol bones:
 * the rover holds a fixed screen position while the world scrolls past; you
 * only ever TAP to bound over trouble and SWIPE to change gear.
 *
 * World space: worldX advances with the rover; obstacles carry a worldX and
 * appear at screenX = worldX - scroll. y is height above the regolith.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val GRAV = 22f              // gentle lunar gravity
        const val JUMP_V = 11.5f
        const val GEARS = 4
        val GEAR_SPEED = floatArrayOf(7f, 11f, 15f, 20f)
        const val SECTOR_LEN = 260f       // worldX distance per colonized sector
        const val EXTRA_LIFE_EVERY = 4000
        const val RESPAWN_INVULN = 2.2f
        const val SPAWN_AHEAD = 30f       // how far ahead of the screen we seed hazards

        // Gadgets (remix): magnet grabs ore mid-air, shield, slow, drill smashes rocks.
        const val GIZ_MAGNET = 0
        const val GIZ_SHIELD = 1
        const val GIZ_SLOW = 2
        const val GIZ_DRILL = 3
        const val GIZMO_DURATION = 9f
        val GIZMO_NAMES = arrayOf("ORE MAGNET!", "HULL SHIELD!", "SLOW-MO!", "MEGA DRILL!")
    }

    var state = GameState.TITLE; private set
    var mode = Mode.CLASSIC; private set
    var selMode = 0; private set
    var time = 0f; private set

    // Viewport-derived visible half-width (set by the renderer, GL thread).
    var halfW = 22f; private set
    fun setHalfW(w: Float) { if (abs(w - halfW) > 0.01f) halfW = w }

    /** The rover holds this fixed screen x — left of center, scaled to the view. */
    val roverScreenX get() = -halfW * 0.5f

    // --- rover ---
    var scroll = 0f; private set          // worldX at the rover
    var gear = 1; private set
    var speed = GEAR_SPEED[1]; private set
    var roverY = 0f; private set          // height above ground
    var roverVy = 0f; private set
    var airborne = false; private set
    var wheelSpin = 0f; private set
    var tilt = 0f; private set            // nose pitch, for the hop
    var invuln = 0f; private set
    var roverAlive = false; private set

    // --- terrain / hazards ---
    val obstacles = ArrayList<Obstacle>()
    val gizmos = ArrayList<Gizmo>()
    private var seededTo = 0f
    // A compact terrain height cache: craters carve a pit; the renderer reads groundY().
    fun groundY(worldX: Float): Float {
        var y = 0f
        for (i in 0 until obstacles.size) {
            val o = obstacles[i]
            if (o.type != ObType.CRATER || o.dead) continue
            val d = worldX - o.worldX
            if (d > -o.w && d < o.w) {
                val f = 1f - (d / o.w) * (d / o.w)
                y = minOf(y, -3.2f * f)      // dip down into the pit
            }
        }
        return y
    }

    // --- aliens ---
    val ufos = ArrayList<Ufo>()
    val bombs = ArrayList<Bomb>()
    var anger = 0f; private set           // 0..1, mining raises it, time cools it
    val angerTier get() = when { anger > 0.8f -> 3; anger > 0.55f -> 2; anger > 0.28f -> 1; else -> 0 }
    private var lastAngerTier = 0
    private var ufoTimer = 6f

    // --- gadgets (remix) ---
    var activeGizmo = -1; private set
    var gizmoT = 0f; private set

    // --- score / progress ---
    var sector = 1; private set
    var score = 0; private set
    var oreLoad = 0; private set          // total ore mined (drives colony %)
    var lives = 3; private set
    var highScore = 0; private set
    private var nextLifeAt = EXTRA_LIFE_EVERY
    private var stateT = 0f
    private var sectorStartScroll = 0f
    val sectorProgress get() = ((scroll - sectorStartScroll) / SECTOR_LEN).coerceIn(0f, 1f)

    // --- flourish ---
    var message: String? = null; private set
    var messageHue = 0f; private set
    private var messageUntil = 0f
    var shake = 0f; private set

    // A short "GET READY" freeze at sector starts and respawns: the world
    // holds still so the player can read the terrain before it moves.
    var readyT = 0f; private set

    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()
    private val rng = Random(System.nanoTime())
    private var mineCombo = 0
    private var mineVoiceT = 0f
    private var titleVoiceT = 3f
    private var vowSaid = true
    private var vowT = 0f

    private val voiceOn get() = mode == Mode.REMIX

    fun boot() {
        highScore = store.highScore(0)
        state = GameState.TITLE
    }

    // ---------------------------------------------------------------- input

    fun select(d: Int) {
        if (state == GameState.GAME_OVER) { toTitle(); return }
        if (state != GameState.TITLE) return
        selMode = (selMode + d + Mode.entries.size) % Mode.entries.size
        highScore = store.highScore(selMode)
        host.sfx(Sfx.UI, 1.2f, 0.6f)
    }

    /** Swipe: shift gear. dir +1 = faster (forward), -1 = slower (back). */
    fun shiftGear(dir: Int) {
        when (state) {
            GameState.TITLE -> select(dir)
            GameState.GAME_OVER -> toTitle()
            GameState.PLAYING, GameState.SECTOR_CLEAR -> {
                if (!roverAlive) return
                val g = (gear + dir).coerceIn(0, GEARS - 1)
                if (g == gear) return
                gear = g
                speed = GEAR_SPEED[g]
                host.setEngineRate(0.7f + gear * 0.22f)
                host.sfx(Sfx.GEAR, 0.85f + gear * 0.12f, 0.7f)
                if (voiceOn && gear == GEARS - 1 && rng.nextFloat() < 0.5f) host.say("speed_up")
            }
            else -> {}
        }
    }

    /** Tap: hop the rover — or select / restart from the menus. */
    fun tap() {
        when (state) {
            GameState.TITLE -> startGame(Mode.entries[selMode])
            GameState.GAME_OVER -> startGame(mode)
            GameState.PLAYING, GameState.SECTOR_CLEAR -> jump()
            else -> {}
        }
    }

    private fun jump() {
        if (!roverAlive || airborne) return
        roverVy = JUMP_V
        airborne = true
        tilt = 0.5f
        host.sfx(Sfx.JUMP, 0.9f + rng.nextFloat() * 0.15f, 0.85f)
        repeat(10) { dustPuff() }
    }

    // ----------------------------------------------------------------- flow

    private fun startGame(m: Mode) {
        mode = m
        selMode = m.ordinal
        score = 0
        oreLoad = 0
        lives = 3
        sector = 1
        anger = 0f
        lastAngerTier = 0
        activeGizmo = -1
        nextLifeAt = EXTRA_LIFE_EVERY
        highScore = store.highScore(m.ordinal)
        store.games++
        host.sfx(Sfx.START)
        beginSector(reset = true)
        spawnRover()
        if (voiceOn) host.say("start")
    }

    private fun beginSector(reset: Boolean) {
        if (reset) {
            scroll = 0f
            seededTo = 0f
            obstacles.clear(); gizmos.clear(); ufos.clear(); bombs.clear()
        }
        sectorStartScroll = scroll
        gear = 1
        speed = GEAR_SPEED[1]
        ufoTimer = (7f - sector * 0.4f).coerceAtLeast(2.5f) + rng.nextFloat() * 4f
        state = GameState.PLAYING
        seedTerrain()
        if (!reset) clearRunway(16f)   // every sector opens with breathing room
        readyT = 1.5f
        host.startEngineLoop()
        host.setEngineRate(0.7f + gear * 0.22f)
        flash("SECTOR $sector - GET READY", 2.2f)
        host.sfx(Sfx.WAVE)
        store.setBestWave(mode.ordinal, sector)
    }

    private fun spawnRover() {
        roverY = 0f; roverVy = 0f; airborne = false
        tilt = 0f
        invuln = RESPAWN_INVULN
        roverAlive = true
        host.sfx(Sfx.SPAWN)
    }

    private fun flash(text: String, secs: Float) {
        message = text
        messageHue = rng.nextFloat()
        messageUntil = time + secs
    }

    fun toTitle() {
        state = GameState.TITLE
        host.stopEngineLoop()
        host.sfx(Sfx.UI, 0.8f)
    }

    // --------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        if (message != null && time > messageUntil) message = null
        if (shake > 0f) shake = maxOf(0f, shake - dt * 2.5f)
        updateParticles(dt)

        when (state) {
            GameState.TITLE -> {
                // Arcade attract mode: the natives flee across the screen and
                // jabber in their own tongue on a loop.
                titleVoiceT -= dt
                wheelSpin += dt * 5f
                if (titleVoiceT <= 0f) {
                    titleVoiceT = 5.5f + rng.nextFloat() * 4f
                    host.say("alien_flee_${1 + rng.nextInt(4)}")
                }
            }
            GameState.GAME_OVER -> {
                // The bridge to Part 2: once the miner's last grumble fades,
                // the natives deliver their vow — in formation, next time.
                if (!vowSaid) {
                    vowT -= dt
                    if (vowT <= 0f) { vowSaid = true; host.say("alien_vow") }
                }
            }
            GameState.PLAYING -> {
                stepWorld(dt)
                if (state == GameState.PLAYING && sectorProgress >= 1f) {
                    score += 250 * sector
                    checkExtraLife()
                    state = GameState.SECTOR_CLEAR
                    stateT = 3.2f
                    host.stopEngineLoop()
                    flash("OUTPOST $sector STAKED - COFFEE BREAK", 3f)
                    // The post-level "coffee break": the natives grumble in
                    // their language while the miner catches his breath.
                    host.say("alien_coffee_${1 + rng.nextInt(4)}", urgent = true)
                    host.sfx(Sfx.CLEAR)
                }
            }
            GameState.LIFE_LOST -> {
                stepScenery(dt)
                stateT -= dt
                if (stateT <= 0f) {
                    // Never respawn into trouble: flatten the road ahead, hold
                    // the world still for a beat, and shimmer through the rest.
                    clearRunway(16f)
                    spawnRover()
                    readyT = 1.4f
                    flash("GET READY", 1.6f)
                    state = GameState.PLAYING
                    host.startEngineLoop()
                }
            }
            GameState.SECTOR_CLEAR -> {
                stepScenery(dt)
                stateT -= dt
                if (stateT <= 0f) { sector++; beginSector(reset = false) }
            }
        }
    }

    /** Non-interactive drift used during death/clear pauses (keeps the world alive). */
    private fun stepScenery(dt: Float) {
        wheelSpin += speed * dt * 0.5f
        updateUfos(dt, 1f)
        updateBombs(dt, 1f)
    }

    private fun stepWorld(dt: Float) {
        // GET READY: the world holds still; the rover idles; nothing can hurt you.
        if (readyT > 0f) {
            readyT -= dt
            invuln = maxOf(invuln, 0.1f)
            wheelSpin += dt * 3f
            seedTerrain()
            return
        }
        val slow = if (activeGizmo == GIZ_SLOW) 0.55f else 1f
        val ds = speed * dt * slow
        scroll += ds
        wheelSpin += ds * 0.9f

        // gadget timer
        if (activeGizmo >= 0) {
            gizmoT -= dt
            if (gizmoT <= 0f) { activeGizmo = -1; host.sfx(Sfx.GIZ_END) }
        }

        // rover vertical (hop arc)
        invuln = maxOf(0f, invuln - dt)
        if (activeGizmo == GIZ_SHIELD) invuln = maxOf(invuln, 0.05f)
        if (airborne) {
            roverY += roverVy * dt
            roverVy -= GRAV * slow * dt
            tilt = (roverVy * 0.045f)
            val gy = groundY(scroll + roverScreenX)
            if (roverY <= gy) {
                roverY = gy
                airborne = false
                roverVy = 0f
                tilt = 0f
                if (roverY < -0.3f) { crash("crater"); return } // came down inside a pit
                else { host.sfx(Sfx.LAND, 1f, 0.6f); repeat(6) { dustPuff() } }
            }
        } else {
            // follow terrain; a pit underfoot with no jump = you drive into it
            val gy = groundY(scroll + roverScreenX)
            roverY = gy
            if (gy < -0.4f) { crash("crater"); return }
        }

        anger = (anger - dt * 0.02f).coerceIn(0f, 1f) // slowly cools when you behave
        mineVoiceT = maxOf(0f, mineVoiceT - dt)
        seedTerrain()
        collide()
        updateGizmos(dt)
        updateAnger(dt)
        updateUfos(dt, slow)
        updateBombs(dt, slow)

        // prune passed hazards
        var i = obstacles.size - 1
        while (i >= 0) { if (obstacles[i].worldX - scroll < -halfW - 12f || obstacles[i].dead) obstacles.removeAt(i); i-- }
        i = gizmos.size - 1
        while (i >= 0) { if (gizmos[i].worldX - scroll < -halfW - 12f || gizmos[i].dead) gizmos.removeAt(i); i-- }
    }

    // --------------------------------------------------------- terrain seed

    /**
     * CLEARABILITY CONTRACT. Jump physics give airtime 2·JUMP_V/GRAV ≈ 1.05 s
     * and reach speed·airtime — 7.3 units at gear 0, the worst case. So every
     * hazard must be jumpable at ANY gear:
     *  - crater half-width capped at 2.8 (pit span 5.6 < 7.3);
     *  - placement reserves the FULL width of each obstacle, so edge-to-edge
     *    gaps are guaranteed (craters can never merge into a mega-pit);
     *  - after a crater the next hazard leaves a proper landing zone.
     */
    private var lastSeedType = ObType.ORE

    private fun seedTerrain() {
        val target = scroll + halfW + SPAWN_AHEAD
        while (seededTo < target) {
            // spacing tightens with sector, but never below a jumpable rhythm;
            // landing room after a pit is sacred
            var gap = (7f - sector * 0.35f).coerceAtLeast(3.6f) + rng.nextFloat() * 4f
            if (lastSeedType == ObType.CRATER) gap = maxOf(gap, 5f)
            // A safe on-ramp at the very start of a run: ore only, no lethal
            // hazards, so the player learns to hop before anything can crash them.
            val intro = sector == 1 && seededTo < 22f
            val roll = rng.nextFloat()
            val type = when {
                intro -> ObType.ORE
                roll < 0.30f -> ObType.ORE
                roll < 0.52f -> ObType.CRATER
                roll < 0.72f -> ObType.ROCK
                roll < 0.85f -> ObType.SPIRE
                else -> ObType.MINE
            }
            val w = when (type) {
                ObType.CRATER -> (1.6f + rng.nextFloat() * (0.9f + sector * 0.1f)).coerceAtMost(2.8f)
                ObType.ROCK -> 0.9f + rng.nextFloat() * 0.7f
                ObType.SPIRE -> 0.5f + rng.nextFloat() * 0.4f
                ObType.ORE -> 0.8f
                ObType.MINE -> 0.6f
            }
            // reserve the full extent: [center-w, center+w], edge gap == gap
            val center = seededTo + gap + w
            obstacles.add(Obstacle(type, center, w))
            if (mode == Mode.REMIX && type == ObType.ORE && rng.nextFloat() < 0.08f) {
                gizmos.add(Gizmo(center + 2f, rng.nextInt(4)))
            }
            seededTo = center + w
            lastSeedType = type
        }
    }

    /**
     * Flatten every lethal hazard from just behind the rover to `ahead` in
     * front of it (ore may stay), and clear falling bombs — a guaranteed
     * survivable runway for respawns and sector starts.
     */
    private fun clearRunway(ahead: Float) {
        val rx = scroll + roverScreenX
        for (i in 0 until obstacles.size) {
            val o = obstacles[i]
            if (o.type == ObType.ORE) continue
            if (o.worldX + o.w > rx - 4f && o.worldX - o.w < rx + ahead) o.dead = true
        }
        bombs.clear()
    }

    // ----------------------------------------------------------- collisions

    private fun collide() {
        val magnet = activeGizmo == GIZ_MAGNET
        for (i in 0 until obstacles.size) {
            val o = obstacles[i]
            if (o.dead) continue
            val sx = o.worldX - scroll
            if (abs(sx - roverScreenX) > o.w + 1.3f) continue
            val overlap = abs(sx - roverScreenX) < o.w + 1.0f
            when (o.type) {
                ObType.ORE -> {
                    // Mined by driving over at ground level — or magnet grabs it airborne.
                    if (!o.mined && overlap && (roverY < 0.6f || magnet)) mineOre(o)
                }
                ObType.ROCK, ObType.SPIRE -> {
                    val topY = if (o.type == ObType.ROCK) 1.4f else 2.2f
                    if (overlap && roverY < topY && invuln <= 0f) {
                        if (activeGizmo == GIZ_DRILL) { smashRock(o) }
                        else { crash("rock"); return }
                    }
                }
                ObType.MINE -> {
                    if (overlap && roverY < 0.7f && invuln <= 0f) { o.dead = true; crash("mine"); return }
                }
                ObType.CRATER -> { /* handled via groundY in stepWorld */ }
            }
        }
    }

    private fun mineOre(o: Obstacle) {
        o.mined = true
        mineCombo++
        oreLoad++
        addScore(25 + mineCombo)
        anger = (anger + 0.055f).coerceIn(0f, 1f)
        host.sfx(Sfx.MINE, 1f + (mineCombo % 5) * 0.06f, 0.9f)
        oreBurst(roverScreenX, o.worldX)
        if (voiceOn && mineVoiceT <= 0f && rng.nextFloat() < 0.14f) {
            mineVoiceT = 8f
            host.say("mine_good")
        }
    }

    private fun smashRock(o: Obstacle) {
        o.dead = true
        addScore(15)
        explode(roverScreenX, 1f, 0.34f, 20, 5f)
        host.sfx(Sfx.SMASH)
    }

    private fun crash(kind: String) {
        roverAlive = false
        lives--
        mineCombo = 0
        shake = 1f
        explode(roverScreenX, 0.8f, 0.05f, 70, 8f)
        host.sfx(Sfx.CRASH)
        host.stopEngineLoop()
        if (lives <= 0) { gameOver(); return }
        state = GameState.LIFE_LOST
        stateT = 1.7f
        bombs.clear()
        if (voiceOn) host.say(if (rng.nextBoolean()) "death_1" else "death_2", urgent = true)
    }

    // ------------------------------------------------------------- gadgets

    private fun updateGizmos(dt: Float) {
        for (i in 0 until gizmos.size) {
            val g = gizmos[i]
            if (g.dead) continue
            val sx = g.worldX - scroll
            if (abs(sx - roverScreenX) < 1.6f && roverY < 2.2f) {
                g.dead = true
                activeGizmo = g.type
                gizmoT = GIZMO_DURATION
                flash(GIZMO_NAMES[g.type], 2f)
                host.sfx(Sfx.GIZ_GET)
                if (voiceOn) host.say("power_up")
            }
        }
    }

    // ------------------------------------------------------------- aliens

    private fun updateAnger(dt: Float) {
        val tier = angerTier
        if (tier > lastAngerTier) {
            lastAngerTier = tier
            if (voiceOn) host.say(when (tier) { 1 -> "anger_1"; 2 -> "anger_2"; 3 -> "anger_max"; else -> "anger_1" })
            host.sfx(Sfx.ANGER, 0.8f + tier * 0.12f)
            if (tier >= 3) shake = maxOf(shake, 0.7f)
        }
        if (tier < lastAngerTier) lastAngerTier = tier
    }

    private fun updateUfos(dt: Float, slow: Float) {
        val tier = angerTier
        if (tier > 0 && state == GameState.PLAYING) {
            ufoTimer -= dt
            if (ufoTimer <= 0f && ufos.size < tier) {
                ufoTimer = (5.5f - tier * 1.1f).coerceAtLeast(1.6f) + rng.nextFloat() * 3f
                val dir = if (rng.nextBoolean()) 1f else -1f
                ufos.add(Ufo(-dir * (halfW + 3f), 7f + rng.nextFloat() * 3f, dir))
                host.sfx(Sfx.UFO)
                if (voiceOn && rng.nextFloat() < 0.4f) host.say("ufo")
            }
        }
        var i = ufos.size - 1
        while (i >= 0) {
            val u = ufos[i]
            u.t += dt
            u.x += u.dir * (4.5f + tier) * slow * dt
            u.y += sin(u.t * 2f) * 1.2f * dt
            u.dropT -= dt * slow
            if (u.dropT <= 0f && state == GameState.PLAYING && readyT <= 0f) {
                u.dropT = (1.6f - tier * 0.2f).coerceAtLeast(0.7f)
                // aim a little ahead of the rover
                bombs.add(Bomb(u.x, u.y - 0.8f, -2f))
                host.sfx(Sfx.BOMB_DROP, 1f, 0.6f)
            }
            if (u.x * u.dir > halfW + 3.5f) ufos.removeAt(i)
            i--
        }
    }

    private fun updateBombs(dt: Float, slow: Float) {
        var i = bombs.size - 1
        while (i >= 0) {
            val b = bombs[i]
            b.vy -= 14f * slow * dt
            b.y += b.vy * slow * dt
            b.x -= speed * dt * slow * 0.15f // slight drift with the world
            if (b.y <= 0f) {
                // impact: cracks the ground into a fresh (always-jumpable) crater —
                // but never directly under the rover, where a pit would be an
                // undodgeable instant kill. Close hits stay pure blast.
                explode(b.x, 0.2f, 0.03f, 26, 5f)
                host.sfx(Sfx.BOMB_HIT)
                if (abs(b.x - roverScreenX) > 3f) {
                    obstacles.add(Obstacle(ObType.CRATER, scroll + b.x, 1.3f))
                }
                bombs.removeAt(i); i--; continue
            }
            // vs rover
            if (roverAlive && invuln <= 0f && abs(b.x - roverScreenX) < 1.2f && abs(b.y - (roverY + 0.8f)) < 1.1f) {
                bombs.removeAt(i)
                crash("bomb")
                return
            }
            i--
        }
    }

    // ------------------------------------------------------------- scoring

    private fun gameOver() {
        roverAlive = false
        state = GameState.GAME_OVER
        vowSaid = false
        vowT = 4.5f
        host.stopEngineLoop()
        host.sfx(Sfx.GAMEOVER)
        store.setHighScore(mode.ordinal, score)
        if (score >= highScore && score > 0) {
            highScore = score
            flash("NEW HIGH SCORE!", 3.5f)
            host.sfx(Sfx.HISCORE)
            if (voiceOn) host.say("hiscore", urgent = true)
        } else if (voiceOn) host.say("game_over", urgent = true)
    }

    private fun addScore(n: Int) {
        score += n
        checkExtraLife()
        if (score > highScore) { highScore = score; store.setHighScore(mode.ordinal, score) }
    }

    private fun checkExtraLife() {
        while (score >= nextLifeAt) {
            nextLifeAt += EXTRA_LIFE_EVERY
            lives++
            flash("SPARE RIG!", 1.8f)
            host.sfx(Sfx.LIFE)
            if (voiceOn) host.say("one_up")
        }
    }

    // ----------------------------------------------------------- particles

    private fun dustPuff() {
        val p = pool.removeFirstOrNull() ?: Particle()
        p.x = roverScreenX - 1.5f + rng.nextFloat(); p.y = 0.1f; p.z = 0f
        p.vx = -1.5f - rng.nextFloat() * 2f; p.vy = 0.5f + rng.nextFloat() * 2f; p.vz = 0f
        p.life = 0.4f + rng.nextFloat() * 0.4f; p.maxLife = p.life
        p.hue = 0.09f
        particles.add(p)
    }

    private fun oreBurst(sx: Float, worldX: Float) {
        repeat(16) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = sx; p.y = 0.4f; p.z = 0f
            val a = rng.nextFloat() * 6.2832f; val sp = rng.nextFloat() * 4f
            p.vx = cos(a) * sp; p.vy = 1f + abs(sin(a)) * 4f; p.vz = 0f
            p.life = 0.5f + rng.nextFloat() * 0.4f; p.maxLife = p.life
            p.hue = if (mode == Mode.REMIX) (0.45f + rng.nextFloat() * 0.35f) else 0.15f
            particles.add(p)
        }
    }

    private fun explode(x: Float, y: Float, hue: Float, count: Int, power: Float) {
        repeat(count) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = x; p.y = y; p.z = 0f
            val a = rng.nextFloat() * 6.2832f
            val sp = rng.nextFloat() * power
            p.vx = cos(a) * sp; p.vy = abs(sin(a)) * power * 0.8f + 1f; p.vz = 0f
            p.life = 0.5f + rng.nextFloat() * 0.6f; p.maxLife = p.life
            p.hue = (hue + rng.nextFloat() * 0.15f) % 1f
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); pool.addLast(p) }
            else {
                p.vy -= 9f * dt
                p.x += p.vx * dt
                p.y = maxOf(0f, p.y + p.vy * dt)
                p.vx *= 0.98f
            }
            i--
        }
    }
}
