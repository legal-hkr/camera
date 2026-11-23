package sh.hnet.camera.capabilities.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** A point as a share of a rectangle's width and height (0..1 each). */
data class Point(val x: Double, val y: Double)

/** A rectangle in the sensor's active array, in pixels. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object Orientation {
    /** How far the phone must turn past the 45° boundary before the controls turn with it, so they don't flicker when it is held at an angle. */
    const val HYSTERESIS = 20

    /**
     * The rotation (0, 90, 180, 270, clockwise from upright) for the angle `OrientationEventListener` reports, given the current one: a new quadrant is taken
     * only once the angle is within 45° minus the hysteresis of its middle. A flat phone (-1) keeps the current rotation.
     */
    fun rotation(angle: Int, current: Int): Int {
        if (angle < 0) return current
        val nearest = ((angle + 45) / 90 % 4) * 90
        if (nearest == current) return current
        val distance = abs(((angle - nearest + 540) % 360) - 180)
        return if (distance <= 45 - HYSTERESIS) nearest else current
    }

    /** The orientation to write into a photo (`JPEG_ORIENTATION`): the sensor's mounting plus how the phone is held, the other way round for a front camera. */
    fun jpeg(sensorOrientation: Int, deviceRotation: Int, front: Boolean): Int =
        if (front) (sensorOrientation - deviceRotation + 360) % 360 else (sensorOrientation + deviceRotation) % 360

    /**
     * A point of the upright preview (the activity is portrait only) in the sensor's own coordinates. The camera shows the sensor turned clockwise by its
     * orientation, and a front camera's preview mirrored, so this turns it back.
     */
    fun viewToSensor(view: Point, sensorOrientation: Int, front: Boolean): Point {
        val p = if (front) Point(1 - view.x, view.y) else view
        return when (sensorOrientation) {
            90 -> Point(p.y, 1 - p.x)
            180 -> Point(1 - p.x, 1 - p.y)
            270 -> Point(1 - p.y, p.x)
            else -> p
        }
    }

    /** The inverse of [viewToSensor]: where a point of the sensor (a face) is on the preview. */
    fun sensorToView(sensor: Point, sensorOrientation: Int, front: Boolean): Point {
        val p = when (sensorOrientation) {
            90 -> Point(1 - sensor.y, sensor.x)
            180 -> Point(1 - sensor.x, 1 - sensor.y)
            270 -> Point(sensor.y, 1 - sensor.x)
            else -> sensor
        }
        return if (front) Point(1 - p.x, p.y) else p
    }

    /**
     * The part of the active array the preview shows: centered and cut to the preview's aspect (landscape, as the sensor sees it). With `CONTROL_ZOOM_RATIO`
     * (Android 11+) the whole active array stands for the zoomed view in metering regions and face rectangles, so the zoom doesn't cut it further.
     */
    fun visible(activeWidth: Int, activeHeight: Int, previewRatio: Double): PixelRect {
        val arrayRatio = activeWidth.toDouble() / activeHeight
        var w = activeWidth.toDouble()
        var h = activeHeight.toDouble()
        if (previewRatio > arrayRatio) h = w / previewRatio else w = h * previewRatio
        val left = ((activeWidth - w) / 2).roundToInt()
        val top = ((activeHeight - h) / 2).roundToInt()
        return PixelRect(left, top, left + w.roundToInt(), top + h.roundToInt())
    }

    /** The metering region around a tapped point: a square a tenth of the visible width, kept inside the visible part. */
    fun meteringRegion(view: Point, sensorOrientation: Int, front: Boolean, visible: PixelRect): PixelRect {
        val s = viewToSensor(view, sensorOrientation, front)
        val half = visible.width / 20
        val cx = (visible.left + s.x * visible.width).roundToInt().coerceIn(visible.left + half, visible.right - half)
        val cy = (visible.top + s.y * visible.height).roundToInt().coerceIn(visible.top + half, visible.bottom - half)
        return PixelRect(cx - half, cy - half, cx + half, cy + half)
    }

    /** A rectangle of the active array (a face) as a share of the preview: its corners turned like [sensorToView]. */
    fun regionToView(rect: PixelRect, visible: PixelRect, sensorOrientation: Int, front: Boolean): Pair<Point, Point> {
        fun share(x: Int, y: Int) = Point((x - visible.left).toDouble() / visible.width, (y - visible.top).toDouble() / visible.height)
        val a = sensorToView(share(rect.left, rect.top), sensorOrientation, front)
        val b = sensorToView(share(rect.right, rect.bottom), sensorOrientation, front)
        return Point(minOf(a.x, b.x), minOf(a.y, b.y)) to Point(maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}
