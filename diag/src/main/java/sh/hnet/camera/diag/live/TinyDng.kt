package sh.hnet.camera.diag.live

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A plain DNG writer for what DngCreator refuses (a raw image larger than the sensor's normal pixel array, as the 50 MP maximum-resolution mode gives): one
 * uncompressed 16-bit CFA image with the camera's colour calibration (colour, calibration and forward matrices for its two illuminants), its black and white
 * levels and the white balance it saw. No lens shading correction: the corners come out darker than in the camera's own DNG.
 */
object TinyDng {
    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val SRATIONAL = 10

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** The 2 × 2 pattern of Android's SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR) in DNG's colours (0 red, 1 green, 2 blue). */
    fun bayer(cfa: Int): IntArray = when (cfa) {
        0 -> intArrayOf(0, 1, 1, 2)
        1 -> intArrayOf(1, 0, 2, 1)
        2 -> intArrayOf(1, 2, 0, 1)
        else -> intArrayOf(2, 1, 1, 0)
    }

    /** The same pattern with every pixel a 2 × 2 block of its colour: the quad Bayer of a 50 MP sensor, a 4 × 4 repeat. */
    fun quad(cfa: Int): IntArray = bayer(cfa).let { b -> IntArray(16) { i -> b[(i / 8) * 2 + (i % 4) / 2] } }

    /** [pixels]: 16-bit samples, row by row, in the colour [pattern] (2 × 2 or 4 × 4, row by row). */
    fun write(out: OutputStream, width: Int, height: Int, pixels: ByteBuffer, pattern: IntArray, chars: CameraCharacteristics, result: CaptureResult, exifOrientation: Int) {
        val entries = mutableListOf<Entry>()
        fun bytes(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        fun shorts(tag: Int, vararg values: Int) = entries.add(Entry(tag, SHORT, values.size, bytes(values.size * 2) { values.forEach { putShort(it.toShort()) } }))
        fun longs(tag: Int, vararg values: Int) = entries.add(Entry(tag, LONG, values.size, bytes(values.size * 4) { values.forEach { putInt(it) } }))
        fun byteValues(tag: Int, vararg values: Int) = entries.add(Entry(tag, BYTE, values.size, ByteArray(values.size) { values[it].toByte() }))
        fun ascii(tag: Int, text: String) = (text + "\u0000").toByteArray(Charsets.US_ASCII).let { entries.add(Entry(tag, ASCII, it.size, it)) }
        fun matrix(tag: Int, transform: ColorSpaceTransform?) {
            transform ?: return
            entries.add(Entry(tag, SRATIONAL, 9, bytes(9 * 8) {
                for (row in 0 until 3) for (column in 0 until 3) transform.getElement(column, row).let {
                    putInt(it.numerator)
                    putInt(it.denominator)
                }
            }))
        }

        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.toInt()
            ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern -> (0 until 4).map { pattern.getOffsetForIndex(it % 2, it / 2) }.average().toInt() } ?: 0
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val dimension = if (pattern.size == 16) 4 else 2
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"

        longs(254, 0)
        longs(256, width)
        longs(257, height)
        shorts(258, 16)
        shorts(259, 1)
        shorts(262, 32803)
        ascii(271, Build.MANUFACTURER)
        ascii(272, Build.MODEL)
        longs(273, 0) // the strip's offset, set below
        shorts(274, exifOrientation)
        shorts(277, 1)
        longs(278, height)
        longs(279, width * height * 2)
        shorts(284, 1)
        shorts(33421, dimension, dimension)
        byteValues(33422, *pattern)
        byteValues(50706, 1, 4, 0, 0)
        byteValues(50707, 1, 1, 0, 0)
        ascii(50708, model)
        shorts(50713, 2, 2)
        longs(50714, black, black, black, black)
        longs(50717, white)
        matrix(50721, chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
        matrix(50722, chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
        matrix(50723, chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        matrix(50724, chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
        result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { neutral ->
            entries.add(Entry(50728, RATIONAL, 3, bytes(3 * 8) { neutral.forEach { putInt(it.numerator); putInt(it.denominator) } }))
        }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { shorts(50778, it) }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { shorts(50779, it.toInt()) }
        matrix(50964, chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        matrix(50965, chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))

        // Header, then the directory, then the values that don't fit in an entry, then the image
        val directorySize = 2 + entries.size * 12 + 4
        var dataAt = 8 + directorySize
        val outside = entries.filter { it.data.size > 4 }
        val offsets = HashMap<Entry, Int>()
        for (entry in outside) {
            offsets[entry] = dataAt
            dataAt += entry.data.size + (entry.data.size and 1)
        }
        val imageAt = dataAt
        val head = ByteBuffer.allocate(imageAt).order(ByteOrder.LITTLE_ENDIAN)
        head.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        head.putShort(entries.size.toShort())
        for (entry in entries.sortedBy { it.tag }) {
            head.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            when {
                entry.tag == 273 -> head.putInt(imageAt)
                entry.data.size > 4 -> head.putInt(offsets.getValue(entry))
                else -> head.put(entry.data.copyOf(4))
            }
        }
        head.putInt(0)
        for (entry in outside) {
            head.position(offsets.getValue(entry))
            head.put(entry.data)
        }
        out.write(head.array())
        // The samples, little-endian like the header
        val samples = pixels.duplicate().order(ByteOrder.nativeOrder())
        samples.position(0)
        val chunk = ByteArray(1 shl 16)
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        while (samples.hasRemaining()) {
            val n = minOf(chunk.size, samples.remaining())
            samples.get(chunk, 0, n)
            if (!little) for (i in 0 until n step 2) chunk[i] = chunk[i + 1].also { chunk[i + 1] = chunk[i] }
            out.write(chunk, 0, n)
        }
    }
}
