package sh.hnet.camera.media

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoWriterTest {
    private val ultraHdrXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="5"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun xmp(xml: String) = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xml.toByteArray())

    /** An MPF block (big-endian TIFF) with two entries: the primary at 0 and the gain map at [offset] from the TIFF header. */
    private fun mpf(offset: Int): ByteArray {
        val b = ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v shr 8); b.write(v) }
        fun u32(v: Int) { u16(v shr 16); u16(v and 0xFFFF) }
        b.write("MPF\u0000".toByteArray())
        b.write('M'.code); b.write('M'.code); u16(42); u32(8)
        // IFD at 8: one entry (MPEntry, 2 × 16 bytes at offset 26)
        u16(1)
        u16(0xB002); u16(7); u32(32); u32(26)
        u32(0)
        // Entries: primary (offset 0), gain map
        u32(0); u32(100); u32(0); u16(0); u16(0)
        u32(0); u32(5); u32(offset); u16(0); u16(0)
        return segment(0xE2, b.toByteArray())
    }

    private fun jpeg(vararg header: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        header.forEach { b.write(it) }
        b.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()))
        b.write("GAINMAP".toByteArray())
        return b.toByteArray()
    }

    private fun mpfOffset(bytes: ByteArray): Int {
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("MPF\u0000") + 4
        val entry = at + 26 + 16 + 8
        return ((bytes[entry].toInt() and 0xFF) shl 24) or ((bytes[entry + 1].toInt() and 0xFF) shl 16) or ((bytes[entry + 2].toInt() and 0xFF) shl 8) or (bytes[entry + 3].toInt() and 0xFF)
    }

    private val video = "MP4VIDEO".toByteArray()

    @Test fun ultraHdrGetsTheItemAndTheVideo() {
        val photo = jpeg(segment(0xE1, "Exif\u0000\u0000xx".toByteArray()), xmp(ultraHdrXmp), mpf(1000))
        val out = MotionPhotoWriter.write(photo, video, 1_500_000)
        assertNotNull(out)
        out!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("GCamera:MotionPhoto=\"1\"" in text)
        assertTrue("GCamera:MotionPhotoPresentationTimestampUs=\"1500000\"" in text)
        assertTrue("xmlns:GCamera=" in text)
        // The video's item after the gain map's, in the same directory
        assertTrue(text.indexOf("Item:Semantic=\"GainMap\"") < text.indexOf("Item:Semantic=\"MotionPhoto\""))
        assertTrue("Item:Length=\"${video.size}\"" in text)
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        assertTrue(text.contains("GAINMAPMP4VIDEO"))
        // MPF after the XMP moves with it: its offsets stay
        assertEquals(1000, mpfOffset(out))
    }

    @Test fun mpfBeforeTheXmpIsShifted() {
        val photo = jpeg(mpf(1000), xmp(ultraHdrXmp))
        val out = MotionPhotoWriter.write(photo, video, 0)!!
        val grown = out.size - video.size - photo.size
        assertTrue(grown > 0)
        assertEquals(1000 + grown, mpfOffset(out))
    }

    @Test fun plainJpegGetsAnXmpOfItsOwn() {
        val photo = jpeg(segment(0xE0, "JFIF\u0000".toByteArray()))
        val out = MotionPhotoWriter.write(photo, video, 42)!!
        val text = String(out, Charsets.ISO_8859_1)
        // After APP0
        assertTrue(text.indexOf("JFIF") < text.indexOf("http://ns.adobe.com/xap/1.0/"))
        assertTrue("Item:Semantic=\"Primary\"" in text && "Item:Semantic=\"MotionPhoto\"" in text)
    }

    @Test fun selfClosingDescription() {
        val xml = MotionPhotoWriter.addTo("""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="r"><rdf:Description rdf:about="" a="b"/></rdf:RDF></x:xmpmeta>""", 10, 5)
        assertTrue(xml.contains("<Container:Directory>"))
        assertTrue(xml.contains("</rdf:Description>"))
        assertTrue(xml.indexOf("<Container:Directory>") < xml.indexOf("</rdf:Description>"))
    }

    @Test fun notAJpeg() {
        assertNull(MotionPhotoWriter.write("nope".toByteArray(), video, 0))
    }

    @Test fun burstCoverOfUltraHdr() {
        val photo = jpeg(segment(0xE1, "Exif\u0000\u0000xx".toByteArray()), xmp(ultraHdrXmp), mpf(1000))
        val out = MotionPhotoWriter.markBurst(photo, "1234-abcd", primary = true)!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("GCamera:BurstID=\"1234-abcd\"" in text)
        assertTrue("GCamera:BurstPrimary=\"1\"" in text)
        assertTrue("xmlns:GCamera=" in text)
        // The gain map's directory and the data after the image stay
        assertTrue("Item:Semantic=\"GainMap\"" in text)
        assertTrue(text.endsWith("GAINMAP"))
        assertEquals(1000, mpfOffset(out))
    }

    @Test fun burstFrameOfPlainJpegWithMpf() {
        // A plain JPEG gets an XMP of its own (no directory needed for a burst frame); it goes right after SOI here, before the MPF, whose offsets stay
        val photo = jpeg(mpf(1000))
        val out = MotionPhotoWriter.markBurst(photo, "id", primary = false)!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("GCamera:BurstID=\"id\"" in text)
        assertTrue("BurstPrimary" !in text)
        assertEquals(1000, mpfOffset(out))
        assertNull(MotionPhotoWriter.markBurst("nope".toByteArray(), "id", primary = false))
    }

    @Test fun burstXmpAfterMpfShiftsIt() {
        // The XMP goes after APP0 and the EXIF block; an MPF before that point moves by the XMP's length
        val photo = jpeg(segment(0xE0, "JFIF\u0000".toByteArray()), mpf(1000), segment(0xE1, "Exif\u0000\u0000xx".toByteArray()))
        val out = MotionPhotoWriter.markBurst(photo, "id", primary = true)!!
        val grown = out.size - photo.size
        assertTrue(grown > 0)
        assertEquals(1000 + grown, mpfOffset(out))
    }

    @Test fun motionPhotoRefusesMpfWithoutXmp() {
        assertNull(MotionPhotoWriter.write(jpeg(mpf(1000)), video, 0))
    }
}
