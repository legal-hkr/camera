package sh.hnet.camera.media

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.location.Location
import android.os.Build
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import sh.hnet.camera.camera.Raw50Shot

/**
 * The 50 MP DNG (LOGIKA §30.6, D18), which DngCreator refuses (an image larger than the sensor's normal pixel array): one uncompressed 16-bit CFA image,
 * the quad Bayer rearranged to plain Bayer on the way (in each 4 × 4 block columns 1 and 2, and rows 1 and 2, swapped), with the lens' color calibration
 * (color, calibration and forward matrices for its two illuminants), black and white levels, the white balance it saw, the lens shading map as four gain
 * maps (the corners no darker than in the camera's own DNG), the exposure, orientation, place, and the photo's aspect as the default crop.
 */
object DngWriter {
    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7
    private const val SRATIONAL = 10

    private class Entry(val tag: Int, val type: Int, val count: Int, var data: ByteArray)

    /** The 2 × 2 pattern of Android's SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR) in DNG's colors (0 red, 1 green, 2 blue). */
    fun bayer(cfa: Int): IntArray = when (cfa) {
        0 -> intArrayOf(0, 1, 1, 2)
        1 -> intArrayOf(1, 0, 2, 1)
        2 -> intArrayOf(1, 2, 0, 1)
        else -> intArrayOf(2, 1, 1, 0)
    }

