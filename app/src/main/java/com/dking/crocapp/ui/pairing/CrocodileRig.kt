package com.dking.crocapp.ui.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.sin

/**
 * Programmatic control surface + joint state for the rig [CrocodileCanvas]
 * draws. This class holds state only; it never draws anything itself, so it
 * has no Compose UI dependency beyond the state types.
 *
 * Hierarchy:
 * ```
 * Crocodile
 * ├── Body                         (root; fixed — never moves)
 * ├── Head                         (headAngle, about the neck seam)
 * ├── Tail                         (tailSwayAngle, about the tail seam)
 * ├── FrontLeft  { hip → knee → ankle/foot }
 * ├── FrontRight { hip → knee → ankle/foot }
 * ├── RearLeft   { hip → knee → ankle/foot }
 * └── RearRight  { hip → knee → ankle/foot }
 * ```
 * Every hip and knee angle is an independent field — nothing forces the
 * upper and lower segments of a leg to move together; [CrocodileCanvas]
 * renders the knee as a child rotation nested inside the hip's rotation, so
 * they compose correctly (the shin stays attached at the knee no matter
 * what either angle is) without being the same value.
 *
 * While [walking] is true, [rememberCrocodileRig]'s driver overwrites all 8
 * hip/knee angles every frame using `sin(time * speed + phase) * amplitude`
 * — one sine per leg, four phase offsets, diagonal pairs sharing a phase.
 * Set `walking = false` (via [setWalking]) to stop the driver and pose any
 * joint by hand with its setter; the driver never touches [headAngle], so a
 * head-turn pose always stays under manual control even while walking.
 */
class CrocodileRig {

    // ---- global procedural controls ---------------------------------
    // Each is a *private* backing field plus a public get-only property of
    // the same base name, rather than `var x by ... ; private set` next to
    // a same-named setX() function. Kotlin auto-generates a setX(...) JVM
    // method for the latter pattern's `var`, which collides at the
    // bytecode level with an explicitly-declared setX() in the same class
    // — a real compile error, not just a style nit.
    //
    // These must be Compose state, not plain vars: setWalking() is called
    // from a *different* composable (whatever hosts this rig) than the one
    // holding `LaunchedEffect(rig.walking)` below. A plain var mutation
    // from outside would never trigger that composable to recompose, so
    // the key would never be seen as changed and the driver would never
    // actually start or stop.
    private var _walking by mutableStateOf(false)
    val walking: Boolean get() = _walking

    private var _walkSpeed by mutableFloatStateOf(1f)
    val walkSpeed: Float get() = _walkSpeed

    private var _legAmplitude by mutableFloatStateOf(20f)
    val legAmplitude: Float get() = _legAmplitude

    private var _kneeBendAmplitude by mutableFloatStateOf(34f)
    val kneeBendAmplitude: Float get() = _kneeBendAmplitude

    private var _direction by mutableFloatStateOf(1f) // 1f = forward, -1f = gait runs in reverse
    val direction: Float get() = _direction

    fun setWalking(enabled: Boolean) { _walking = enabled }
    fun setWalkSpeed(speed: Float) { _walkSpeed = speed }
    fun setLegAmplitude(degrees: Float) { _legAmplitude = degrees }
    fun setKneeBendAmplitude(degrees: Float) { _kneeBendAmplitude = degrees }
    fun setDirection(forward: Boolean) { _direction = if (forward) 1f else -1f }

    // ---- per-joint angles, degrees — each is its own Compose state so ---
    // ---- CrocodileCanvas's draw phase can read them independently -------
    private var _frontLeftHipAngle by mutableFloatStateOf(0f)
    val frontLeftHipAngle: Float get() = _frontLeftHipAngle
    private var _frontLeftKneeAngle by mutableFloatStateOf(0f)
    val frontLeftKneeAngle: Float get() = _frontLeftKneeAngle

    private var _frontRightHipAngle by mutableFloatStateOf(0f)
    val frontRightHipAngle: Float get() = _frontRightHipAngle
    private var _frontRightKneeAngle by mutableFloatStateOf(0f)
    val frontRightKneeAngle: Float get() = _frontRightKneeAngle

    private var _rearLeftHipAngle by mutableFloatStateOf(0f)
    val rearLeftHipAngle: Float get() = _rearLeftHipAngle
    private var _rearLeftKneeAngle by mutableFloatStateOf(0f)
    val rearLeftKneeAngle: Float get() = _rearLeftKneeAngle

    private var _rearRightHipAngle by mutableFloatStateOf(0f)
    val rearRightHipAngle: Float get() = _rearRightHipAngle
    private var _rearRightKneeAngle by mutableFloatStateOf(0f)
    val rearRightKneeAngle: Float get() = _rearRightKneeAngle

