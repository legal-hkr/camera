package sh.hnet.camera.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.camera.camera.VideoTarget

/** File names of the camera's shots: the local time to the millisecond, as bursts and quick shots fall in the same second. */
object FileNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")

    fun photo(takenAt: Long, zone: ZoneId = ZoneId.systemDefault()) = "IMG_${stamp.format(Instant.ofEpochMilli(takenAt).atZone(zone))}.jpg"

    fun video(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()) = "VID_${stamp.format(Instant.ofEpochMilli(startedAt).atZone(zone))}.mp4"
}

/** The newest shot the app has taken: what the thumbnail shows and the gallery opens. */
data class LastShot(val uri: Uri, val mimeType: String, val thumbnail: Bitmap?)

/**
 * The shots in the phone's media storage (LOGIKA §6.1, §7): written to `DCIM/Camera` as pending entries until complete. Without media permissions the app sees only
 * its own entries, which is what the thumbnail wants.
 */
class MediaFiles(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val _last = MutableStateFlow<LastShot?>(null)
    val last: StateFlow<LastShot?> = _last.asStateFlow()

    /** Writes a finished JPEG (Ultra HDR or plain) and publishes it; null when the storage refused it. */
    suspend fun savePhoto(bytes: ByteArray, takenAt: Long): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.photo(takenAt))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return@withContext null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Photo not saved: ${e.javaClass.simpleName}")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** A new video in `DCIM/Camera`, pending until the recording ends; null when the storage refused it. */
    suspend fun newVideo(startedAt: Long): VideoTarget? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.video(startedAt))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, startedAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return@withContext null
        val file = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (file == null) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        VideoTarget(file) { saved ->
            withContext(Dispatchers.IO) {
                if (saved) runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
                else runCatching { resolver.delete(uri, null, null) }
            }
            refreshLast()
        }
    }

    /** Reads the newest own shot again (after a save, when the viewfinder comes back). */
    suspend fun refreshLast() = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE)
        val found = runCatching {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                ContentUris.withAppendedId(collection, id) to (c.getString(2) ?: if (video) "video/mp4" else "image/jpeg")
            }
        }.getOrNull()
        if (found == null) {
            _last.value = null
            return@withContext
        }
        val (uri, mime) = found
        if (_last.value?.uri == uri && _last.value?.thumbnail != null) return@withContext
        val thumbnail = runCatching { resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null) }.getOrNull()
        _last.value = LastShot(uri, mime, thumbnail)
    }

    /** Own entries left pending by a process that died while writing (older than an hour) are removed when the app starts. */
    suspend fun removeAbandoned() = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} < ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf((System.currentTimeMillis() / 1000 - ABANDONED_AFTER_S).toString()))
        }
        runCatching {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) resolver.delete(ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), c.getLong(0)), null, null)
            }
        }
    }

    private companion object {
        const val TAG = "MediaFiles"
        const val CAMERA_DIR = "DCIM/Camera"
        /** The thumbnail is 52 dp; this covers it at up to 3.7× density. */
        const val THUMBNAIL_PX = 192
        const val ABANDONED_AFTER_S = 3600L
    }
}
