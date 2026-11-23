package sh.hnet.camera.capabilities.model

import kotlin.math.abs

/** A size in pixels as the camera gives it (width ≥ height for a sensor's outputs). */
data class PixelSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val ratio: Double get() = width.toDouble() / height
    override fun toString() = "${width}x$height"
}

/** The shot's aspect ratio, landscape as the sensor sees it. */
enum class Aspect(val width: Int, val height: Int) {
    FOUR_THREE(4, 3),
    SIXTEEN_NINE(16, 9),
    SQUARE(1, 1);

    val ratio: Double get() = width.toDouble() / height
}

/** How the still is made: the size the camera captures, and the centered crop the app makes of it when the camera has no size of that aspect at full quality. */
data class StillPlan(val capture: PixelSize, val crop: PixelSize?)

object Sizes {
    /** Cameras' default sizes sit near a ratio, not on it (4080×3072, 3440×2448), so within 7 %. */
    private const val TOLERANCE = 0.07

    /**
     * The camera's own size for an aspect is used only when it keeps at least this share of the 4:3 size (its width for wide ones, its height for the square);
     * otherwise the shot is cut from the 4:3 size (Pixel 9 Pro offers 1920×1920 next to 4080×3072, so its square is cut).
     */
    private const val OWN_SIZE_MIN_SHARE = 0.9

    fun near(size: PixelSize, aspect: Aspect): Boolean = abs(size.ratio / aspect.ratio - 1) < TOLERANCE

    /**
     * The still for an aspect: the largest size near it (the camera's default for it) when it is about as large as the 4:3 size, else a centered cut of the 4:3
     * size (or of the largest size when there is no 4:3 one).
     */
    fun still(sizes: List<PixelSize>, aspect: Aspect): StillPlan? {
        val base = sizes.filter { near(it, Aspect.FOUR_THREE) }.maxByOrNull { it.area } ?: sizes.maxByOrNull { it.area } ?: return null
        val own = sizes.filter { near(it, aspect) }.maxByOrNull { it.area }
        val largeEnough = own != null && if (aspect == Aspect.SQUARE) own.height >= base.height * OWN_SIZE_MIN_SHARE else own.width >= base.width * OWN_SIZE_MIN_SHARE
        if (own != null && largeEnough) return StillPlan(own, null)
        return StillPlan(base, if (near(base, aspect)) null else cropTo(base, aspect))
    }

    /** The centered crop of a size to an aspect (even dimensions, as encoders like them). */
    fun cropTo(size: PixelSize, aspect: Aspect): PixelSize {
        val byWidth = (size.height * aspect.ratio).toInt()
        return if (byWidth <= size.width) PixelSize(byWidth and 1.inv(), size.height and 1.inv()) else PixelSize(size.width and 1.inv(), (size.width / aspect.ratio).toInt() and 1.inv())
    }

    /** The preview: the largest size near the aspect with the long side at most [maxLong] (1920: sharp enough on a phone, cheap to show); a square shot is previewed in 4:3 and masked by the UI. */
    fun preview(sizes: List<PixelSize>, aspect: Aspect, maxLong: Int = 1920): PixelSize? {
        val target = if (aspect == Aspect.SQUARE) Aspect.FOUR_THREE else aspect
        return sizes.filter { it.width <= maxLong && near(it, target) }.maxByOrNull { it.area }
    }

    /** The small analysis stream (QR codes, the finder glass): the smallest 4:3 size at least [minWidth] wide, else the smallest that wide. */
    fun analysis(sizes: List<PixelSize>, minWidth: Int = 320): PixelSize? =
        sizes.filter { it.width >= minWidth && near(it, Aspect.FOUR_THREE) }.minByOrNull { it.area } ?: sizes.filter { it.width >= minWidth }.minByOrNull { it.area }
}
