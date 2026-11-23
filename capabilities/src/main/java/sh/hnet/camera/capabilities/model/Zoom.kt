package sh.hnet.camera.capabilities.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A lens as its characteristics describe it: the focal length and the width of the sensor area it uses (both in mm). */
data class LensOptics(val id: String, val focalLength: Double, val sensorWidth: Double) {
    /** How wide it sees, as a number that grows with the field of view. */
    val view: Double get() = sensorWidth / focalLength
}

/** A zoom stop: the ratio set on the logical camera, and whether it is the separate ultrawide button. */
data class ZoomStop(val ratio: Double, val ultrawide: Boolean = false)

/** The zoom of one logical camera: its stops (native lenses only), and where the control ends. */
data class ZoomScale(val stops: List<ZoomStop>, val min: Double, val max: Double, val end: Double) {
    val ultrawide: ZoomStop? get() = stops.firstOrNull { it.ultrawide }
    /** The stops of the group from 1× up (the ultrawide is a button of its own). */
    val group: List<ZoomStop> get() = stops.filterNot { it.ultrawide }
    /** One lens and nothing to zoom to: the control is left out (front cameras, single-lens phones). */
    val hasControl: Boolean get() = stops.size > 1
}

object ZoomStops {
    /** A lens counts as the ultrawide below this ratio, and a tele above that one; in between it is the main lens (or another mode of it). */
    private const val ULTRAWIDE_BELOW = 0.8
    private const val TELE_ABOVE = 1.5

    /**
     * The stops from the logical camera's view and its physical lenses. The main lens is the one that sees like the logical camera; a lens with the main lens' focal
     * length and a smaller sensor area is a crop of the main sensor (Pixel 9 Pro: physical 5 at 2×) and is a stop only at 2×; crops of other lenses never are
     * (the camera doesn't pick them, and the tele's 50 MP mode breaks the camera service). A camera that shows apps no physical lenses (many phones hide them)
     * still has an ultrawide when its range starts well below 1×; a tele can't be told from digital zoom there, so the scale ends at 1×.
     */
    fun compute(logical: LensOptics, physical: List<LensOptics>, range: ClosedFloatingPointRange<Double>): ZoomScale {
        val lenses = physical.ifEmpty { listOf(logical) }
        val main = lenses.minBy { abs(it.view / logical.view - 1) }
        val stops = mutableListOf(ZoomStop(1.0))
        for (lens in lenses) {
            if (lens == main) continue
            val ratio = main.view / lens.view
            val sameOptics = lenses.any { other -> other != lens && abs(other.focalLength / lens.focalLength - 1) < 0.01 && other.sensorWidth > lens.sensorWidth }
            when {
                ratio < ULTRAWIDE_BELOW && !sameOptics && range.start < ULTRAWIDE_BELOW -> stops += ZoomStop(range.start, ultrawide = true)
                sameOptics -> if (abs(lens.focalLength / main.focalLength - 1) < 0.01 && abs(ratio - 2) < 0.1 && range.endInclusive >= 2) stops += ZoomStop(2.0)
                ratio > TELE_ABOVE -> stops += ZoomStop(teleStop(ratio).coerceAtMost(range.endInclusive))
            }
        }
        if (physical.isEmpty() && range.start < ULTRAWIDE_BELOW) stops += ZoomStop(range.start, ultrawide = true)
        val distinct = stops.sortedBy { it.ratio }.fold(mutableListOf<ZoomStop>()) { kept, stop ->
            if (kept.none { abs(it.ratio - stop.ratio) < 0.25 }) kept += stop
            kept
        }
        val end = distinct.maxOf { it.ratio }
        return ZoomScale(distinct, min = distinct.minOf { it.ratio }, max = range.endInclusive, end = end)
    }

    /**
     * The physical lenses that are the ultrawide (and its other modes, sharing its focal length): the camera switching to one of them at 1× or more is macro
     * (Pixel 9 Pro switches to physical 3 by itself close up, diag 23 Nov 2026).
     */
    fun ultrawideIds(logical: LensOptics, physical: List<LensOptics>): Set<String> {
        if (physical.isEmpty()) return emptySet()
        val main = physical.minBy { abs(it.view / logical.view - 1) }
        val wide = physical.filter { main.view / it.view < ULTRAWIDE_BELOW }
        return physical.filter { lens -> wide.any { abs(it.focalLength / lens.focalLength - 1) < 0.01 } }.map { it.id }.toSet()
    }

    /** Where a tele's stop sits: the whole number nearest its ratio (Pixel 9 Pro: 4.5 by the optics, 5× as the camera labels and switches it), unless that falls short of the lens, then the ratio itself to a tenth, rounded up. */
    fun teleStop(ratio: Double): Double {
        val whole = ratio.roundToInt().toDouble()
        return if (whole >= ratio - 0.05) whole else ceil(ratio * 10) / 10
    }
}
