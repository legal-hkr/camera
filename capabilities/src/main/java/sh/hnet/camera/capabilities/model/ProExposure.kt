package sh.hnet.camera.capabilities.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/** An exposure: the sensor's sensitivity and the time in nanoseconds. */
data class Exposure(val iso: Int, val timeNs: Long) {
    /** What reaches the picture: ISO × seconds. */
    val product: Double get() = iso * timeNs / 1e9
}

/**
 * Pro's exposure (LOGIKA §25, §26): the scales the rulers offer, the preview of a long time, and the camera's own metering for ISO or shutter priority, which
 * the camera can't do itself. Pure, so it is tested without Android.
 */
object ProExposure {
    /** ISO in the usual thirds of a stop. */
    private val ISO = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)

    /** Shutter times in the usual thirds of a stop, from 1/8000 s to 16 s (fractions as 1/n, longer ones in seconds). */
    private val SHUTTER: List<Long> = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)
        .map { (1e9 / it).roundToLong() } +
        listOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 16.0).map { (it * 1e9).roundToLong() }

    /** The longest shutter Pro offers (SPEC §11). */
    const val SHUTTER_MAX_NS = 16_000_000_000L

    /** The preview never takes longer than this: it would stand still (the camera's preview stalls during a long exposure). */
    const val PREVIEW_MAX_NS = 66_666_667L

    /** An automatic shutter chosen by the metering stays this short: a longer one would blur in the hand. */
    const val AUTO_SHUTTER_MAX_NS = 250_000_000L

    /** The ISO steps within the camera's range. */
    fun isoSteps(range: IntRange): List<Int> = ISO.filter { it in range }

    /** The shutter steps within the camera's range, at most [SHUTTER_MAX_NS]. */
    fun shutterSteps(range: LongRange): List<Long> = SHUTTER.filter { it >= range.first && it <= minOf(range.last, SHUTTER_MAX_NS) }

    /** The step nearest to [value] (a camera's own value shown on a ruler). */
    fun <T : Number> nearest(steps: List<T>, value: Double): T? = steps.minByOrNull { kotlin.math.abs(ln(it.toDouble() / value)) }

    /**
     * The preview's exposure for a photo's: the same brightness with the time held to [PREVIEW_MAX_NS] and the gain it loses added, as far as [isoMax] goes (the
     * preview then shows the photo darker than it will be, but moves).
     */
    fun preview(photo: Exposure, isoMax: Int): Exposure {
        if (photo.timeNs <= PREVIEW_MAX_NS) return photo
        val iso = (photo.iso * photo.timeNs.toDouble() / PREVIEW_MAX_NS).coerceAtMost(isoMax.toDouble()).toInt()
        return Exposure(maxOf(iso, photo.iso), PREVIEW_MAX_NS)
    }

    /** The picture's brightness made linear, from the mean of an 8-bit luma: SDR (a gamma of 2.2) or HLG (its inverse curve). */
    fun linear(luma: Double, hlg: Boolean): Double {
        val v = luma.coerceIn(0.0, 1.0)
        if (!hlg) return v.pow(2.2)
        return if (v <= 0.5) v * v / 3 else (exp((v - HLG_C) / HLG_A) + HLG_B) / 12
    }

    /** What a well exposed picture's linear brightness is when nothing better was learned (the camera's own exposure teaches it, [Metering.learn]). */
    fun defaultTarget(hlg: Boolean) = if (hlg) 0.05 else 0.18

    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073
}

/**
 * The metering of Pro's priority modes: the photo's ISO × time that brings the picture to the target brightness (with the EV), moved there by half the way per
 * step (the camera's own exposure moves as smoothly), and the other value of the pair derived from the one set by hand. The target is learned from the camera's
 * own automatic exposure while it runs.
 */
class Metering(private var target: Double) {
    /** The photo's ISO × time now (null until the first measurement). */
    var product: Double? = null
        private set

    /** The camera's own exposure is running: its picture's brightness is the target (smoothed). */
    fun learn(measured: Double) {
        if (measured <= 0) return
        target += (measured - target) * LEARN
    }

    /**
     * One step: the picture's linear brightness [measured] seen with the [preview]'s exposure; [evThirds] the compensation. Returns the photo's exposure for the
     * hand-set [iso] or [timeNs] (one of them), within the camera's [isoRange] and [timeRange] (the automatic time at most [ProExposure.AUTO_SHUTTER_MAX_NS]).
     */
    fun step(measured: Double, preview: Exposure, evThirds: Int, iso: Int?, timeNs: Long?, isoRange: IntRange, timeRange: LongRange): Exposure? {
        if (measured <= 0 || preview.product <= 0) return null
        val wanted = preview.product * target * 2.0.pow(evThirds / 3.0) / measured
        val now = product ?: wanted
        val next = exp(ln(now) + (ln(wanted) - ln(now)) * MOVE)
        product = next
        return when {
            iso != null -> Exposure(iso, (next / iso * 1e9).toLong().coerceIn(timeRange.first, minOf(timeRange.last, ProExposure.AUTO_SHUTTER_MAX_NS)))
            timeNs != null -> Exposure((next / (timeNs / 1e9)).toInt().coerceIn(isoRange), timeNs)
            else -> null
        }
    }

    /** With both values set by hand: how much brighter (+) or darker (−) than the metering the photo comes out, in EV; null before a measurement. */
    fun meter(measured: Double, preview: Exposure, photo: Exposure): Double? {
        if (measured <= 0 || preview.product <= 0) return null
        val wanted = preview.product * target / measured
        return ln(photo.product / wanted) / ln(2.0)
    }

    /** A new start: the next step jumps to its value (another camera, a value set again). */
    fun reset() {
        product = null
    }

    private companion object {
        const val LEARN = 0.1
        const val MOVE = 0.5
    }
}