    fun write(target: OutputStream, shot: Raw50Shot, takenAt: Long) {
        val chars = shot.characteristics
        val result = shot.result
        val width = shot.width
        val height = shot.height
        val entries = mutableListOf<Entry>()
        fun le(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        fun shorts(tag: Int, vararg values: Int) = entries.add(Entry(tag, SHORT, values.size, le(values.size * 2) { values.forEach { putShort(it.toShort()) } }))
        fun longs(tag: Int, vararg values: Int) = entries.add(Entry(tag, LONG, values.size, le(values.size * 4) { values.forEach { putInt(it) } }))
        fun bytes(tag: Int, vararg values: Int) = entries.add(Entry(tag, BYTE, values.size, ByteArray(values.size) { values[it].toByte() }))
        fun ascii(tag: Int, text: String) = (text + "\u0000").toByteArray(Charsets.US_ASCII).let { entries.add(Entry(tag, ASCII, it.size, it)) }
        fun rationals(tag: Int, vararg values: Pair<Long, Long>) = entries.add(Entry(tag, RATIONAL, values.size, le(values.size * 8) { values.forEach { putInt(it.first.toInt()); putInt(it.second.toInt()) } }))
        fun matrix(tag: Int, transform: ColorSpaceTransform?) {
            transform ?: return
            entries.add(Entry(tag, SRATIONAL, 9, le(9 * 8) {
                for (row in 0 until 3) for (column in 0 until 3) transform.getElement(column, row).let { putInt(it.numerator); putInt(it.denominator) }
            }))
        }

        // The picture's area on the sensor and the photo's framing within it (in the image's own coordinates: no ActiveArea is written)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.takeIf { it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height }
        val areaLeft = active?.left ?: 0
        val areaTop = active?.top ?: 0
        val areaWidth = active?.width() ?: width
        val areaHeight = active?.height() ?: height
        val crop = DngCrop.framing(areaLeft.toDouble(), areaTop.toDouble(), areaWidth.toDouble(), areaHeight.toDouble(), 1.0, shot.aspect)
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 2
        val pattern = bayer(cfa)
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()
            ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> (0 until 4).map { p.getOffsetForIndex(it % 2, it / 2) }.average() } ?: 64.0
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val model = Build.MODEL

        longs(254, 0)
        longs(256, width)
        longs(257, height)
        shorts(258, 16)
        shorts(259, 1)
        shorts(262, 32803)
        ascii(271, Build.MANUFACTURER)
        ascii(272, model)
        longs(273, 0) // the image's place, set below
        shorts(274, shot.exifOrientation)
        shorts(277, 1)
        longs(278, height)
        longs(279, width * height * 2)
        shorts(284, 1)
        ascii(306, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date(takenAt)))
        shorts(33421, 2, 2)
        bytes(33422, *pattern)
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { ns -> rationals(33434, (ns / 1000) to 1_000_000L) }
        result.get(CaptureResult.LENS_APERTURE)?.let { f -> rationals(33437, (f * 100).roundToLong() to 100L) }
        // The place's own directory, pointed at below
        if (shot.location != null) longs(34853, 0)
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { shorts(34855, it.coerceAtMost(65535)) }
        ascii(36867, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date(takenAt)))
        result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { f -> rationals(37386, (f * 100).roundToLong() to 100L) }
        bytes(50706, 1, 4, 0, 0)
        bytes(50707, 1, 1, 0, 0)
        ascii(50708, "${Build.MANUFACTURER} $model")
        bytes(50710, 0, 1, 2)
        shorts(50711, 1)
        shorts(50713, 1, 1)
        rationals(50714, (black * 100).roundToLong() to 100L)
        longs(50717, white)
        rationals(50718, 1L to 1L, 1L to 1L)
        longs(50719, crop[0].roundToInt(), crop[1].roundToInt())
        longs(50720, crop[2].roundToInt(), crop[3].roundToInt())
        matrix(50721, chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
        matrix(50722, chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
        matrix(50723, chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        matrix(50724, chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
        result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.takeIf { it.size == 3 }?.let { neutral ->
            entries.add(Entry(50728, RATIONAL, 3, le(3 * 8) { neutral.forEach { putInt(it.numerator); putInt(it.denominator) } }))
        }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { shorts(50778, it) }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { shorts(50779, it.toInt()) }
        matrix(50964, chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        matrix(50965, chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
        gainMaps(result, pattern, width, height)?.let { entries.add(Entry(51009, UNDEFINED, it.size, it)) }

        val gps = shot.location?.let { gpsEntries(it) }
        val main = entries.sortedBy { it.tag }
        // Layout: header, the main directory, the place's directory, the values too large for an entry, then the image (word-aligned)
        val mainSize = 2 + main.size * 12 + 4
        val gpsAt = 8 + mainSize
        val gpsSize = gps?.let { 2 + it.size * 12 + 4 } ?: 0
        val valuesAt = (gpsAt + gpsSize).toLong()
        val valueSize = (main + gps.orEmpty()).filter { it.data.size > 4 }.sumOf { it.data.size + (it.data.size and 1) }
        val imageAt = (valuesAt + valueSize + 3) / 4 * 4
        main.first { it.tag == 273 }.data = le(4) { putInt(imageAt.toInt()) }
        main.firstOrNull { it.tag == 34853 }?.data = le(4) { putInt(gpsAt) }

        val out = BufferedOutputStream(target, 1 shl 16)
        val values = java.io.ByteArrayOutputStream()
        fun directory(list: List<Entry>): ByteArray = le(2 + list.size * 12 + 4) {
            putShort(list.size.toShort())
            for (entry in list) {
                putShort(entry.tag.toShort())
                putShort(entry.type.toShort())
                putInt(entry.count)
                if (entry.data.size <= 4) {
                    put(entry.data)
                    repeat(4 - entry.data.size) { put(0) }
                } else {
                    putInt((valuesAt + values.size()).toInt())
                    values.write(entry.data)
                    if (entry.data.size and 1 == 1) values.write(0)
                }
            }
            putInt(0)
        }
        out.write(le(8) {
            put('I'.code.toByte())
            put('I'.code.toByte())
            putShort(42)
            putInt(8)
        })
        out.write(directory(main))
        gps?.let { out.write(directory(it.sortedBy { e -> e.tag })) }
        out.write(values.toByteArray())
        repeat((imageAt - valuesAt - values.size()).toInt()) { out.write(0) }
        pixels(out, shot.packed, width, height)
        out.flush()
    }

    /**
     * The image, unpacked from RAW10 (four pixels in five bytes) to 16 bits little-endian and rearranged from quad Bayer to Bayer, four rows at a time (no
     * copy of the whole 100 MB).
     */
    internal fun pixels(out: OutputStream, packed: ByteBuffer, width: Int, height: Int) {
        val rowBytes = width * 5 / 4
        val rows = Array(4) { ShortArray(width) }
        val packedRow = ByteArray(rowBytes)
        val line = ByteArray(width * 2)
        packed.position(0)
        var y = 0
        while (y < height) {
            val count = minOf(4, height - y)
            for (r in 0 until count) {
                packed.get(packedRow)
                val row = rows[r]
                for (group in 0 until width / 4) {
                    val at = group * 5
                    val low = packedRow[at + 4].toInt() and 0xFF
                    for (i in 0 until 4) row[group * 4 + i] = (((packedRow[at + i].toInt() and 0xFF) shl 2) or ((low shr (2 * i)) and 3)).toShort()
                }
                // Columns 1 and 2 of every block swapped
                var x = 0
                while (x + 3 < width) {
                    val keep = row[x + 1]
                    row[x + 1] = row[x + 2]
                    row[x + 2] = keep
                    x += 4
                }
            }
            // Rows 1 and 2 of the block swapped
            val order = if (count == 4) intArrayOf(0, 2, 1, 3) else IntArray(count) { it }
            for (r in order) {
                val row = rows[r]
                for (i in 0 until width) {
                    val v = row[i].toInt()
                    line[i * 2] = v.toByte()
                    line[i * 2 + 1] = (v shr 8).toByte()
                }
                out.write(line)
            }
            y += count
        }
    }

    /**
     * The lens shading map as an OpcodeList2 of four GainMap opcodes (DNG 1.3, opcode 9), one per position of the 2 × 2 pattern, over the whole image;
     * opcode lists are big-endian whatever the file's order. Null without a map.
     */
    private fun gainMaps(result: CaptureResult, pattern: IntArray, width: Int, height: Int): ByteArray? {
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val rows = map.rowCount
        val columns = map.columnCount
        if (rows < 2 || columns < 2) return null
        val params = 76 + 4 * rows * columns
        val record = 16 + params
        val b = ByteBuffer.allocate(4 + 4 * record).order(ByteOrder.BIG_ENDIAN)
        b.putInt(4)
        for (position in 0 until 4) {
            val row = position / 2
            val column = position % 2
            // The map's channel of that position's color: R, the green of the even rows, the green of the odd ones, B
            val channel = when (pattern[position]) {
                0 -> RggbChannelVector.RED
                2 -> RggbChannelVector.BLUE
                else -> if (row == 0) RggbChannelVector.GREEN_EVEN else RggbChannelVector.GREEN_ODD
            }
            b.putInt(9)
            b.put(1).put(3).put(0).put(0)
            // Optional: a reader without it still shows the picture
            b.putInt(1)
            b.putInt(params)
            b.putInt(row)
            b.putInt(column)
            b.putInt(height)
            b.putInt(width)
            b.putInt(0)
            b.putInt(1)
            b.putInt(2)
            b.putInt(2)
            b.putInt(rows)
            b.putInt(columns)
            b.putDouble(1.0 / (rows - 1))
            b.putDouble(1.0 / (columns - 1))
            b.putDouble(0.0)
            b.putDouble(0.0)
            b.putInt(1)
            for (r in 0 until rows) for (c in 0 until columns) b.putFloat(map.getGainFactor(channel, c, r))
        }
        return b.array()
    }

    /** The place's directory (GPS IFD): latitude, longitude and altitude as degrees, minutes and seconds. */
    private fun gpsEntries(location: Location): List<Entry> {
        fun le(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        fun dms(value: Double): ByteArray {
            val v = abs(value)
            val degrees = v.toInt()
            val minutes = ((v - degrees) * 60).toInt()
            val seconds = ((v - degrees) * 3600 - minutes * 60) * 10000
            return le(24) {
                putInt(degrees)
                putInt(1)
                putInt(minutes)
                putInt(1)
                putInt(seconds.roundToInt())
                putInt(10000)
            }
        }
        fun ascii(text: String) = (text + "\u0000").toByteArray(Charsets.US_ASCII)
        return buildList {
            add(Entry(0, BYTE, 4, byteArrayOf(2, 3, 0, 0)))
            add(Entry(1, ASCII, 2, ascii(if (location.latitude >= 0) "N" else "S")))
            add(Entry(2, RATIONAL, 3, dms(location.latitude)))
            add(Entry(3, ASCII, 2, ascii(if (location.longitude >= 0) "E" else "W")))
            add(Entry(4, RATIONAL, 3, dms(location.longitude)))
            if (location.hasAltitude()) {
                add(Entry(5, BYTE, 1, byteArrayOf(if (location.altitude < 0) 1 else 0)))
                add(Entry(6, RATIONAL, 1, le(8) {
                    putInt((abs(location.altitude) * 100).roundToInt())
                    putInt(100)
                }))
            }
        }
    }
}
