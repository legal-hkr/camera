package sh.hnet.camera.capabilities.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStopsTest {
    // Pixel 9 Pro's back camera from the dumpsys of 23 Nov 2026: logical 0 over physical 2 (main), 5 (main crop), 4 (tele), 6 (tele crop), 3 (ultrawide), 9 (ultrawide crop)
    private val pixelLogical = LensOptics("0", 6.9, 9.792)
    private val pixelPhysical = listOf(
        LensOptics("2", 6.9, 9.792),
        LensOptics("5", 6.9, 4.896),
        LensOptics("4", 17.906, 5.6448),
        LensOptics("6", 17.906, 2.8224),
        LensOptics("3", 2.02, 5.6448),
        LensOptics("9", 2.02, 2.8224),
    )

    @Test fun pixel9ProBack() {
        val scale = ZoomStops.compute(pixelLogical, pixelPhysical, 0.50783783..30.0)
        assertEquals(listOf(0.50783783, 1.0, 2.0, 5.0), scale.stops.map { it.ratio })
        assertTrue(scale.stops.first().ultrawide)
        assertEquals(listOf(1.0, 2.0, 5.0), scale.group.map { it.ratio })
        assertEquals(5.0, scale.end, 0.0)
        assertTrue(scale.hasControl)
    }

    @Test fun pixel9ProUltrawideLenses() {
        assertEquals(setOf("3", "9"), ZoomStops.ultrawideIds(pixelLogical, pixelPhysical))
        assertEquals(emptySet<String>(), ZoomStops.ultrawideIds(pixelLogical, emptyList()))
    }

    @Test fun pixel9ProFrontHasNoControl() {
        // Logical 1 over 8 (main) and 7 (a wider mode of the same lens): 0.9 is no ultrawide
        val scale = ZoomStops.compute(LensOptics("1", 2.713, 4.816), listOf(LensOptics("8", 2.713, 4.816), LensOptics("7", 2.713, 5.376)), 0.89583337..10.0)
        assertEquals(listOf(1.0), scale.stops.map { it.ratio })
        assertFalse(scale.hasControl)
    }

    @Test fun singleLens() {
        val scale = ZoomStops.compute(LensOptics("0", 4.0, 6.0), emptyList(), 1.0..8.0)
        assertEquals(listOf(1.0), scale.stops.map { it.ratio })
        assertNull(scale.ultrawide)
    }

    @Test fun mainAndUltrawideOnly() {
        val scale = ZoomStops.compute(LensOptics("0", 5.0, 7.0), listOf(LensOptics("2", 5.0, 7.0), LensOptics("3", 1.8, 5.0)), 0.6..10.0)
        assertEquals(listOf(0.6, 1.0), scale.stops.map { it.ratio })
        assertEquals(1.0, scale.end, 0.0)
    }

    @Test fun hiddenLensesStillGiveTheUltrawide() {
        val scale = ZoomStops.compute(LensOptics("0", 5.0, 7.0), emptyList(), 0.6..10.0)
        assertEquals(listOf(0.6, 1.0), scale.stops.map { it.ratio })
        assertEquals(1.0, scale.end, 0.0)
    }

    @Test fun teleBetweenWholeNumbers() {
        assertEquals(5.0, ZoomStops.teleStop(4.5017), 0.0)
        assertEquals(3.0, ZoomStops.teleStop(2.98), 0.0)
        // 3 would leave the lens unused (the camera switches at 3.4), so the ratio itself, rounded up
        assertEquals(3.4, ZoomStops.teleStop(3.37), 1e-9)
    }
}

class SizesTest {
    // Pixel 9 Pro's JPEG_R sizes of the back camera (dumpsys, 23 Nov 2026)
    private val back = listOf(
        "4032x3024", "4000x3000", "4032x2268", "3840x2160", "4000x2000", "3264x2448", "3264x1836", "2560x1920", "2688x1512", "1920x1920", "2560x1280", "2048x1536",
        "1920x1440", "1920x1080", "1600x1200", "1920x960", "1792x1008", "1440x1080", "1344x1008", "1280x960", "1080x1080", "1280x720", "1024x768", "1024x576",
        "800x600", "720x480", "640x480", "640x360", "352x288", "320x240", "176x144", "4080x3072",
    ).map { s -> s.split('x').let { PixelSize(it[0].toInt(), it[1].toInt()) } }

