package sh.hnet.camera.media

import java.io.ByteArrayOutputStream

/**
 * Google's motion photo format (LOGIKA §3.3, a general standard any gallery reads): the short MP4 appended after everything the JPEG holds (an Ultra HDR photo's
 * gain map included), and the photo's XMP saying so – `GCamera:MotionPhoto`, its version, the still's time in the video, and a `MotionPhoto` item with the
 * video's length in the `Container:Directory`, next to the items already there (Primary, GainMap). Also Google's burst marks in the same XMP. Pure, so it is
 * tested without Android.
 */
object MotionPhotoWriter {
    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val MPF_ID = "MPF\u0000"
    private const val GCAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /** One marker segment of the JPEG's header: where it starts (at its 0xFF), its whole length, its marker. */
    private class Segment(val start: Int, val length: Int, val marker: Int)

    /** The photo with [video] appended; [presentationUs] is the still's time in the video. Returns null when the bytes aren't a JPEG it can read. */
    fun write(jpeg: ByteArray, video: ByteArray, presentationUs: Long): ByteArray? = runCatching {
        // A gain map described only by MPF (no XMP directory) would sit between the photo and the video without an item: no motion photo then
        rewrite(jpeg, video, needsDirectory = true) { xml -> if (xml != null) addTo(xml, video.size, presentationUs) else fresh(video.size, presentationUs) }
    }.getOrNull()

    /**
     * A frame of a burst marked as Google's camera marks it (LOGIKA §18, D12): `GCamera:BurstID`, the same for every frame of one burst, and
     * `GCamera:BurstPrimary` on the cover. Galleries that know it group the frames; the others show plain photos. Null when the bytes aren't a JPEG it can read.
     */
    fun markBurst(jpeg: ByteArray, burstId: String, primary: Boolean): ByteArray? = runCatching {
        val attributes = buildString {
            append("GCamera:BurstID=\"$burstId\"")
            if (primary) append(" GCamera:BurstPrimary=\"1\"")
        }
        rewrite(jpeg, null, needsDirectory = false) { xml -> if (xml != null) addAttributes(xml, attributes) else plain(attributes) }
    }.getOrNull()

    /**
     * The JPEG with its XMP replaced by [newXml] of the old one (null: there was none), the MPF offsets moved as the header grows, and [tail] appended.
     * [needsDirectory]: a JPEG with MPF but no XMP is refused (what follows the primary image can't be named without the XMP's directory).
     */
    private fun rewrite(jpeg: ByteArray, tail: ByteArray?, needsDirectory: Boolean, newXml: (String?) -> String): ByteArray? {
        val segments = segments(jpeg) ?: return null
        val xmp = segments.firstOrNull { it.marker == 0xE1 && startsWith(jpeg, it.start + 4, XMP_ID) }
        val mpf = segments.firstOrNull { it.marker == 0xE2 && startsWith(jpeg, it.start + 4, MPF_ID) }
        if (needsDirectory && xmp == null && mpf != null) return null
        val xml = if (xmp != null) String(jpeg, xmp.start + 4 + XMP_ID.length, xmp.length - 4 - XMP_ID.length, Charsets.UTF_8) else null
        val segment = xmpSegment(newXml(xml)) ?: return null
        // The new or grown XMP goes where the old one was, else after APP0 (JFIF) and the EXIF block
        val at = xmp?.start ?: segments.filter { it.marker == 0xE0 || (it.marker == 0xE1 && !startsWith(jpeg, it.start + 4, XMP_ID)) }.maxOfOrNull { it.start + it.length } ?: 2
        val removed = xmp?.length ?: 0
        val delta = segment.size - removed
        val out = jpeg.copyOf()
        // MPF's offsets count from its own header: only a change before the images and after the MPF block moves them
        if (mpf != null && mpf.start < at && delta != 0) shiftMpf(out, mpf, delta)
        val bytes = ByteArrayOutputStream(out.size + delta + (tail?.size ?: 0))
        bytes.write(out, 0, at)
        bytes.write(segment)
        bytes.write(out, at + removed, out.size - at - removed)
        tail?.let { bytes.write(it) }
        return bytes.toByteArray()
    }

