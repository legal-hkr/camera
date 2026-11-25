package sh.hnet.camera.media

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.camera.capabilities.model.PixelSize

/**
 * Saves bursts (LOGIKA §18): each frame is first put aside as it comes – a plain write into the app's own files, so the camera isn't held back – and then, one
 * by one in the background, saved in the media storage: cut to the aspect and mirrored where asked, named and marked as a burst ([MediaFiles.saveBurstFrame]).
 * A burst's covers go before the other frames waiting, so its thumbnail comes at once. What is put aside outlives the process (a note per burst says how to
 * save its frames): frames left when the app ends are saved when it starts again. [pending] counts the frames not saved yet (shown as being processed).
 */
class BurstSaver(context: Context, private val media: MediaFiles, private val scope: CoroutineScope) {
    /** A frame put aside; [resumed]: by a process that ended before saving it; [failedBefore]: its saving failed once already. */
    private class Staged(val file: File, val burst: BurstFiles, val index: Int, val takenAt: Long, val resumed: Boolean = false, val failedBefore: Boolean = false)

    /**
     * A burst's saving: its frame shown in the thumbnail, how many are put aside and saved, and whether more may come. A resumed burst isn't shown as it is
     * saved (its cover may be in the gallery already): the thumbnail is read again from the gallery when it is done.
     */
    private class Progress(var shown: Uri? = null, var staged: Int = 0, var done: Int = 0, var saved: Int = 0, var ended: Boolean = false, var resumed: Boolean = false)