    @Test fun stillsOfPixel9Pro() {
        assertEquals(StillPlan(PixelSize(4080, 3072), null), Sizes.still(back, Aspect.FOUR_THREE))
        assertEquals(StillPlan(PixelSize(4032, 2268), null), Sizes.still(back, Aspect.SIXTEEN_NINE))
        // The camera's own square is 1920×1920, so the square is cut from 4080×3072
        assertEquals(StillPlan(PixelSize(4080, 3072), PixelSize(3072, 3072)), Sizes.still(back, Aspect.SQUARE))
    }

    @Test fun previewAndAnalysis() {
        assertEquals(PixelSize(1920, 1440), Sizes.preview(back, Aspect.FOUR_THREE))
        assertEquals(PixelSize(1920, 1080), Sizes.preview(back, Aspect.SIXTEEN_NINE))
        assertEquals(PixelSize(1920, 1440), Sizes.preview(back, Aspect.SQUARE))
        assertEquals(PixelSize(320, 240), Sizes.analysis(back))
    }

    @Test fun cropIsCenteredAndEven() {
        assertEquals(PixelSize(4080, 2294), Sizes.cropTo(PixelSize(4080, 3072), Aspect.SIXTEEN_NINE))
        assertEquals(PixelSize(2448, 2448), Sizes.cropTo(PixelSize(3440, 2448), Aspect.SQUARE))
    }

    @Test fun smallOwnSizesAreCut() {
        val sizes = listOf(PixelSize(4000, 3000), PixelSize(1920, 1080))
        assertEquals(StillPlan(PixelSize(4000, 3000), PixelSize(4000, 2250)), Sizes.still(sizes, Aspect.SIXTEEN_NINE))
        // No 4:3 size at all: the largest one, cut
        assertEquals(StillPlan(PixelSize(4000, 2250), PixelSize(3000, 2250)), Sizes.still(listOf(PixelSize(4000, 2250)), Aspect.FOUR_THREE))
    }

    @Test fun frontOfPixel9Pro() {
        val front = listOf(PixelSize(3440, 2448), PixelSize(3264, 2448), PixelSize(3264, 1836), PixelSize(1920, 1920))
        assertEquals(PixelSize(3440, 2448), Sizes.still(front, Aspect.FOUR_THREE)!!.capture)
    }
}

class SessionLadderTest {
    @Test fun bestFirstThenAnalysisThenHdrPreviewThenUltraHdr() {
        val ladder = SessionLadder.photo(hlg = true, jpegR = true, wantMotion = true, wantAnalysis = true)
        assertEquals(PhotoSession(PictureRange.HLG, StillFormat.JPEG_R, motion = true, analysis = true), ladder.first())
        // Automatic motion photos: the analysis before the motion stream when both don't fit
        assertEquals(PhotoSession(PictureRange.HLG, StillFormat.JPEG_R, motion = false, analysis = true), ladder[1])
        assertEquals(PhotoSession(PictureRange.HLG, StillFormat.JPEG_R, motion = true, analysis = false), ladder[2])
        assertEquals(PhotoSession(PictureRange.HLG, StillFormat.JPEG_R, motion = false, analysis = false), ladder[3])
        assertEquals(PictureRange.SDR, ladder[4].range)
        assertEquals(StillFormat.JPEG_R, ladder[4].still)
        assertEquals(PhotoSession(PictureRange.HLG, StillFormat.JPEG_R, motion = true, analysis = false), SessionLadder.photo(hlg = true, jpegR = true, wantMotion = true, wantAnalysis = true, motionFirst = true)[1])
        assertEquals(StillFormat.JPEG, ladder.last().still)
        assertEquals(ladder.size, ladder.distinct().size)
    }

    @Test fun withoutHdr() {
        val ladder = SessionLadder.photo(hlg = false, jpegR = false, wantMotion = false, wantAnalysis = false)
        assertEquals(listOf(PhotoSession(PictureRange.SDR, StillFormat.JPEG, motion = false, analysis = false)), ladder)
    }

    @Test fun ultraHdrTurnedOff() {
        assertTrue(SessionLadder.photo(hlg = true, jpegR = true, wantMotion = false, wantAnalysis = false, wantUltraHdr = false).all { it.still == StillFormat.JPEG })
    }
}