    private var _headAngle by mutableFloatStateOf(0f)
    val headAngle: Float get() = _headAngle
    private var _tailSwayAngle by mutableFloatStateOf(0f)
    val tailSwayAngle: Float get() = _tailSwayAngle
    private var _bodyBobOffset by mutableFloatStateOf(0f)
    val bodyBobOffset: Float get() = _bodyBobOffset

    fun setFrontLeftHipAngle(degrees: Float) { _frontLeftHipAngle = degrees }
    fun setFrontLeftKneeAngle(degrees: Float) { _frontLeftKneeAngle = degrees }
    fun setFrontRightHipAngle(degrees: Float) { _frontRightHipAngle = degrees }
    fun setFrontRightKneeAngle(degrees: Float) { _frontRightKneeAngle = degrees }
    fun setRearLeftHipAngle(degrees: Float) { _rearLeftHipAngle = degrees }
    fun setRearLeftKneeAngle(degrees: Float) { _rearLeftKneeAngle = degrees }
    fun setRearRightHipAngle(degrees: Float) { _rearRightHipAngle = degrees }
    fun setRearRightKneeAngle(degrees: Float) { _rearRightKneeAngle = degrees }
    fun setHeadAngle(degrees: Float) { _headAngle = degrees }
    fun setTailSwayAngle(degrees: Float) { _tailSwayAngle = degrees }
    fun setBodyBobOffset(pixels: Float) { _bodyBobOffset = pixels }

    /** Seconds, advances only while [walking]; persists across stop/start so a resumed walk doesn't jump. */
    internal var timeSeconds: Float = 0f

    /**
     * Stride cycles completed (fractional part = where in the stride we are).
     * Integrated frame-by-frame rather than computed as time × frequency, so
     * the stride rate can drift slightly without the legs ever jumping.
     */
    internal var gaitCycles: Float = 0f
}

/** Creates a [CrocodileRig] and starts the frame driver that runs while `rig.walking` is true. */
@Composable
fun rememberCrocodileRig(): CrocodileRig {
    val rig = remember { CrocodileRig() }

    LaunchedEffect(rig.walking) {
        if (!rig.walking) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    // Clamp so a dropped/paused frame can't teleport the legs.
                    val dtSeconds = ((nanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                    rig.timeSeconds += dtSeconds * rig.direction
                    // Real animals never hold a metronome-exact cadence: let the
                    // stride rate wander ±5% on two slow, unrelated rhythms.
                    val wander = 1f +
                        0.035f * sin(rig.timeSeconds * 0.71f) +
                        0.015f * sin(rig.timeSeconds * 1.93f + 1.3f)
                    rig.gaitCycles += dtSeconds * rig.direction * rig.walkSpeed * STRIDE_HZ * wander
                }
                lastFrameNanos = nanos
            }
            applyGait(rig)
        }
    }

    return rig
}

/**
 * Crocodilian "high walk", modelled on how crocodiles actually step rather
 * than on four synchronised sine waves.
 *
 * What made the old version look robotic, and what replaces it:
 *
 * 1. Footfall order. The old gait was a trot — diagonal legs moving in exact
 *    unison, 50% apart. Crocodiles in the high walk use a LATERAL-SEQUENCE
 *    walk with diagonal couplets: hind foot, then the same-side fore foot
 *    about a quarter-cycle later, then the other side's hind and fore.
 *    Footfall order: near-hind → near-fore → far-hind → far-fore, 25% apart.
 *
 * 2. Stance vs swing. A sine spends equal time going backward (stance, foot on
 *    the ground) and forward (swing, foot in the air), at a smoothly varying
 *    speed. A walking quadruped's foot is planted for most of the stride
 *    (duty factor ≈ 0.68 here) and sweeps back at a steady speed — the body
 *    gliding over it — then flicks forward quickly in the air.
 *
 * 3. Knee timing. The old knee folded whenever the leg was behind the hip —
 *    i.e. also during late stance, while the foot should be planted. Now the
 *    knee stays almost straight through stance (a slight give mid-stance as
 *    weight loads onto it) and lifts the foot only during swing.
 *
 * 4. Fore vs hind. Crocodilian hindlimbs are longer and do most of the
 *    propulsion, so the forelimbs take a shorter stride with less lift.
 *
 * 5. Body, tail, head. The trunk settles slightly at each hind footfall; the
 *    tail swings once per stride, lagging the hips (lateral undulation seen
 *    side-on); the head counter-moves to stay level — gaze stabilisation —
 *    plus a very slow scan so it never looks frozen.
 *
 * Note on names: with the head pointing right, the legs nearest the viewer
 * are anatomically the animal's RIGHT legs, though the art layers are named
 * "Left". The phases below are assigned by near/far, so the footfall order is
 * correct whatever the layers are called.
 */
