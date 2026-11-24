package sh.hnet.camera.media

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.camera.camera.CapturedPhoto
import sh.hnet.camera.camera.VideoTarget
import sh.hnet.camera.capabilities.model.PixelSize

/**
 * A shot for another app (SPEC §15.3, LOGIKA §11): it goes only to that app, never to `DCIM/Camera`. A photo is written to the address the app gave, or comes
 * back as the small picture the API returns; a video is written there, or handed over from the app's cache through the file provider.
 */
class CaptureOutput(private val context: Context) {
    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, DIR)

    /** A video being recorded for another app and its file. */
    class Video(val target: VideoTarget, val file: File)

    /** A video recorded for another app: a file of its own in the cache, kept until the next capture starts. */
    suspend fun newVideo(onClosed: (File, Boolean) -> Unit): Video? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            // Earlier captures' files once their apps had a day to read them (one may read its result late, a draft's attachment)
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
            val file = File(dir, "video_${System.currentTimeMillis()}.mp4")
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            Video(
                VideoTarget(fd) { saved ->
                    if (!saved) withContext(Dispatchers.IO) { file.delete() }
                    onClosed(file, saved)
                },
                file,
            )
        }.onFailure { Log.w(TAG, "No file for the video: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** The photo as Review shows it: upright, mirrored as saved, at most [maxSide] pixels long. Null when it can't be decoded. */
    suspend fun preview(photo: CapturedPhoto, mirror: Boolean, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { upright(photo, mirror, maxSide) }.getOrNull()
    }

    /**
     * Whether the app's address may be written: only a content address of another app's provider, and not a media item this app owns. An exported activity writes
     * with this app's rights, so a file address (the app's own data), the app's own provider or its own photos would let any app overwrite them.
     */
    fun writable(output: Uri): Boolean {
        if (output.scheme != ContentResolver.SCHEME_CONTENT) return false
        if (output.authority == "${context.packageName}.files") return false
        if (output.authority == MediaStore.AUTHORITY) {
            val owner = runCatching { resolver.query(output, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            if (owner == context.packageName) return false
        }
        return true
    }

    /** The result for the app: the photo written to [output], or the small picture in `data`. Null when it couldn't be written. */
    suspend fun photoResult(photo: CapturedPhoto, mirror: Boolean, output: Uri?): Intent? = withContext(Dispatchers.IO) {
        try {
            if (output == null) return@withContext Intent().putExtra("data", upright(photo, mirror, SMALL_PX))
            write(output, withExif(photo, mirror))
            Intent()
        } catch (e: Exception) {
            Log.w(TAG, "Photo not handed over: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The result for the app: the video copied to [output], or its cache file offered for reading. Null when it couldn't be written. */
    suspend fun videoResult(file: File, output: Uri?): Intent? = withContext(Dispatchers.IO) {
        try {
            if (output != null) {
                open(output).use { out -> file.inputStream().use { it.copyTo(out) } }
                file.delete()
                return@withContext Intent().setData(output)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            Intent().setDataAndType(uri, "video/mp4").apply {
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video not handed over: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The photo as handed over: cut and mirrored where asked, with the camera's EXIF (orientation, time) put in before it is written once. */
    private fun withExif(photo: CapturedPhoto, mirror: Boolean): ByteArray {
        val edited = (if (photo.crop != null || mirror) StillCrop.edit(photo.bytes, photo.crop, mirror) else null) ?: return photo.bytes
        // ExifInterface writes into a file: one in the cache, read back
        val temp = File.createTempFile("photo", ".jpg", context.cacheDir)
        return try {
            temp.writeBytes(edited.bytes)
            runCatching { ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE).use { StillCrop.copyExif(photo.bytes, it, edited.size) } }
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    /** The app's address opened for writing: truncated where its provider allows, else plain. */
    private fun open(output: Uri): OutputStream =
        runCatching { resolver.openOutputStream(output, "wt") }.getOrNull() ?: resolver.openOutputStream(output, "w") ?: throw IOException("No output")

    private fun write(output: Uri, bytes: ByteArray) = open(output).use { it.write(bytes) }

    /** Decoded small, cut to its aspect, turned as its EXIF says and mirrored where the saved photo is. */
    private fun upright(photo: CapturedPhoto, mirror: Boolean, maxSide: Int): Bitmap {
        val bytes = photo.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val degrees = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        // The crop as StillCrop makes it, centred, at the decoded scale
        // The crop is the sensor's way round; pixels the camera turned itself are the other way
        val turnedCrop = photo.crop?.let { c -> if ((decoded.width < decoded.height) != (c.width < c.height)) PixelSize(c.height, c.width) else c }
        val cropW = turnedCrop?.let { (it.width / sample).coerceIn(1, decoded.width) } ?: decoded.width
        val cropH = turnedCrop?.let { (it.height / sample).coerceIn(1, decoded.height) } ?: decoded.height
        val scale = maxSide.toFloat() / maxOf(cropW, cropH)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(degrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val turned = Bitmap.createBitmap(decoded, (decoded.width - cropW) / 2, (decoded.height - cropH) / 2, cropW, cropH, matrix, true)
        if (turned !== decoded) decoded.recycle()
        return turned
    }

    private companion object {
        const val TAG = "CaptureOutput"
        const val DIR = "capture"
        /** The small picture the API returns without an address: well under what an intent may carry. */
        const val SMALL_PX = 320
        const val KEEP_MS = 24 * 60 * 60 * 1000L
    }
}
