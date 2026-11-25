package sh.hnet.camera.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngCropTest {
    /** A TIFF as DngCreator writes the crop: one IFD, DefaultCropOrigin and DefaultCropSize as two LONGs each, stored after the IFD. */
    private fun tiff(order: ByteOrder = ByteOrder.LITTLE_ENDIAN, type: Int = 4): ByteArray {
        val width = if (type == 3) 2 else if (type == 4) 4 else 8
        val b = ByteBuffer.allocate(200).order(order)
        b.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
        b.putShort(42)
        b.putInt(8)
        b.putShort(8, 2)
        val values = 8 + 2 + 2 * 12 + 4
        fun entry(at: Int, tag: Int, position: Int) {
            b.putShort(at, tag.toShort())
            b.putShort(at + 2, type.toShort())
            b.putInt(at + 4, 2)
            if (width * 2 <= 4) b.putInt(at + 8, 0) else b.putInt(at + 8, position)
        }
        entry(10, 50719, values)
        entry(22, 50720, values + 16)
        fun write(entryAt: Int, position: Int, a: Int, c: Int) {
            val at = if (width * 2 <= 4) entryAt + 8 else position
            when (type) {
                3 -> { b.putShort(at, a.toShort()); b.putShort(at + 2, c.toShort()) }
                4 -> { b.putInt(at, a); b.putInt(at + 4, c) }
                else -> { b.putInt(at, a); b.putInt(at + 4, 1); b.putInt(at + 8, c); b.putInt(at + 12, 1) }
            }
        }
        write(10, values, 8, 8)
        write(22, values + 16, 4064, 3056)
        return b.array()
    }

    private class Bytes(val data: ByteArray) : DngCrop.Access {
        override fun read(position: Long, length: Int) = data.copyOfRange(position.toInt(), position.toInt() + length)
        override fun write(position: Long, bytes: ByteArray) {
            bytes.copyInto(data, position.toInt())
        }
    }

    /** The crop as written: origin x, y and size w, h (two LONGs each). */
    private fun crop(data: ByteArray, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): List<Int> {
        val b = ByteBuffer.wrap(data).order(order)
        val values = 8 + 2 + 2 * 12 + 4
        return listOf(b.getInt(values), b.getInt(values + 4), b.getInt(values + 16), b.getInt(values + 20))
    }

    @Test fun fourThreeAtTheStopKeepsTheArea() {
        val file = Bytes(tiff())
        assertTrue(DngCrop.apply(file, 1.0, 4.0 / 3))
        val (x, y, w, h) = crop(file.data)
        assertEquals(4064, w)
        // 4064 × 3056 is a hair wider than 4:3: the height stays within two pixels
        assertTrue(h in 3046..3056)
        assertEquals(8, x)
        assertEquals(8 + (3056 - h) / 2, y)
    }

    @Test fun sixteenNineAndSquare() {
        val wide = Bytes(tiff())
        DngCrop.apply(wide, 1.0, 16.0 / 9)
        val (_, y, w, h) = crop(wide.data)
        assertEquals(4064, w)
        assertEquals(2286, h)
        // Centered, on an even row: (3056 − 2286) / 2 = 385 rounds to 386
        assertEquals(8 + 386, y)
        val square = Bytes(tiff())
        DngCrop.apply(square, 1.0, 1.0)
        val (x2, _, w2, h2) = crop(square.data)
        assertEquals(3056, w2)
        assertEquals(3056, h2)
        assertEquals(8 + 504, x2)
    }

    @Test fun zoomBetweenStopsCutsTheMiddle() {
        val file = Bytes(tiff())
        // 3× on the 2× lens: 1.5 over its stop
        DngCrop.apply(file, 1.5, 4.0 / 3)
        val (x, y, w, h) = crop(file.data)
        assertEquals(2710, w)
        assertTrue(h in 2030..2038)
        assertEquals(0, x % 2)
        assertEquals(0, y % 2)
        // Centered within a pixel or two
        assertTrue(kotlin.math.abs(x + w / 2 - (8 + 4064 / 2)) <= 2)
    }

    @Test fun bigEndianShortsAndRationals() {
        // SHORTs inline in the entries, RATIONALs after the IFD: the square of 3056 at x 512 either way
        val shorts = tiff(ByteOrder.BIG_ENDIAN, 3)
        assertTrue(DngCrop.apply(Bytes(shorts), 1.0, 1.0))
        val s = ByteBuffer.wrap(shorts).order(ByteOrder.BIG_ENDIAN)
        assertEquals(listOf(8 + 504, 8, 3056, 3056), listOf(s.getShort(18), s.getShort(20), s.getShort(30), s.getShort(32)).map { it.toInt() })
        val rationals = tiff(ByteOrder.BIG_ENDIAN, 5)
        assertTrue(DngCrop.apply(Bytes(rationals), 1.0, 1.0))
        val r = ByteBuffer.wrap(rationals).order(ByteOrder.BIG_ENDIAN)
        val at = 8 + 2 + 2 * 12 + 4
        assertEquals(listOf(8 + 504, 1, 8, 1, 3056, 1, 3056, 1), (0 until 8).map { r.getInt(at + it * 4) })
    }

    @Test fun theLensCropIsFollowedOffCenter() {
        // The camera's crop on the lens: its right half and middle rows (as a 2× crop shifted right), then 4:3 within it
        val file = Bytes(tiff())
        DngCrop.apply(file, 9.0, 4.0 / 3, doubleArrayOf(0.5, 0.25, 1.0, 0.75))
        val (x, y, w, h) = crop(file.data)
        assertEquals(2032, w)
        assertTrue(h in 1520..1528)
        assertEquals(8 + 2032, x)
        assertTrue(y in 8 + 762..8 + 768)
    }

    @Test fun notATiff() {
        assertFalse(DngCrop.apply(Bytes(ByteArray(64)), 1.0, 1.0))
    }
}
