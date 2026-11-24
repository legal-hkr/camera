package sh.hnet.camera.diag

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The camera app's capture for other apps (SPEC §15.3), called as any app calls it: what came back, its size, picture and length. */
private enum class Ask { PHOTO_FILE, PHOTO_SMALL, VIDEO_FILE, VIDEO_LIMITED }

private const val CAMERA_PACKAGE = "sh.hnet.camera"

@Composable
fun SystemCameraCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked by remember { mutableStateOf<Ask?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ask = asked ?: return@rememberLauncherForActivityResult
        scope.launch {
            val (text, bitmap) = withContext(Dispatchers.IO) { describe(context, ask, result.resultCode, result.data, output) }
            report = text
            picture = bitmap
        }
    }
    fun ask(what: Ask) {
        asked = what
        report = null
        picture = null
        val video = what == Ask.VIDEO_FILE || what == Ask.VIDEO_LIMITED
        val intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).setPackage(CAMERA_PACKAGE)
        output = null
        if (what == Ask.PHOTO_FILE || what == Ask.VIDEO_FILE) {
            val dir = File(context.cacheDir, "capture").apply { mkdirs() }
            val file = File(dir, if (video) "video.mp4" else "photo.jpg").apply { delete() }
            output = file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (what == Ask.VIDEO_FILE) intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 5)
        if (what == Ask.VIDEO_LIMITED) intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, 5_000_000L).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            report = "No camera app $CAMERA_PACKAGE for ${intent.action}"
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.title_system_camera), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.desc_system_camera), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ask(Ask.PHOTO_FILE) }) { Text(stringResource(R.string.button_capture_photo_file)) }
                OutlinedButton(onClick = { ask(Ask.PHOTO_SMALL) }) { Text(stringResource(R.string.button_capture_photo_small)) }
                OutlinedButton(onClick = { ask(Ask.VIDEO_FILE) }) { Text(stringResource(R.string.button_capture_video_file)) }
                OutlinedButton(onClick = { ask(Ask.VIDEO_LIMITED) }) { Text(stringResource(R.string.button_capture_video_limited)) }
            }
            report?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            picture?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
        }
    }
}

/** What the camera returned, as data (the report stays English like the rest of the diagnostics), and a picture of it. */
private fun describe(context: Context, ask: Ask, code: Int, data: Intent?, output: File?): Pair<String, Bitmap?> {
    val lines = mutableListOf("$ask: result ${if (code == android.app.Activity.RESULT_OK) "OK" else if (code == android.app.Activity.RESULT_CANCELED) "CANCELED" else code.toString()}")
    if (code != Activity.RESULT_OK) return lines.joinToString("\n") to null
    var bitmap: Bitmap? = null
    when (ask) {
        Ask.PHOTO_SMALL -> {
            val small = data?.let { IntentCompat.getParcelableExtra(it, "data", Bitmap::class.java) }
            lines += if (small != null) "data bitmap: ${small.width}×${small.height}" else "data bitmap: missing"
            bitmap = small
        }
        Ask.PHOTO_FILE -> {
            val file = output
            if (file == null || !file.exists()) {
                lines += "file: missing"
            } else {
                lines += "file: ${file.length() / 1024} kB"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                lines += "pixels: ${bounds.outWidth}×${bounds.outHeight}"
                runCatching { ExifInterface(file.path) }.getOrNull()?.let { exif ->
                    lines += "EXIF orientation: ${exif.rotationDegrees}°, taken: ${exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "missing"}, GPS: ${if (exif.latLong != null) "yes" else "no"}"
                }
                bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        }
        Ask.VIDEO_FILE, Ask.VIDEO_LIMITED -> {
            val uri: Uri? = if (ask == Ask.VIDEO_FILE) output?.takeIf { it.exists() }?.let { Uri.fromFile(it) } else data?.data
            lines += "data uri: ${data?.data ?: "none"}"
            if (uri == null) {
                lines += "video: missing"
            } else {
                val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                lines += "size: ${size?.let { "${it / 1024} kB" } ?: "unreadable"}${if (ask == Ask.VIDEO_LIMITED) " (limit 4883 kB)" else ""}"
                runCatching {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, uri)
                        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                        lines += "length: $ms ms${if (ask == Ask.VIDEO_FILE) " (limit 5000)" else ""}, ${w}×$h, transfer $transfer (7 = HLG)"
                        bitmap = retriever.frameAtTime
                    }
                }.onFailure { lines += "unreadable: ${it.javaClass.simpleName}" }
            }
        }
    }
    return lines.joinToString("\n") to bitmap
}
