package sh.hnet.camera.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * The photo's own framing written into its DNG (LOGIKA §30.5): the raw image is the lens' whole sensor, while the photo is cut by the zoom between the lenses'
 * stops and by its aspect (16:9, 1:1). DngCreator writes the sensor's whole active area as the default crop; here only those two tags' values change (their
 * size stays, the file isn't moved), so a raw converter shows the photo's framing and the rest of the sensor stays in the file.
 */
object DngCrop {
    /** Reads [length] bytes at [position], and writes bytes at a position: the file, or an array in the tests. */
    interface Access {
        fun read(position: Long, length: Int): ByteArray
        fun write(position: Long, bytes: ByteArray)
    }

    /**
     * Cuts the default crop to the lens' own crop [share] (left, top, right, bottom as shares of the area; where the camera says it), else to [zoom] (≥ 1: the
     * zoom over the lens' field of view) centered, then to the photo's aspect [aspect] (its long side over its short one: 4/3, 16/9, 1) within that. False
     * when the file isn't a TIFF with both tags in a type this writes (the DNG then keeps the whole area).
     */
    fun apply(file: Access, zoom: Double, aspect: Double, share: DoubleArray? = null): Boolean {
        val header = file.read(0, 8)
        val order = when (String(header, 0, 2, Charsets.US_ASCII)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> return false
        }
        val head = ByteBuffer.wrap(header).order(order)
        if (head.getShort(2).toInt() != 42) return false
        val ifd = head.getInt(4).toLong() and 0xFFFFFFFFL
        val count = ByteBuffer.wrap(file.read(ifd, 2)).order(order).short.toInt() and 0xFFFF
        val entries = ByteBuffer.wrap(file.read(ifd + 2, count * 12)).order(order)
        var origin: Tag? = null
        var size: Tag? = null
        for (i in 0 until count) {
            val at = i * 12
            val tag = entries.getShort(at).toInt() and 0xFFFF
            if (tag != ORIGIN && tag != SIZE) continue
            val type = entries.getShort(at + 2).toInt() and 0xFFFF
            val values = entries.getInt(at + 4)
            val width = TYPE_BYTES[type] ?: return false
            if (values != 2) return false
            // Two values fit in the entry itself up to 4 bytes; else the entry points to them
            val position = if (width * 2 <= 4) ifd + 2 + at + 8 else entries.getInt(at + 8).toLong() and 0xFFFFFFFFL
            val parsed = Tag(type, position)
            if (tag == ORIGIN) origin = parsed else size = parsed
        }
        val o = origin ?: return false
        val s = size ?: return false
        val (x0, y0) = values(file, o, order)
        val (w0, h0) = values(file, s, order)
        if (w0 <= 0 || h0 <= 0) return false
        val (x, y, w, h) = framing(x0, y0, w0, h0, zoom, aspect, share)
        put(file, o, order, x, y)
        put(file, s, order, w, h)
        return true
    }

    /**
     * The photo's framing within an area ([x0], [y0], [w0] × [h0], its long side across or down as it lies): the lens' crop [share] or the [zoom]'s middle
     * part, then the aspect within it; even sizes and offsets (the color filter's pattern keeps its start), inside the area. Left, top, width, height.
     */
    fun framing(x0: Double, y0: Double, w0: Double, h0: Double, zoom: Double, aspect: Double, share: DoubleArray? = null): DoubleArray {
        // The lens' crop, or the zoom's share of the whole area around its middle; then the aspect within it (the long side across or down, as it lies)
        val left = share?.let { x0 + it[0].coerceIn(0.0, 1.0) * w0 }
        val top = share?.let { y0 + it[1].coerceIn(0.0, 1.0) * h0 }
        val cropW = share?.let { (it[2].coerceIn(0.0, 1.0) - it[0].coerceIn(0.0, 1.0)) * w0 }?.takeIf { it > 0 } ?: (w0 / zoom.coerceAtLeast(1.0))
        val cropH = share?.let { (it[3].coerceIn(0.0, 1.0) - it[1].coerceIn(0.0, 1.0)) * h0 }?.takeIf { it > 0 } ?: (h0 / zoom.coerceAtLeast(1.0))
        val centerX = if (left != null && share[2] > share[0]) left + cropW / 2 else x0 + w0 / 2
        val centerY = if (top != null && share[3] > share[1]) top + cropH / 2 else y0 + h0 / 2
        var width = cropW
        var height = cropH
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (longSide / shortSide > aspect) {
            if (width >= height) width = height * aspect else height = width * aspect
        } else {
            if (width >= height) height = width / aspect else width = height / aspect
        }
        // Even sizes and offsets (the color filter's pattern starts as the whole area's did), within the area
        val w = even(width).coerceIn(2.0, w0)
        val h = even(height).coerceIn(2.0, h0)
        val x = x0 + even(centerX - w / 2 - x0).coerceIn(0.0, w0 - w)
        val y = y0 + even(centerY - h / 2 - y0).coerceIn(0.0, h0 - h)
        return doubleArrayOf(x, y, w, h)
    }

    private class Tag(val type: Int, val position: Long)

    private fun even(value: Double) = (value / 2).roundToLong() * 2.0

    private fun values(file: Access, tag: Tag, order: ByteOrder): Pair<Double, Double> {
        val width = TYPE_BYTES.getValue(tag.type)
        val b = ByteBuffer.wrap(file.read(tag.position, width * 2)).order(order)
        fun at(i: Int): Double = when (tag.type) {
            SHORT -> (b.getShort(i * 2).toInt() and 0xFFFF).toDouble()
            LONG -> (b.getInt(i * 4).toLong() and 0xFFFFFFFFL).toDouble()
            else -> (b.getInt(i * 8).toLong() and 0xFFFFFFFFL).toDouble() / (b.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL).coerceAtLeast(1)
        }
        return at(0) to at(1)
    }

    private fun put(file: Access, tag: Tag, order: ByteOrder, a: Double, b: Double) {
        val width = TYPE_BYTES.getValue(tag.type)
        val out = ByteBuffer.allocate(width * 2).order(order)
        for (value in listOf(a, b)) when (tag.type) {
            SHORT -> out.putShort(value.roundToLong().toInt().toShort())
            LONG -> out.putInt(value.roundToLong().toInt())
            else -> {
                out.putInt(value.roundToLong().toInt())
                out.putInt(1)
            }
        }
        file.write(tag.position, out.array())
    }

    private const val ORIGIN = 50719
    private const val SIZE = 50720
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    /** The bytes of one value of the types the crop may be written in. */
    private val TYPE_BYTES = mapOf(SHORT to 2, LONG to 4, RATIONAL to 8)
}
