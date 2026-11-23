package sh.hnet.camera.camera

/**
 * The pauses of one recording, in the clock of the camera's frames (ns): a sample taken during a pause is dropped, a later one moves back by the pauses before it,
 * so the file plays on without a gap (LOGIKA §6.2). Paused and resumed on the camera thread, read by the picture's and the sound's threads.
 */
class PauseTimeline {
    private val pauses = mutableListOf<LongRange>()
    private var pausedAt: Long? = null

    val paused: Boolean
        @Synchronized get() = pausedAt != null

    @Synchronized
    fun pause(now: Long) {
        if (pausedAt == null) pausedAt = now
    }

    @Synchronized
    fun resume(now: Long) {
        val from = pausedAt ?: return
        pauses += from until now
        pausedAt = null
    }

    /** A sample's time in the file, or null when it falls into a pause. */
    @Synchronized
    fun map(time: Long): Long? {
        pausedAt?.let { if (time >= it) return null }
        var shift = 0L
        for (pause in pauses) {
            if (time in pause) return null
            if (time >= pause.last + 1) shift += pause.last + 1 - pause.first
        }
        return time - shift
    }
}