    private val queue = ArrayDeque<Staged>()
    private val progress = HashMap<String, Progress>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()
    // Not the cache: the system may clear it while frames wait (a burst is hundreds of megabytes); not backed up either
    private val dir = File(context.noBackupFilesDir, DIR)

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in wake) {
                while (true) {
                    val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    // Whatever one frame throws (an image too large to decode for its crop), the others are still saved
                    try {
                        save(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "Burst frame not saved: ${e.javaClass.simpleName}")
                    }
                    _pending.update { (it - 1).coerceAtLeast(0) }
                }
            }
        }
    }

    /** Frame [index] of [burst] put aside; false when it couldn't be written (no room). */
    suspend fun stage(burst: BurstFiles, index: Int, bytes: ByteArray, takenAt: Long): Boolean = withContext(Dispatchers.IO) {
        val file = File(dir, "${burst.id}_${index}_$takenAt.jpg")
        runCatching {
            dir.mkdirs()
            // The note first: frames without it couldn't be saved after a restart; each written whole before it gets its name (a process ending mid-write
            // leaves only a .tmp, never a cut file)
            if (index == 0) whole(note(burst)) { it.writeText(listOf(burst.startedAt, burst.crop?.let { c -> "${c.width}x${c.height}" } ?: "-", if (burst.mirror) "1" else "0").joinToString("\n")) }
            whole(file) { it.writeBytes(bytes) }
        }.onFailure {
            Log.w(TAG, "Burst frame not put aside: ${it.javaClass.simpleName}")
            file.delete()
            return@withContext false
        }
        enqueue(Staged(file, burst, index, takenAt))
        true
    }

    private fun whole(target: File, write: (File) -> Unit) {
        val temporary = File(target.path + ".tmp")
        write(temporary)
        if (!temporary.renameTo(target)) error("rename failed")
    }

    /** No more frames of [burst] will come: once its frames are saved, its note goes. */
    fun ended(burst: BurstFiles) = scope.launch(Dispatchers.IO) {
        synchronized(queue) { progress[burst.id]?.ended = true }
        tidy(burst)
    }

    private fun enqueue(staged: Staged) {
        // Counted before the saving can take it (else a frame saved at once would leave the count one too high for good)
        _pending.update { it + 1 }
        synchronized(queue) {
            progress.getOrPut(staged.burst.id) { Progress() }.staged++
            // A cover before the frames of earlier bursts still waiting: the thumbnail shows the burst at once
            if (staged.index == 0) queue.addFirst(staged) else queue.addLast(staged)
        }
        wake.trySend(Unit)
    }

    private suspend fun save(staged: Staged) {
        val state = synchronized(queue) { progress.getOrPut(staged.burst.id) { Progress() } }
        // The cover shown, or the first frame saved when the cover failed
        val show = !state.resumed && state.shown == null
        val bytes = runCatching { staged.file.readBytes() }.getOrNull()
        val uri = when {
            bytes == null -> null
            // Saved already by the ended process (its file not deleted yet): not a second copy
            staged.resumed && media.burstFrameSaved(FileNames.burst(staged.burst.startedAt, staged.index)) -> null.also { staged.file.delete() }
            else -> media.saveBurstFrame(bytes, staged.takenAt, staged.burst, staged.index, show)
        }
        // The frame put aside goes only once it is in the gallery: a process ending meanwhile saves it on the next start; one failed twice goes
        when {
            uri != null || bytes == null || staged.failedBefore -> staged.file.delete()
            staged.file.exists() -> staged.file.renameTo(File(dir, staged.file.name.removeSuffix(".jpg") + FAILED + ".jpg"))
        }
        val last = synchronized(queue) {
            state.done++
            if (uri != null) {
                state.saved++
                if (show) state.shown = uri
            }
            state.ended && state.done >= state.staged
        }
        // The count in the thumbnail every few frames and at the end (not with every frame: the viewfinder would redraw it each time)
        val shown = state.shown
        if (uri != null && shown != null && (state.saved % COUNT_EVERY == 0 || last)) media.burstSaved(shown, state.saved)
        if (last) tidy(staged.burst)
    }

    /** A burst all saved: its note and its progress go (its frames that failed once wait for the next start, with the note). */
    private suspend fun tidy(burst: BurstFiles) {
        val state = synchronized(queue) { progress[burst.id] } ?: return
        val done = synchronized(queue) { state.ended && state.done >= state.staged }
        if (!done) return
        if (state.resumed) media.refreshLast() else state.shown?.let { media.burstSaved(it, state.saved) }
        synchronized(queue) { progress.remove(burst.id) }
        if (dir.listFiles()?.none { it.name.startsWith(burst.id + "_") } != false) note(burst).delete()
    }

    /**
     * When the app starts: the frames a process put aside and didn't save, saved now from their bursts' notes; frames without a note (it couldn't be written)
     * go.
     */
    suspend fun resume() = withContext(Dispatchers.IO) {
        // Only what an ended process left: a burst begun since (this process's) isn't taken twice
        val startedAt = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.lastModified() < startedAt } ?: return@withContext
        // Writes cut off by the process's end
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val notes = files.filter { it.name.endsWith(NOTE) }.associateBy { it.name.removeSuffix(NOTE) }
        val frames = files.filter { it.name.endsWith(".jpg") }.mapNotNull { file ->
            val parts = file.name.removeSuffix(".jpg").split('_')
            val id = parts.getOrNull(0)
            val index = parts.getOrNull(1)?.toIntOrNull()
            val takenAt = parts.getOrNull(2)?.toLongOrNull()
            val burst = id?.let { notes[it] }?.let { read(it, id) }
            if (burst == null || index == null || takenAt == null) {
                file.delete()
                null
            } else {
                Staged(file, burst, index, takenAt, resumed = true, failedBefore = parts.getOrNull(3) == FAILED.removePrefix("_"))
            }
        }
        for ((id, note) in notes) if (frames.none { it.burst.id == id }) note.delete()
        val bursts = frames.map { it.burst }.distinctBy { it.id }
        synchronized(queue) { bursts.forEach { progress.getOrPut(it.id) { Progress() }.resumed = true } }
        frames.sortedWith(compareBy({ it.burst.startedAt }, { it.index })).forEach(::enqueue)
        // No more frames will come for these: said once all are queued (said before, the first saved could close the burst before the others came)
        synchronized(queue) { bursts.forEach { progress[it.id]?.ended = true } }
        bursts.forEach { tidy(it) }
    }

    private fun note(burst: BurstFiles) = File(dir, burst.id + NOTE)

    private fun read(note: File, id: String): BurstFiles? = runCatching {
        val (startedAt, crop, mirror) = note.readLines()
        val size = crop.takeIf { it != "-" }?.split('x')?.let { (w, h) -> PixelSize(w.toInt(), h.toInt()) }
        BurstFiles(startedAt.toLong(), id, size, mirror == "1")
    }.getOrNull()

    private companion object {
        const val TAG = "BurstSaver"
        const val DIR = "burst"
        const val NOTE = ".burst"
        const val COUNT_EVERY = 10
        /** A frame whose saving failed once: tried once more on the next start. */
        const val FAILED = "_f"
    }
}
