# TapMiner

**PART 1 — BEFORE HE SWEPT, HE CONQUERED.**

The prequel to TapMeteors and TapInvaders. Before he was the galaxy's least
appreciated space sweeper, he was the galaxy's least appreciated **indentured
mining intern** — young, hopelessly optimistic, and forty years from paying
off his contract. His first assignment: colonize the moon by strip-mining it,
and in the process thoroughly enrage the things that were already living here.

A synthwave neon-vector take on the 1982 lunar side-scroller for the **RayNeo
X3 Pro** AR glasses — OpenGL ES 3.0, additive lines on black (transparent on
the waveguide), parallax mountains and an Earthrise floating far back,
rendered side-by-side per eye.

## Controls — two gestures, no settings menu

The rover holds a fixed spot on the left while the mare scrolls past. You only
ever:

| Gesture | Action |
|---|---|
| **Tap** | Hop the rover — a big, floaty low-gravity bound over craters, boulders, spires, and mines. |
| **Swipe forward / back** | Shift **up / down a gear** (four gears). Faster = more distance and score, less time to read what's coming — and the engine literally revs with the shift. |
| Swipe up / down | Title: choose mode. Game over: back to mode select. |

There is no gun. You survive by *timing* and *gearing*, not shooting.

## The job

- **Mine by driving over ore** at ground level — glowing crystal clusters
  you scoop automatically. But hopping flies you clean over them, so every
  jump you take to dodge a hazard is ore you leave behind. Stay low to get
  rich; get rich and you die. Choose.
- **Every crystal enrages the locals.** A FURY meter climbs as you mine.
  Cross its thresholds and the moon's inhabitants escalate: first a lone
  saucer, then flights of them strafing overhead and **dropping bombs that
  blow fresh craters into your path**, then ground-shaking, screen-rattling
  wrath. Mining is the whole point and the whole problem.
- **Colonize sector by sector.** A progress bar tracks each sector; reach the
  end to stake an outpost, then the next stretch starts angrier. Crash into a
  rock, drop into a crater, or eat a bomb and you lose a rig — three to start,
  a spare every 4,000 points.

## Two shifts

- **CLASSIC** — the old shift: amber regolith, muted mono palette, no gadgets,
  no backtalk. Just you, the dust, and the drop.
- **REMIX** — the neon shift: hue-cycling everything, particle showers, the
  young miner's running commentary (he has *opinions* about this job), and
  **gadgets that drop from ore**: Ore Magnet (scoop crystals even mid-hop),
  Hull Shield, Slow-Mo, and the Mega Drill (smash straight through boulders).

## Two voices

- **The miner** (remix) — young, chipper, already complaining: clock-in
  optimism, ore-scooping delight, increasingly alarmed notes as the locals
  turn on him, crash grumbles, a high-score line nobody will ever hear.
- **The natives** — an alien tongue all their own, heard on the **arcade
  attract screen** (their children scatter, jabbering, ahead of your rover)
  and on the **post-level coffee breaks** (they grumble about the sacred
  hill you just carted off).

Both are pre-generated with **fish.audio S2.1 Pro**
([free developer API](https://fish.audio/blog/s2-1-pro-free-api/)), each with
its own voice model — the miner
([`b5f4515fd395410b9ed3aef6fa51d9a0`](https://fish.audio/app/m/b5f4515fd395410b9ed3aef6fa51d9a0/))
and the natives
([`f48d143a59a946ab87c0130fd081f349`](https://fish.audio/app/m/f48d143a59a946ab87c0130fd081f349/)):

```bash
export FISH_API_KEY=...   # free at fish.audio
python3 tools/generate_tts.py    # writes app/src/main/assets/tts/<id>.mp3
./gradlew assembleDebug          # clips ship in the APK; no network at run time
```

Until the clips exist the app bakes an Android-TTS fallback **once** on first
launch (never at run time — no stutter), so the character works out of the box.

## Sound

All synthesized at first launch, zero audio binaries: a looping rover motor
whose **pitch revs with the gear**, the springy lunar hop, ore pings that
climb with your mining combo, the aliens' deepening fury drone, saucer
warbles, whistling bombs, crash, sector fanfares, 1UP, gadget chimes.

## Build & install

```bash
cd ~/Projects/TapMiner
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29, zero
dependencies, zero vendor AARs. Binocular SBS auto-enables on RayNeo hardware
(detected by manufacturer identity, never `Build.MODEL` — it reports
`ARGF20`). Audio and SoundPool run off the render thread; no speech is ever
synthesized at run time.
