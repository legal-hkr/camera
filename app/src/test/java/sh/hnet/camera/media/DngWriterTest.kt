package sh.hnet.camera.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class DngWriterTest {
    /** RAW10 as the camera packs it: four 10-bit pixels in five bytes, the high eight bits first, the four low pairs in the fifth byte. */
    private fun pack(values: IntArray, width: Int, height: Int): ByteBuffer {
        val out = ByteBuffer.allocate(width * height * 5 / 4)
        for (group in 0 until width * height / 4) {
            var low = 0
            for (i in 0 until 4) {
                val v = values[group * 4 + i]
                out.put((v shr 2).toByte())
                low = low or ((v and 3) shl (2 * i))
            }
            out.put(low.toByte())
        }
        out.flip()
        return out
    }

    @Test fun quadBayerBecomesBayer() {
        // One 4 × 4 block of quad Bayer: 2 × 2 of each color (G B / R G as the Pixel's GBRG), each pixel its own value to follow
        val width = 4
        val height = 4
        val quad = intArrayOf(
            100, 101, 200, 201,
            102, 103, 202, 203,
            300, 301, 400, 401,
            302, 303, 402, 403,
        )
        val bytes = ByteArrayOutputStream()
        DngWriter.pixels(bytes, pack(quad, width, height), width, height)
        val out = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pixels = IntArray(16) { out.get(it).toInt() }
        // Columns 1 and 2 swapped, rows 1 and 2 swapped: G B G B / R G R G / G B G B / R G R G
        assertEquals(
            listOf(
                100, 200, 101, 201,
                300, 400, 301, 401,
                102, 202, 103, 203,
                302, 402, 303, 403,
            ),
            pixels.toList(),
        )
    }

    @Test fun tenBitValuesSurvive() {
        val values = intArrayOf(0, 1, 2, 3, 1020, 1021, 1022, 1023)
        val bytes = ByteArrayOutputStream()
        // Two rows of four, no swaps below a whole block
        DngWriter.pixels(bytes, pack(values, 4, 2), 4, 2)
        val out = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(listOf(0, 2, 1, 3, 1020, 1022, 1021, 1023), List(8) { out.get(it).toInt() })
    }
}
