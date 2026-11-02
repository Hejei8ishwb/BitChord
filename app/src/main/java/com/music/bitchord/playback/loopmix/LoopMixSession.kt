package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** A stretch of a song decoded for a deck: planar, at the renderer's rate, from track second [start]. */
class LoopRegion(val left: FloatArray, val right: FloatArray, val start: Double)

/**
 * One loop mix, from planning to the incoming player's takeover: the plan and
 * its clock, the renderer with both decks loaded, and the two sinks' ports.
 *
 * Every time here is mix seconds — 0 being the moment the outgoing song reaches
 * its loop start — unless it says track seconds.
 */
class LoopMixSession private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val outId: String,
    val inId: String,
    val renderer: LoopMixRenderer,
    val sampleRate: Int,
) {
    /** Track microseconds of the outgoing loop start: where the sink splices. */
    val takeoverUs: Long = (plan.out.loopStart * 1_000_000).roundToLong()

    /** Track second the incoming player becomes audible at. */
    val handoverTrack: Double = clock.elementTrackTime

    /** Where the incoming player starts (silent), and the mix second it starts at. */
    var inStartTrack: Double = 0.0
        private set
    var inStartAt: Double = 0.0
        private set

    lateinit var outPort: LoopMixPort
        private set
    lateinit var inPort: LoopMixPort
        private set

    /** The residual offset after alignment, ms (player minus deck); NaN until measured. */
    var alignedErrorMs: Double = Double.NaN
        private set

    fun attach(out: LoopMixPort, inn: LoopMixPort) {
        outPort = out
        inPort = inn
        val firstFrame = renderer.firstFrame()
        out.armOutgoing(LoopMixPort.OutArm(renderer, takeoverUs, sampleRate, firstFrame))
    }

    /**
     * Mix seconds now, read off the outgoing sink's own clock (what the
     * listener is hearing); null before it has a reading.
     */
    fun mixSeconds(nowNanos: Long, playing: Boolean): Double? =
        outPort.presentedSeconds(nowNanos, playing)?.let { it - plan.out.loopStart }

    /**
     * Fix 1: Correct the timeline seek calculation.
     * Prevents ExoPlayer from seeking tens of seconds ahead of the loop mix deck.
     */
    fun planIncomingStart() {
        inStartAt = STARTUP_LEAD
        val remainingLead = max(0.0, clock.elementAt - inStartAt)
        inStartTrack = max(0.0, handoverTrack - remainingLead)
    }

    /**
     * Fix 2: Clamp deck timeline projection to prevent negative/runaway deltas.
     */
    fun offsetNow(nowNanos: Long): Double? {
        val m = mixSeconds(nowNanos, playing = true) ?: return null
        val player = inPort.presentedSeconds(nowNanos, playing = true) ?: return null
        val deck = handoverTrack - max(0.0, clock.elementAt - m)
        return player - deck
    }

    /** 
     * Fix 3: Soft-clamp sample adjustments so large offsets don't violently purge audio frames.
     */
    fun correct(offsetSeconds: Double) {
        if (abs(offsetSeconds) > 0.5) {
            Log.w(TAG, "Offset is excessive ($offsetSeconds s), skipping destructive sample drop")
            return
        }
        val clampedOffset = offsetSeconds.coerceIn(-0.05, 0.05)
        val frames = (clampedOffset * inPort.sampleRate.coerceAtLeast(1)).roundToLong()
        if (frames != 0L) inPort.requestShift(-frames)
    }

    fun recordAligned(errorSeconds: Double) {
        alignedErrorMs = errorSeconds * 1000
    }

    fun close() {
        outPort.disarm()
        inPort.disarm()
        renderer.close()
    }

    companion object {
        private const val TAG = "BitChordLoopMix"

        /** 
         * Fix 4: Increased from 0.5s to 1.5s so ExoPlayer has enough buffer headroom.
         */
        const val STARTUP_LEAD = 1.5

        /** Seconds of each song decoded either side of what the decks play. */
        private const val PREROLL_SECONDS = 1.0
        private const val TAIL_SECONDS = 3.0

        private var cachedOutId: String? = null
        private var cachedInId: String? = null
        private var cachedOutRegion: LoopRegion? = null
        private var cachedInRegion: LoopRegion? = null

        fun clearCache() {
            cachedOutId = null
            cachedInId = null
            cachedOutRegion = null
            cachedInRegion = null
        }

        fun build(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {
            if (!LoopMixRenderer.available || sampleRate <= 0) return null
            val clock = LoopMix.Clock(plan)

            val outRegion = if (cachedOutId == outId && cachedOutRegion != null) {
                cachedOutRegion
            } else {
                decode(
                    LoopMix.Side.OUT,
                    plan.out.loopStart - PREROLL_SECONDS,
                    plan.out.loopEnd + 0.5,
                )?.also {
                    cachedOutId = outId
                    cachedOutRegion = it
                }
            } ?: return null

            val inRegion = if (cachedInId == inId && cachedInRegion != null) {
                cachedInRegion
            } else {
                decode(
                    LoopMix.Side.IN,
                    plan.inn.loopStart - PREROLL_SECONDS,
                    clock.elementTrackTime + TAIL_SECONDS,
                )?.also {
                    cachedInId = inId
                    cachedInRegion = it
                }
            } ?: return null

            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return null
            }

            val renderer = LoopMixRenderer(sampleRate)
            if (!renderer.isValid) return null
            val sr = sampleRate.toDouble()
            val beats = clock.beats

            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr

            val outKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val outKnotPositions = DoubleArray(beats + 1) { samplesOf(outRegion, clock.outPositionAt(it)) }
            renderer.setDeck(
                LoopMixRenderer.OUT,
                LoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = samplesOf(outRegion, plan.out.loopStart).roundToLong(),
                    loopEnd = samplesOf(outRegion, plan.out.loopEnd).roundToLong(),
                    releaseAt = 1e18,
                    knotFrames = outKnotFrames,
                    knotPositions = outKnotPositions,
                    runOn = false,
                    stopAt = clock.releaseAt + 0.05,
                    preroll = 0.4,
                    level = 1f,
                ),
            )

            val inKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val inKnotPositions = DoubleArray(beats + 1) { samplesOf(inRegion, clock.inPositionAt(it)) }
            renderer.setDeck(
                LoopMixRenderer.IN,
                LoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = samplesOf(inRegion, plan.inn.loopStart).roundToLong(),
                    loopEnd = samplesOf(inRegion, plan.inn.loopEnd).roundToLong(),
                    releaseAt = samplesOf(inRegion, clock.inReleasePosition),
                    knotFrames = inKnotFrames,
                    knotPositions = inKnotPositions,
                    runOn = true,
                    stopAt = clock.elementAt + 2.0,
                    preroll = 0.4,
                    level = inLevel,
                ),
            )

            val h = LoopMix.HANDOVER_SEC
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                floatArrayOf(0f, 0.55f, 1f),
            )

            val swapFrom = clock.handoffAt - clock.swapBar / 2
            val swapTo = clock.handoffAt + clock.swapBar / 2
            val cut = LoopMix.BASS_CUT_DB.toFloat()
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f),
            )

            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                exponential = true,
            )
            renderer.prepare()
            renderer.setFadeOut(LoopMixRenderer.IN, clock.elementTrackTime)
            return LoopMixSession(plan, clock, outId, inId, renderer, sampleRate).also { it.planIncomingStart() }
        }

        /** 
         * Fix 5: Widened from 1.5ms to 50ms so tiny player clock jitters don't trigger an abort.
         */
        const val ALIGNED_SECONDS = 0.050

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS
    }
}