    /** The header's segments up to the image data (SOS); null when it isn't a JPEG. */
    private fun segments(jpeg: ByteArray): List<Segment>? {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return null
        val list = mutableListOf<Segment>()
        var i = 2
        while (i + 4 <= jpeg.size) {
            if ((jpeg[i].toInt() and 0xFF) != 0xFF) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) return null
            list += Segment(i, length + 2, marker)
            i += length + 2
        }
        return list
    }

    private fun startsWith(bytes: ByteArray, at: Int, text: String): Boolean =
        at + text.length <= bytes.size && text.indices.all { bytes[at + it] == text[it].code.toByte() }

    private fun xmpSegment(xml: String): ByteArray? {
        val payload = XMP_ID.toByteArray(Charsets.ISO_8859_1) + xml.toByteArray(Charsets.UTF_8)
        val length = payload.size + 2
        if (length > 0xFFFF) return null
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun item(length: Int) = """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$length" Item:Padding="0"/></rdf:li>"""

    private fun attributes(presentationUs: Long) = """GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$presentationUs""""

    /** An XMP of its own for a photo that had none (a plain JPEG): the primary image and the video. */
    private fun fresh(length: Int, presentationUs: Long) = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about="" xmlns:GCamera="$GCAMERA" xmlns:Container="$CONTAINER" xmlns:Item="$ITEM" ${attributes(presentationUs)}><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>${item(length)}</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    /** An XMP of its own with only [attributes] on its description (a plain JPEG's burst frame). */
    private fun plain(attributes: String) = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about="" xmlns:GCamera="$GCAMERA" $attributes/></rdf:RDF></x:xmpmeta>"""

    /** The camera's XMP with GCamera [attributes] added to its first `rdf:Description` (and the namespace where it is missing). */
    internal fun addAttributes(xml: String, attributes: String): String {
        // Marked already (a frame saved again): not twice, which would make the XML invalid
        if ("GCamera:BurstID=" in xml) return xml
        val open = Regex("<rdf:Description\\b[^>]*?(/?)>").find(xml) ?: return plain(attributes)
        val selfClosing = open.groupValues[1] == "/"
        val tag = open.value.removeSuffix("/>").removeSuffix(">")
        val namespace = if ("xmlns:GCamera=" !in xml) " xmlns:GCamera=\"$GCAMERA\"" else ""
        return xml.replaceRange(open.range, "$tag$namespace $attributes${if (selfClosing) "/>" else ">"}")
    }

    /**
     * The camera's XMP with the motion photo added: the GCamera attributes and namespaces on the first `rdf:Description`, the video's item at the end of the
     * `Container:Directory` (one made, with the Primary item, when there is none).
     */
    internal fun addTo(xml: String, length: Int, presentationUs: Long): String {
        val open = Regex("<rdf:Description\\b[^>]*?(/?)>").find(xml) ?: return fresh(length, presentationUs)
        val selfClosing = open.groupValues[1] == "/"
        val tag = open.value.removeSuffix("/>").removeSuffix(">")
        val namespaces = buildString {
            if ("xmlns:GCamera=" !in xml) append(" xmlns:GCamera=\"$GCAMERA\"")
            if ("xmlns:Container=" !in xml) append(" xmlns:Container=\"$CONTAINER\"")
            if ("xmlns:Item=" !in xml) append(" xmlns:Item=\"$ITEM\"")
        }
        val newOpen = "$tag$namespaces ${attributes(presentationUs)}>"
        var result = if (selfClosing) {
            xml.replaceRange(open.range, "$newOpen</rdf:Description>")
        } else {
            xml.replaceRange(open.range, newOpen)
        }
        val seqEnd = Regex("</rdf:Seq>\\s*</Container:Directory>").find(result)
        result = if (seqEnd != null) {
            result.replaceRange(seqEnd.range.first, seqEnd.range.first, item(length))
        } else {
            val close = result.indexOf("</rdf:Description>")
            val directory = """<Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>${item(length)}</rdf:Seq></Container:Directory>"""
            result.replaceRange(close, close, directory)
        }
        return result
    }

    /** Moves the MPF entries' data offsets (the images after the primary one) by [delta] bytes. */
    private fun shiftMpf(jpeg: ByteArray, mpf: Segment, delta: Int) {
        val tiff = mpf.start + 4 + MPF_ID.length
        val little = jpeg[tiff].toInt() == 'I'.code
        fun u16(at: Int) = if (little) (jpeg[at].toInt() and 0xFF) or ((jpeg[at + 1].toInt() and 0xFF) shl 8) else ((jpeg[at].toInt() and 0xFF) shl 8) or (jpeg[at + 1].toInt() and 0xFF)
        fun u32(at: Int) = if (little) (u16(at).toLong() or (u16(at + 2).toLong() shl 16)) else ((u16(at).toLong() shl 16) or u16(at + 2).toLong())
        fun put32(at: Int, value: Long) {
            val v = value.toInt()
            if (little) {
                jpeg[at] = v.toByte(); jpeg[at + 1] = (v shr 8).toByte(); jpeg[at + 2] = (v shr 16).toByte(); jpeg[at + 3] = (v shr 24).toByte()
            } else {
                jpeg[at] = (v shr 24).toByte(); jpeg[at + 1] = (v shr 16).toByte(); jpeg[at + 2] = (v shr 8).toByte(); jpeg[at + 3] = v.toByte()
            }
        }
        val end = mpf.start + mpf.length
        fun inside(at: Int, size: Int) = at >= tiff && at + size <= end
        val ifd = tiff + u32(tiff + 4).toInt()
        require(inside(ifd, 2))
        val count = u16(ifd)
        for (e in 0 until count) {
            val entry = ifd + 2 + e * 12
            require(inside(entry, 12))
            if (u16(entry) != 0xB002) continue
            val bytes = u32(entry + 4).toInt()
            val data = tiff + u32(entry + 8).toInt()
            require(inside(data, bytes))
            for (image in 0 until bytes / 16) {
                val offsetAt = data + image * 16 + 8
                val offset = u32(offsetAt)
                if (offset != 0L) put32(offsetAt, offset + delta)
            }
        }
    }
}
