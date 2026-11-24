package sh.hnet.camera.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import sh.hnet.camera.camera.MotionClip
import sh.hnet.camera.camera.VideoTarget
import sh.hnet.camera.capabilities.model.PixelSize

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

    /** Writes a finished JPEG (Ultra HDR or plain), cut to [crop] first when the camera had no size of the shot's aspect, and publishes it; null when the storage refused it. */
    suspend fun savePhoto(bytes: ByteArray, takenAt: Long, crop: PixelSize? = null, mirror: Boolean = false): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.photo(takenAt))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return@withContext null
        try {
            // Cut to its aspect and mirrored where asked, encoded again; else the camera's own file
            val edited = if (crop != null || mirror) StillCrop.edit(bytes, crop, mirror) else null
            resolver.openOutputStream(uri)!!.use { it.write(edited?.bytes ?: bytes) }
            if (edited != null) {
                // The photo is kept even if its EXIF can't be carried over
                runCatching { resolver.openFileDescriptor(uri, "rw")!!.use { StillCrop.copyExif(bytes, it, edited.size) } }.onFailure { Log.w(TAG, "EXIF not copied: ${it.javaClass.simpleName}") }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            show(uri, "image/jpeg")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Photo not saved: ${e.javaClass.simpleName}")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** The place found shortly after a photo without one (SPEC §7.8). */
    /**
     * A saved photo made a motion photo (LOGIKA §3.3): its video appended and named in its XMP, the file rewritten whole. A photo that can't take it stays as it
     * was.
     */
    suspend fun addMotion(uri: Uri, clip: MotionClip) = withContext(Dispatchers.IO) {
        runCatching {
            val photo = resolver.openInputStream(uri)!!.use { it.readBytes() }
            val whole = MotionPhotoWriter.write(photo, clip.bytes, clip.presentationUs) ?: return@runCatching
            resolver.openOutputStream(uri, "wt")!!.use { it.write(whole) }
        }.onFailure { Log.w(TAG, "Motion video not added: ${it.javaClass.simpleName}") }
    }

    suspend fun addLocation(uri: Uri, location: Location) = withContext(Dispatchers.IO) {
        runCatching { resolver.openFileDescriptor(uri, "rw")?.use { StillCrop.addLocation(it, location) } }.onFailure { Log.w(TAG, "Place not added: ${it.javaClass.simpleName}") }
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
            if (saved) show(uri, "video/mp4") else refreshLast()
        }
    }

    /** The shot this process saved last: the thumbnail shows it as long as it exists (a video's entry is older than a photo taken while it recorded). */
    @Volatile
    private var latest: Pair<Uri, String>? = null

    /** A shot this process saved, and when (the lock screen's session shows only its own, SPEC §15.2). */
    data class Saved(val uri: Uri, val mimeType: String, val at: Long)

    private val _saved = MutableStateFlow<List<Saved>>(emptyList())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()

    /** Shows a shot just saved in the thumbnail. */
    private suspend fun show(uri: Uri, mime: String) = withContext(Dispatchers.IO) {
        latest = uri to mime
        _saved.update { (it + Saved(uri, mime, System.currentTimeMillis())).takeLast(SAVED_KEPT) }
        val thumbnail = runCatching { resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null) }.getOrNull()
        _last.value = LastShot(uri, mime, thumbnail)
    }

    /**
     * Reads the newest own shot again (when the viewfinder comes back): the one saved last by this process while it still exists (a gallery may have deleted it),
     * else the newest by the time it was taken.
     */
    suspend fun refreshLast() = withContext(Dispatchers.IO) {
        latest?.let { (uri, mime) ->
            // Asked through the collection, whose default leaves out what is in the trash or pending (a gallery's delete is usually a move to the trash)
            val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ContentUris.parseId(uri).toString()))
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            }
            val exists = runCatching { resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count > 0 } }.getOrNull() == true
            if (exists) {
                if (_last.value?.uri != uri || _last.value?.thumbnail == null) show(uri, mime)
                return@withContext
            }
            latest = null
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
            // The SQL order: MediaStore ignored QUERY_ARG_SORT_DIRECTION and gave the oldest shot
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC")
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

    /** Calls [onChange] (a moment after the last of a burst of changes) when anything in the media storage changes: a gallery deleting the last shot, say. */
    fun watch(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        return { resolver.unregisterContentObserver(observer) }
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
        /** The shots of a lock-screen session: far more than one takes before unlocking. */
        const val SAVED_KEPT = 500
        const val TAG = "MediaFiles"
        const val CAMERA_DIR = "DCIM/Camera"
        /** The thumbnail is 52 dp; this covers it at up to 3.7× density. */
        const val THUMBNAIL_PX = 192
        const val ABANDONED_AFTER_S = 3600L
    }
}
