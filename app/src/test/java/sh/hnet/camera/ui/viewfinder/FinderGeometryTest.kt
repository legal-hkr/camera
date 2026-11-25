package sh.hnet.camera.ui.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.camera.capabilities.model.Aspect

class FinderGeometryTest {
    // A 400 dp wide viewfinder with 720 dp for the picture (16:9 fits), and one with only 600 dp (16:9 is cut at its ends)
    private val tall = FinderGeometry(400f, 40f, 760f)
    private val short = FinderGeometry(400f, 40f, 640f)

    private fun assertRect(expected: FinderRect, actual: FinderRect) {
        assertEquals("left", expected.left, actual.left, 0.01f)
        assertEquals("top", expected.top, actual.top, 0.01f)
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    @Test
    fun fourThreeFillsTheWidthCentered() {
        val sensor = tall.sensor(Aspect.FOUR_THREE)
        assertRect(FinderRect(0f, 133.33f, 400f, 533.33f), sensor)
        assertRect(sensor, tall.content(sensor, Aspect.FOUR_THREE))
        assertRect(sensor, tall.photo(sensor, Aspect.FOUR_THREE))
    }

    @Test
    fun squareIsTheMiddleOfFourThree() {
        val sensor = tall.sensor(Aspect.FOUR_THREE)
        assertRect(FinderRect(0f, 200f, 400f, 400f), tall.photo(sensor, Aspect.SQUARE))
    }

    @Test
    fun sixteenNineFillsTheWidthWithTheSensorPastTheSides() {
        val sensor = tall.sensor(Aspect.SIXTEEN_NINE)
        assertRect(FinderRect(-66.67f, 44.44f, 533.33f, 711.11f), sensor)
        val stream = tall.content(sensor, Aspect.SIXTEEN_NINE)
        assertRect(FinderRect(0f, 44.44f, 400f, 711.11f), stream)
        assertRect(stream, tall.photo(sensor, Aspect.SIXTEEN_NINE))
    }

    @Test
    fun sixteenNineTallerThanTheAreaIsCutAtItsEnds() {
        val sensor = short.sensor(Aspect.SIXTEEN_NINE)
        assertRect(FinderRect(0f, -15.56f, 400f, 711.11f), short.content(sensor, Aspect.SIXTEEN_NINE))
        assertRect(FinderRect(0f, 40f, 400f, 600f), short.photo(sensor, Aspect.SIXTEEN_NINE))
    }

    @Test
    fun fourThreeTallerThanTheAreaFitsInside() {
        val low = FinderGeometry(400f, 40f, 540f)
        assertRect(FinderRect(12.5f, 40f, 375f, 500f), low.sensor(Aspect.FOUR_THREE))
    }

    @Test
    fun sixteenNinePhotoOverAFourThreeStreamIsItsMiddle() {
        // Night on a camera whose extension previews only in 4:3
        val sensor = tall.sensor(Aspect.FOUR_THREE)
        assertRect(FinderRect(50f, 133.33f, 300f, 533.33f), tall.photo(sensor, Aspect.SIXTEEN_NINE))
    }

    @Test
    fun oneStreamAtAnothersScale() {
        // A 4:3 picture at the 16:9 stream's scale is a third wider than the screen; 16:9 at 4:3's is three quarters of it
        val frozen = tall.content(tall.sensor(Aspect.SIXTEEN_NINE), Aspect.FOUR_THREE)
        assertEquals(400f * 4 / 3, frozen.width, 0.01f)
        val back = tall.content(tall.sensor(Aspect.FOUR_THREE), Aspect.SIXTEEN_NINE)
        assertEquals(300f, back.width, 0.01f)
    }

    @Test
    fun lerpGoesBetweenRectangles() {
        assertRect(FinderRect(5f, 10f, 15f, 20f), FinderRect(0f, 0f, 10f, 10f).lerp(FinderRect(10f, 20f, 20f, 30f), 0.5f))
    }
}
