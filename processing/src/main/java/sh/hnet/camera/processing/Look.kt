package sh.hnet.camera.processing

/**
 * The development's taste: every number the look is tuned by. The values are the computer prototype's, accepted by the owner on his scenes
 * (26 Nov 2026); the reasons behind each are in the prototype's notes. The GPU does the same steps with the same numbers, so a change is tried on the
 * computer first and copied here.
 */
data class Look(
    /** 3 × 3 (row by row) on linear sRGB after the calibration: only the white's tint the camera's own pictures have (a full fitted matrix turned a red book orange). */
    val color: FloatArray? = floatArrayOf(1.0513f, 0f, 0f, 0f, 0.9853f, 0f, 0f, 0f, 0.9949f),
    /** (CIELAB hue in degrees, its change in degrees) anchors, linear between them: oranges and browns about 7° toward yellow, reds 2°. */
    val hues: FloatArray? = floatArrayOf(0f, 0f, 15f, 2f, 45f, 7f, 75f, 4.4f, 105f, 0f, 360f, 0f),
    /** 0 = the camera's white balance, 1 = the picture's own (shades of gray); only where the two clearly disagree (a lamp's warmth kept). */
    val neutralize: Float = 0.5f,
    /** The white balance's tint: the green gain after the as-shot neutral. */
    val green: Float = 1f,
    /** The scene's log-average luminance after the shadows' gain (a mid-gray). */
    val key: Float = 0.3f,
    /** Scene brightness (stops, see [SceneStats.ev]) from which the key is [key]; one stop darker and below it is [keyDark]. */
    val keyEv: Float = 7.32f,
    val keyDark: Float = 0.22f,
    /** The black point's percentile one stop below [keyEv] and darker. */
    val blackDark: Float = 3f,
    /** The luminance kept below clipping by the highlights' gain. */
    val whitePercentile: Float = 99.7f,
    val white: Float = 0.75f,
    /** How much brighter the shadows may be lifted than the highlights' exposure. */
    val maxRatio: Float = 32f,
    /** How much a brighter exposure is preferred over the next darker one in the fusion. */
    val fuseBias: Float = 3f,
    /** Display level above which an exposure counts less (toward clipping), and how fast. */
    val fuseHigh: Float = 0.78f,
    val fuseSoft: Float = 0.05f,
    /** After the fusion: this luminance percentile goes to [stretchWhite], by at most [maxStretch]. */
    val stretchPercentile: Float = 99.5f,
    val stretchWhite: Float = 0.92f,
    val maxStretch: Float = 1.3f,
    /** This luminance percentile goes to black. */
    val blackPercentile: Float = 1f,
    /** The S curve's strength. */
    val contrast: Float = 0.35f,
    /** Lift of the mid-tones (v(1 − v)²: most around a third of the range, none at black and white). */
    val midtones: Float = 0.5f,
    /** Local contrast in the bright parts only (from [clarityLow] over 0.2 up), its base a guided filter of [clarityRadius] ‰ of the longer side. */
    val clarity: Float = 1f,
    val clarityLow: Float = 0.5f,
    val clarityRadius: Float = 20f,
    val clarityEdge: Float = 0.01f,
    val saturation: Float = 1.2f,
    /** The color's wide averaging (half-size pixels) and the luma steps that keep it apart. */
    val chromaRadius: Int = 24,
    val chromaEdge: Float = 0.06f,
    /** A local color this many times the noise away from the wide one is a real color and stays (fully at twice that). */
    val chromaKeep: Float = 3f,
    val lumaDenoise: Float = 1f,
    /** Relative noise from which the luma is smoothed fully, and the luma steps that stay in the noisiest parts. */
    val lumaNoise: Float = 0.15f,
    val lumaEdge: Float = 0.04f,
    val sharpen: Float = 1f,
    val sharpenRadius: Float = 1f,
) {
    // A data class of arrays: equality by content, as the look is compared and copied
    override fun equals(other: Any?): Boolean = other is Look && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()

    override fun toString(): String = listOf(
        color?.contentToString(), hues?.contentToString(), neutralize, green, key, keyEv, keyDark, blackDark, whitePercentile, white, maxRatio, fuseBias, fuseHigh,
        fuseSoft, stretchPercentile, stretchWhite, maxStretch, blackPercentile, contrast, midtones, clarity, clarityLow, clarityRadius, clarityEdge, saturation,
        chromaRadius, chromaEdge, chromaKeep, lumaDenoise, lumaNoise, lumaEdge, sharpen, sharpenRadius,
    ).joinToString(prefix = "Look(", postfix = ")")
}
