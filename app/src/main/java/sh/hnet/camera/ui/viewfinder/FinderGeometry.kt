package sh.hnet.camera.ui.viewfinder

import sh.hnet.camera.capabilities.model.Aspect

/** A rectangle in dp from the viewfinder's top-left corner. */
data class FinderRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val bottom: Float get() = top + height

    /** The part of this rectangle inside [other] (an empty one where they don't meet). */
    fun inside(other: FinderRect): FinderRect {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(left + width, other.left + other.width)
        val b = minOf(bottom, other.bottom)
        return FinderRect(l, t, maxOf(0f, r - l), maxOf(0f, b - t))
    }

    /** The way from this rectangle to [to] at [fraction] (0 here, 1 there). */
    fun lerp(to: FinderRect, fraction: Float) = FinderRect(
        left + (to.left - left) * fraction,
        top + (to.top - top) * fraction,
        width + (to.width - width) * fraction,
        height + (to.height - height) * fraction,
    )
}

/**
 * Where the picture goes in the viewfinder (LOGIKA §23.2), held upright. Everything is placed from the sensor's whole 4:3 picture at the scale a stream is shown
 * at: a 4:3 stream is that picture fitted in the area, a 16:9 stream is its middle filling the area's width (the area cuts its ends), and a 1:1 photo is the
 * middle of the 4:3 picture. Two streams of one camera share the sensor's long side, so each is placed by the same rule.
 *
 * [width]: the viewfinder's width; [areaTop] and [areaBottom]: the area for the picture (below the status bar, down to the middle of the gap above the shutter).
 */
class FinderGeometry(private val width: Float, areaTop: Float, areaBottom: Float) {
    val area = FinderRect(0f, areaTop, width, areaBottom - areaTop)

    /** The sensor's whole picture at the scale [stream] (4:3 or 16:9) is shown at, centered in the area. */
    fun sensor(stream: Aspect): FinderRect {
        val height = if (stream == Aspect.SIXTEEN_NINE) width * SIXTEEN_NINE else minOf(width * FOUR_THREE, area.height)
        val sensorWidth = height / FOUR_THREE
        return FinderRect((width - sensorWidth) / 2, area.top + (area.height - height) / 2, sensorWidth, height)
    }

    /** A picture of [content] (a stream, or a frozen frame of one) within the [sensor]'s picture: its full height, its own width. */
    fun content(sensor: FinderRect, content: Aspect): FinderRect {
        val contentWidth = sensor.height / longSide(content)
        return FinderRect(sensor.left + (sensor.width - contentWidth) / 2, sensor.top, contentWidth, sensor.height)
    }

    /** The photo's frame for [aspect] within the [sensor]'s picture, as much of it as the area shows. */
    fun photo(sensor: FinderRect, aspect: Aspect): FinderRect {
        val frame = when (aspect) {
            Aspect.SQUARE -> FinderRect(sensor.left, sensor.top + (sensor.height - sensor.width) / 2, sensor.width, sensor.width)
            else -> content(sensor, aspect)
        }
        return frame.inside(area)
    }

    private companion object {
        const val FOUR_THREE = 4f / 3f
        const val SIXTEEN_NINE = 16f / 9f

        /** Upright, the long side over the short one; a square is cut from 4:3. */
        fun longSide(aspect: Aspect) = if (aspect == Aspect.SIXTEEN_NINE) SIXTEEN_NINE else FOUR_THREE
    }
}
