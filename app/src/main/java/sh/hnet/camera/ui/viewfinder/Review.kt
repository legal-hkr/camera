package sh.hnet.camera.ui.viewfinder

import android.app.Activity
import android.app.KeyguardManager
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import sh.hnet.camera.R

/**
 * Review of a shot for another app (SPEC §15.3): the photo, or the video with a play button and its time (no position line), centred in the canvas; Retake and
 * Use in place of the shutter row. Over everything else of the viewfinder.
 */
@Composable
fun ReviewScreen(review: Review, onRetake: () -> Unit, onUse: () -> Unit, snackbars: SnackbarHostState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface).clickable(enabled = false) {}) {
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility), contentAlignment = Alignment.Center) {
            when (review) {
                is Review.Photo -> review.preview?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                is Review.Video -> ReviewVideo(review)
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledTonalButton(onClick = onRetake, modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight)) {
                Icon(painterResource(R.drawable.ic_replay), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.button_retake), maxLines = 1)
            }
            Button(onClick = onUse, modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(if (review is Review.Video) R.string.button_use_video else R.string.button_use_photo), maxLines = 1)
            }
        }
    }
}

/** The video, fitted in the canvas (VideoView keeps its shape), a 72 dp play button over it while it doesn't play, and the time chip "0:00 / 0:12". */
@Composable
private fun ReviewVideo(review: Review.Video) {
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(playing) {
        while (playing) {
            view?.let { position = it.currentPosition }
            delay(TIME_TICK_MS)
        }
    }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(review.file.path)
                    setOnPreparedListener { player ->
                        length = player.duration
                        // The first frame shows instead of black
                        seekTo(1)
                    }
                    setOnCompletionListener {
                        playing = false
                        position = length
                    }
                    view = this
                }
            },
        )
        Text(
            stringResource(R.string.value_playback_time, duration(position / 1000L), duration((length + 999) / 1000L)),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).controlSurface(CircleShape, controlColor()).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (!playing) {
            val label = stringResource(R.string.content_description_play)
            Box(
                Modifier
                    .size(72.dp)
                    .controlSurface(CircleShape, controlColor())
                    .clickable(role = Role.Button) {
                        view?.let {
                            if (position >= length) it.seekTo(0)
                            it.start()
                            playing = true
                        }
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(36.dp))
            }
        } else {
            // A tap on the playing video pauses it
            Box(Modifier.fillMaxSize().clickable(role = Role.Button) {
                view?.pause()
                playing = false
            })
        }
    }
}

/** Over the lock screen [action] waits for the phone to be unlocked (the system asks); otherwise it runs at once. */
fun unlockThen(activity: Activity?, locked: Boolean, action: () -> Unit) {
    val keyguard = activity?.getSystemService(KeyguardManager::class.java)
    if (!locked || activity == null || keyguard?.isKeyguardLocked != true) {
        action()
        return
    }
    keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = action()
    })
}

private const val TIME_TICK_MS = 200L
