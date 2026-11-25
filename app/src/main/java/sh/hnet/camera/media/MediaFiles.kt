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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.camera.camera.MotionClip
import sh.hnet.camera.camera.VideoTarget
import sh.hnet.camera.capabilities.model.PixelSize

/** File names of the camera's shots: the local time to the millisecond, as bursts and quick shots fall in the same second. */
object FileNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")

    fun photo(takenAt: Long, zone: ZoneId = ZoneId.systemDefault()) = "IMG_${stamp.format(Instant.ofEpochMilli(takenAt).atZone(zone))}.jpg"

    fun video(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()) = "VID_${stamp.format(Instant.ofEpochMilli(startedAt).atZone(zone))}.mp4"

    /** A burst's frame as Google's camera names it (D12): all named by the burst's start, `…_BURST000_COVER.jpg` the cover, `…_BURST001.jpg` on. */
    fun burst(startedAt: Long, index: Int, zone: ZoneId = ZoneId.systemDefault()) =
        "IMG_${stamp.format(Instant.ofEpochMilli(startedAt).atZone(zone))}_BURST${index.toString().padStart(3, '0')}${if (index == 0) "_COVER" else ""}.jpg"

    /** A burst frame's name: the burst's own prefix (`IMG_<stamp>_BURST`), or null for any other file. */
    fun burstPrefix(name: String): String? = burstName.matchEntire(name)?.groupValues?.get(1)

    private val burstName = Regex("(IMG_\\d{8}_\\d{9}_BURST)\\d{3}(_COVER)?\\.jpg")
}

/** One burst being saved: its start (the names), its id (the XMP), and where its frames are cut or mirrored. */
class BurstFiles(val startedAt: Long, val id: String, val crop: PixelSize?, val mirror: Boolean)

/** The newest shot the app has taken: what the thumbnail shows and the gallery opens; [burst]: the frames of the burst it is the cover of (0: a single shot). */
data class LastShot(val uri: Uri, val mimeType: String, val thumbnail: Bitmap?, val burst: Int = 0)

/**
 * The shots in the phone's media storage (LOGIKA §6.1, §7): written to `DCIM/Camera` as pending entries until complete. Without media permissions the app sees only
 * its own entries, which is what the thumbnail wants.
 */