private fun applyGait(rig: CrocodileRig) {
    // 0..1 scale from the host's amplitude ease-in/out, so every secondary
    // motion (bob, tail, head) settles to rest together with the legs.
    val effort = (rig.legAmplitude / BASE_HIP_AMPLITUDE).coerceIn(0f, 1.5f)
    val cycle = rig.gaitCycles

    fun phaseOf(offset: Float): Float {
        val x = (cycle + offset) % 1f
        return if (x < 0f) x + 1f else x
    }

    /** Hip angle in degrees for stride position [u]. Positive = foot behind the hip. */
    fun hip(u: Float, amp: Float): Float =
        if (u < DUTY_FACTOR) {
            // Stance: foot planted, leg sweeps back at a near-constant rate.
            // A light blend with a cosine softens the touchdown/lift-off ends.
            val v = u / DUTY_FACTOR
            val linear = -1f + 2f * v
            val eased = -kotlin.math.cos(PI.toFloat() * v)
            amp * (0.75f * linear + 0.25f * eased)
        } else {
            // Swing: quick forward recovery that accelerates then brakes.
            val s = (u - DUTY_FACTOR) / (1f - DUTY_FACTOR)
            amp * (1f - 2f * smootherstep(s))
        }

    /** Knee angle in degrees for stride position [u]. Positive = foot folds up/back. */
    fun knee(u: Float, lift: Float): Float =
        if (u < DUTY_FACTOR) {
            val v = u / DUTY_FACTOR
            KNEE_REST + 4f * effort * sin(PI.toFloat() * v) // slight give under load
        } else {
            val s = (u - DUTY_FACTOR) / (1f - DUTY_FACTOR)
            // Eases up from lift-off, peaks at mid-swing so the
            // foot clears the ground, then extends to reach for the next footfall.
            // (Every term starts and ends at zero speed — no twitch.)
            val e = smootherstep(s)
            val bump = sin(PI.toFloat() * (0.6f * s + 0.4f * e))
            KNEE_REST + lift * bump * bump
        }

    val hindAmp = rig.legAmplitude
    val foreAmp = rig.legAmplitude * 0.82f
    val hindLift = rig.kneeBendAmplitude
    val foreLift = rig.kneeBendAmplitude * 0.8f

    // Lateral-sequence footfalls, 25% of a stride apart.
    val nearHind = phaseOf(0.00f)
    val nearFore = phaseOf(-0.25f)
    val farHind = phaseOf(-0.50f)
    val farFore = phaseOf(-0.75f)

    rig.setRearLeftHipAngle(hip(nearHind, hindAmp))
    rig.setRearLeftKneeAngle(knee(nearHind, hindLift))
    rig.setFrontLeftHipAngle(hip(nearFore, foreAmp))
    rig.setFrontLeftKneeAngle(knee(nearFore, foreLift))
    rig.setRearRightHipAngle(hip(farHind, hindAmp))
    rig.setRearRightKneeAngle(knee(farHind, hindLift))
    rig.setFrontRightHipAngle(hip(farFore, foreAmp))
    rig.setFrontRightKneeAngle(knee(farFore, foreLift))

    val strideAngle = TWO_PI * cycle

    // Trunk dips at each hind footfall (twice per stride), never floats up.
    // Applied to the whole assembly, so nothing detaches at the hips.
    val bob = 1.3f * effort * (0.5f + 0.5f * kotlin.math.cos(2f * strideAngle))
    rig.setBodyBobOffset(bob)

    // Tail: one swing per stride, lagging the near hip by ~0.15 cycle,
    // plus a slow independent drift so successive strides aren't identical.
    rig.setTailSwayAngle(
        effort * (3.2f * sin(strideAngle - TWO_PI * 0.15f) +
            1.2f * sin(rig.timeSeconds * 0.37f))
    )

    // Head holds steady against the bob (gaze stabilisation) with a very slow scan.
    rig.setHeadAngle(
        effort * (-0.9f * kotlin.math.cos(2f * strideAngle) + 1.6f * sin(rig.timeSeconds * 0.23f + 0.8f))
    )
}

/** Stride frequency at walkSpeed = 1. Slow and deliberate, as a crocodile's high walk is. */
private const val STRIDE_HZ = 0.8f
private const val DUTY_FACTOR = 0.68f
private const val BASE_HIP_AMPLITUDE = 20f
private const val KNEE_REST = 6f

private fun smootherstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

private const val TWO_PI = (2.0 * PI).toFloat()

internal fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}
