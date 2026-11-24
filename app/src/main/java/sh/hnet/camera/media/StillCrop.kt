package sh.hnet.camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.graphics.Matrix
import android.location.Location
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import sh.hnet.camera.capabilities.model.PixelSize

/**
 * Edits a still where the camera can't: cuts it to its aspect ratio when the camera has no size of it at full quality (a square on the Pixel 9 Pro, LOGIKA §3): the centered crop of the picture
 * and of its Ultra HDR gain map, encoded again (Android 14's `Bitmap.compress` writes the gain map), the camera's EXIF carried over.
 */
object StillCrop {
    private const val QUALITY = 95

    /** A photo encoded again, with its size. */
    class Edited(val bytes: ByteArray, val size: PixelSize)

    /**
     * The photo cut to [crop] (centered) and mirrored as the front camera's preview shows it ([mirror], Settings › Photos), the gain map with it. The mirror is
     * left to right as the photo is seen: across the stored rows when the EXIF turns the picture a quarter. The camera's file unchanged when it can't be decoded.
     */
    fun edit(bytes: ByteArray, crop: PixelSize?, mirror: Boolean): Edited? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        // The crop is the sensor's way round; a camera that turned the pixels itself (no EXIF turn) has them the other way
        val size = crop?.let { if ((source.width < source.height) != (it.width < it.height)) PixelSize(it.height, it.width) else it }
        val width = (size?.width ?: source.width).coerceAtMost(source.width)
        val height = (size?.height ?: source.height).coerceAtMost(source.height)
        val x = (source.width - width) / 2
        val y = (source.height - height) / 2
        val quarter = mirror && runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees % 180 != 0 }.getOrDefault(false)
        val flip = if (mirror) Matrix().apply { if (quarter) setScale(1f, -1f) else setScale(-1f, 1f) } else null
        val edited = Bitmap.createBitmap(source, x, y, width, height, flip, false)
        source.gainmap?.let { edited.gainmap = editGainmap(it, source, x, y, width, height, flip) }
        val out = ByteArrayOutputStream()
        edited.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        source.recycle()
        edited.recycle()
        return Edited(out.toByteArray(), PixelSize(width, height))
    }

    /** The gain map is smaller than the picture (a quarter on the Pixel): the same region at its scale, with the map's numbers kept. */
    private fun editGainmap(map: Gainmap, source: Bitmap, x: Int, y: Int, width: Int, height: Int, flip: Matrix?): Gainmap {
        val contents = map.gainmapContents
        val sx = contents.width.toDouble() / source.width
        val sy = contents.height.toDouble() / source.height
        val gx = (x * sx).toInt().coerceIn(0, contents.width - 1)
        val gy = (y * sy).toInt().coerceIn(0, contents.height - 1)
        val gw = (width * sx).toInt().coerceIn(1, contents.width - gx)
        val gh = (height * sy).toInt().coerceIn(1, contents.height - gy)
        val cut = Bitmap.createBitmap(contents, gx, gy, gw, gh, flip, false)
        // Android 15+ copies everything the map says (direction, primaries); before, the numbers one by one
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return Gainmap(map, cut)
        return Gainmap(cut).apply {
            map.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
            map.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
            map.gamma.let { setGamma(it[0], it[1], it[2]) }
            map.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
            map.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
            displayRatioForFullHdr = map.displayRatioForFullHdr
            minDisplayRatioForHdrTransition = map.minDisplayRatioForHdrTransition
        }
    }

    /** The camera's EXIF (time, exposure, orientation, place…) written into the cropped file, with its new size. */
    fun copyExif(original: ByteArray, file: ParcelFileDescriptor, size: PixelSize) {
        val from = ExifInterface(ByteArrayInputStream(original))
        val to = ExifInterface(file.fileDescriptor)
        for (tag in TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        to.saveAttributes()
    }

    /** The place found after the photo was taken (within seconds, SPEC §7.8) written into it. */
    fun addLocation(file: ParcelFileDescriptor, location: Location) {
        val exif = ExifInterface(file.fileDescriptor)
        exif.setGpsInfo(location)
        exif.saveAttributes()
    }

    private val TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_SCENE_TYPE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )
}