class MediaFiles(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val _last = MutableStateFlow<LastShot?>(null)
    val last: StateFlow<LastShot?> = _last.asStateFlow()

    /** Writes a finished JPEG (Ultra HDR or plain), cut to [crop] first when the camera had no size of the shot's aspect, and publishes it; null when the storage refused it. */
    suspend fun savePhoto(bytes: ByteArray, takenAt: Long, crop: PixelSize? = null, mirror: Boolean = false, exposureNs: Long? = null): Uri? =
        writePhoto(bytes, takenAt, FileNames.photo(takenAt), crop, mirror, exposureNs, mark = null)?.also { show(it, "image/jpeg", takenAt = takenAt) }

    /**
     * A burst's frame [index] (0 the cover), written as it comes (LOGIKA §18): named and marked as Google's camera does (D12), cut and mirrored as a photo is.
     * The cover is shown in the thumbnail, the others only counted there.
     */
    suspend fun saveBurstFrame(bytes: ByteArray, takenAt: Long, burst: BurstFiles, index: Int, show: Boolean): Uri? {
        val uri = writePhoto(bytes, takenAt, FileNames.burst(burst.startedAt, index), burst.crop, burst.mirror) { MotionPhotoWriter.markBurst(it, burst.id, primary = index == 0) ?: it }
            ?: return null
        if (show) show(uri, "image/jpeg", burst = 1, takenAt = takenAt) else counted(uri, "image/jpeg", takenAt)
        return uri
    }

    /** The burst whose cover the thumbnail shows has [frames] saved. */
    fun burstSaved(cover: Uri, frames: Int) = _last.update { if (it?.uri == cover) it.copy(burst = frames) else it }

    /** [exposureNs]: a time the camera's EXIF couldn't hold, written over its own. */
    private suspend fun writePhoto(bytes: ByteArray, takenAt: Long, name: String, crop: PixelSize?, mirror: Boolean, exposureNs: Long? = null, mark: ((ByteArray) -> ByteArray)?): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return@withContext null
        try {
            // Cut to its aspect and mirrored where asked, encoded again; else the camera's own file
            val edited = if (crop != null || mirror) StillCrop.edit(bytes, crop, mirror) else null
            val written = (edited?.bytes ?: bytes).let { mark?.invoke(it) ?: it }
            resolver.openOutputStream(uri)!!.use { it.write(written) }
            if (edited != null) {
                // The photo is kept even if its EXIF can't be carried over
                runCatching { resolver.openFileDescriptor(uri, "rw")!!.use { StillCrop.copyExif(bytes, it, edited.size) } }.onFailure { Log.w(TAG, "EXIF not copied: ${it.javaClass.simpleName}") }
            }
            exposureNs?.let { ns ->
                runCatching { resolver.openFileDescriptor(uri, "rw")!!.use { StillCrop.setExposure(it, ns) } }.onFailure { Log.w(TAG, "Exposure not written: ${it.javaClass.simpleName}") }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Photo not saved: ${e.javaClass.simpleName}")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

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

    /** The place found shortly after a photo without one (SPEC §7.8). */
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
            // Shown as of its end: the newest shot (a photo taken while it recorded is older)
            if (saved) show(uri, "video/mp4", takenAt = System.currentTimeMillis()) else refreshLast()
        }
    }

    /** The shot this process saved last: the thumbnail shows it as long as it exists (a video's entry is older than a photo taken while it recorded). */
    @Volatile
    private var latest: Pair<Uri, String>? = null

    /** A shot this process saved, and when (the lock screen's session shows only its own, SPEC §15.2). */
    data class Saved(val uri: Uri, val mimeType: String, val at: Long)

    private val _saved = MutableStateFlow<List<Saved>>(emptyList())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()

    /** When the shot in the thumbnail was taken: a burst's cover saved late doesn't replace a newer shot. */
    @Volatile
    private var latestTaken = 0L

    /** One shot at a time decides the thumbnail (a burst's saving, a photo and a video may finish together). */
    private val showing = Mutex()

    /** Shows a shot just saved in the thumbnail, unless a newer one is there. */
    private suspend fun show(uri: Uri, mime: String, burst: Int = 0, takenAt: Long) = withContext(Dispatchers.IO) {
        counted(uri, mime, takenAt)
        val thumbnail = runCatching { resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null) }.getOrNull()
        showing.withLock {
            if (takenAt < latestTaken) return@withLock
            latestTaken = takenAt
            latest = uri to mime
            _last.value = LastShot(uri, mime, thumbnail, burst)
        }
    }

    /**
     * A shot of this session (the lock screen's review has it) not shown in the thumbnail: a burst's frame after the cover. [takenAt] decides the session: a
     * burst taken before the phone was locked and saved after stays out of the lock screen's.
     */
    private fun counted(uri: Uri, mime: String, takenAt: Long) = _saved.update { (it + Saved(uri, mime, takenAt)).takeLast(SAVED_KEPT) }

    /**
     * Before a burst's frame put aside by an ended process is saved: whether it is in the gallery already (saved, its file not yet deleted when the process
     * ended), and its pending entry from a write cut off removed (the name would be taken).
     */
    suspend fun burstFrameSaved(name: String): Boolean = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun ids(pending: Boolean): List<Long> = runCatching {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, if (pending) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            }
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptyList())
        ids(pending = true).forEach { runCatching { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) } }
        ids(pending = false).isNotEmpty()
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
                // A burst's count again too: frames may have been deleted in a gallery
                val current = _last.value
                if (current?.uri != uri || current.thumbnail == null || current.burst > 0) {
                    val burst = burstOf(uri)
                    _last.value = LastShot(burst?.first ?: uri, mime, current?.takeIf { it.uri == uri }?.thumbnail ?: thumbnail(uri), burst?.second ?: 0)
                }
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
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN)
        val found = runCatching {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                // A shot saved later by this process (a burst's frames saved from an ended process's) mustn't replace a newer one found here
                latestTaken = maxOf(latestTaken, c.getLong(3))
                ContentUris.withAppendedId(collection, id) to (c.getString(2) ?: if (video) "video/mp4" else "image/jpeg")
            }
        }.getOrNull()
        if (found == null) {
            _last.value = null
            return@withContext
        }
        // The newest of a burst is its last frame: the thumbnail shows its cover, with the count
        val mime = found.second
        val burst = if (mime.startsWith("image/")) burstOf(found.first) else null
        val uri = burst?.first ?: found.first
        if (_last.value?.uri == uri && _last.value?.thumbnail != null && _last.value?.burst == (burst?.second ?: 0)) return@withContext
        _last.value = LastShot(uri, mime, thumbnail(uri), burst?.second ?: 0)
    }

    private fun thumbnail(uri: Uri): Bitmap? = runCatching { resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null) }.getOrNull()

    /** For a burst's frame: its cover (the frame itself when the cover is gone) and how many of its frames are left; null for any other photo. */
    private fun burstOf(uri: Uri): Pair<Uri, Int>? = runCatching {
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return@runCatching null
        val prefix = FileNames.burstPrefix(name) ?: return@runCatching null
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            // In the camera's folder only: the table may be large
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$CAMERA_DIR/", "$prefix%"))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), args, null)?.use { c ->
            var cover: Uri? = null
            var count = 0
            while (c.moveToNext()) {
                // Only this burst's frames: LIKE takes "_" for any character
                if (FileNames.burstPrefix(c.getString(1)) != prefix) continue
                count++
                if (c.getString(1).endsWith("_COVER.jpg")) cover = ContentUris.withAppendedId(collection, c.getLong(0))
            }
            (cover ?: uri) to count
        }
    }.getOrNull()

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
        const val SAVED_KEPT = 1000
        const val TAG = "MediaFiles"
        const val CAMERA_DIR = "DCIM/Camera"
        /** The thumbnail is 52 dp; this covers it at up to 3.7× density. */
        const val THUMBNAIL_PX = 192
        const val ABANDONED_AFTER_S = 3600L
    }
}
