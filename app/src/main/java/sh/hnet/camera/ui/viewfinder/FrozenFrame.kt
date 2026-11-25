package sh.hnet.camera.ui.viewfinder

import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import sh.hnet.camera.capabilities.model.Aspect

/**
 * The last live picture, fading out over a session being rebuilt (LOGIKA §23.3): [picture] as the preview's surface had it, a stream of [stream] (4:3 or
 * 16:9) for a photo of [aspect], from a camera whose sensor lies at [sensorOrientation] and that is [front].
 */
class FrozenFrame(val picture: Bitmap, val stream: Aspect, val aspect: Aspect, val sensorOrientation: Int, val front: Boolean) {
    /** The square's bands lay over it. */
    val square: Boolean get() = aspect == Aspect.SQUARE && stream == Aspect.FOUR_THREE

    companion object {
        /** Copies the surface's last frame on the GPU; [done] gets null when the surface has none or refuses (a protected or unreadable buffer). */
        fun copy(surface: Surface, executor: Executor, done: (Bitmap?) -> Unit) {
            val request = runCatching { PixelCopy.Request.Builder.ofSurface(surface).build() }.getOrNull() ?: return done(null)
            runCatching {
                PixelCopy.request(request, executor) { result ->
                    // The bitmap exists only on success (asking for it otherwise throws)
                    done(runCatching { if (result.status == PixelCopy.SUCCESS) result.bitmap else null }.getOrNull())
                }
            }.onFailure { done(null) }
        }
    }
}

/** Without a frozen frame (the copy failed or was late) the live picture fades out to the background in this time before the change is told to the engine. */
const val COVER_FADE_MS = 150

/**
 * The frozen frame drawn to fill its box, upright. A copy that lies on its side (the surface's turn not applied) is turned by
 * the sensor's orientation and mirrored for the front camera, as the live picture is shown.
 */
@Composable
fun FrozenPicture(frame: FrozenFrame, modifier: Modifier = Modifier) {
    val image = remember(frame) { frame.picture.asImageBitmap() }
    val sideways = image.width > image.height
    Canvas(modifier) {
        val turn = if (sideways) frame.sensorOrientation.toFloat() else 0f
        val quarter = sideways && frame.sensorOrientation % 180 != 0
        // Drawn into the box's size, turned into place: across a quarter turn the box's sides swap
        val width = if (quarter) size.height else size.width
        val height = if (quarter) size.width else size.height
        val offset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt())
        scale(if (sideways && frame.front) -1f else 1f, 1f) {
            rotate(turn) {
                drawImage(image, dstOffset = offset, dstSize = IntSize(width.roundToInt(), height.roundToInt()))
            }
        }
    }
}