class OrientationTest {
    @Test fun hysteresis() {
        assertEquals(0, Orientation.rotation(50, 0))
        assertEquals(0, Orientation.rotation(64, 0))
        assertEquals(90, Orientation.rotation(66, 0))
        assertEquals(90, Orientation.rotation(30, 90))
        assertEquals(0, Orientation.rotation(20, 90))
        assertEquals(270, Orientation.rotation(290, 0))
        assertEquals(0, Orientation.rotation(340, 270))
        assertEquals(180, Orientation.rotation(-1, 180))
    }

    @Test fun jpegOrientation() {
        // Back sensor mounted at 90°, front at 270° (Pixel)
        assertEquals(90, Orientation.jpeg(90, 0, front = false))
        assertEquals(180, Orientation.jpeg(90, 90, front = false))
        assertEquals(0, Orientation.jpeg(90, 270, front = false))
        assertEquals(270, Orientation.jpeg(270, 0, front = true))
        assertEquals(180, Orientation.jpeg(270, 90, front = true))
    }

    @Test fun viewAndSensorAreInverse() {
        for (o in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val p = Point(0.2, 0.7)
            val back = Orientation.sensorToView(Orientation.viewToSensor(p, o, front), o, front)
            assertEquals(p.x, back.x, 1e-9)
            assertEquals(p.y, back.y, 1e-9)
        }
        // Back camera at 90°: the top-right of the upright preview is the sensor's top-left
        assertEquals(Point(0.0, 0.0), Orientation.viewToSensor(Point(1.0, 0.0), 90, front = false))
    }

    @Test fun visiblePartAndMetering() {
        assertEquals(PixelRect(0, 0, 4080, 3072), Orientation.visible(4080, 3072, 4080.0 / 3072))
        // 16:9 from a 4:3 array: full width, the height cut
        assertEquals(PixelRect(0, 389, 4080, 2684), Orientation.visible(4080, 3072, 16.0 / 9))
        // The middle of the preview meters the middle of the array; a corner stays inside
        assertEquals(PixelRect(1836, 1332, 2244, 1740), Orientation.meteringRegion(Point(0.5, 0.5), 90, false, PixelRect(0, 0, 4080, 3072)))
        val corner = Orientation.meteringRegion(Point(0.0, 0.0), 90, false, PixelRect(0, 0, 4080, 3072))
        assertTrue(corner.left >= 0 && corner.top >= 0 && corner.right <= 4080 && corner.bottom <= 3072)
    }
}

class VideoLadderTest {
    private val sizes = listOf(PixelSize(3840, 2160), PixelSize(1920, 1080))
    private val everything = EncoderLimits { _, _, _ -> true }

    @Test fun hdrBeforeSize() {
        val ladder = SessionLadder.video(VideoChoice(), hlg = true, jpegR = true, sizes, setOf(30, 60), everything)
        assertEquals(VideoSession(PictureRange.HLG, SessionLadder.UHD, 30, StillFormat.JPEG_R), ladder.first())
        val firstSdr = ladder.indexOfFirst { it.range == PictureRange.SDR }
        val firstFhdHlg = ladder.indexOfFirst { it.range == PictureRange.HLG && it.size == SessionLadder.FHD }
        assertTrue(firstFhdHlg in 0 until firstSdr)
    }

    @Test fun sixtyIsSdrThenThirtyWithHdr() {
        val ladder = SessionLadder.video(VideoChoice(fps = 60), hlg = true, jpegR = false, sizes, setOf(30, 60), everything)
        assertEquals(VideoSession(PictureRange.SDR, SessionLadder.UHD, 60, StillFormat.JPEG), ladder.first())
        assertTrue(ladder.none { it.fps == 60 && it.range == PictureRange.HLG })
        assertTrue(ladder.any { it.fps == 30 && it.range == PictureRange.HLG })
    }

    @Test fun encoderWithoutHdrOr4k() {
        val ladder = SessionLadder.video(VideoChoice(), hlg = true, jpegR = true, sizes, setOf(30), EncoderLimits { size, _, hdr -> !hdr && size == SessionLadder.FHD })
        assertTrue(ladder.all { it.range == PictureRange.SDR && it.size == SessionLadder.FHD })
        assertFalse(ladder.isEmpty())
    }
}
