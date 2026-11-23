@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.camera.ui.viewfinder

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.PersistableBundle
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlin.math.hypot
import sh.hnet.camera.R
import sh.hnet.camera.camera.QrContent
import sh.hnet.camera.capabilities.model.Point

/** The QR corners (SPEC §5.5): at each corner of the code two 22 dp arms along its edges, a 3.5 dp `primary` core in a 7 dp `surface` outline. */
@Composable
fun QrMarks(corners: List<Point>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxSize()) {
        if (corners.size != 4) return@Canvas
        val points = corners.map { Offset(it.x.toFloat() * size.width, it.y.toFloat() * size.height) }
        val arm = 22.dp.toPx()
        for ((width, color) in listOf(7.dp.toPx() to colors.surface, 3.5.dp.toPx() to colors.primary)) {
            for (i in 0..3) {
                val corner = points[i]
                for (neighbour in listOf(points[(i + 1) % 4], points[(i + 3) % 4])) {
                    val dx = neighbour.x - corner.x
                    val dy = neighbour.y - corner.y
                    val length = hypot(dx, dy).coerceAtLeast(1f)
                    val reach = minOf(arm, length / 2)
                    drawLine(color, corner, Offset(corner.x + dx / length * reach, corner.y + dy / length * reach), width, StrokeCap.Round)
                }
            }
        }
    }
}

@DrawableRes
fun qrIcon(content: QrContent) = when (content) {
    is QrContent.Link -> R.drawable.ic_link
    is QrContent.Wifi -> R.drawable.ic_wifi
    is QrContent.Text -> R.drawable.ic_notes
}

/** The suggestion chip's words for a code: the link's domain, the network's name, the text's first words. */
@Composable
fun qrChipText(content: QrContent) = when (content) {
    is QrContent.Link -> content.domain
    is QrContent.Wifi -> stringResource(R.string.label_qr_wifi, content.ssid)
    is QrContent.Text -> content.preview
}

/**
 * The QR sheet (SPEC §7.10): what the code holds in full (selectable), what it is, and its actions with the main one last and filled. A link is only handed to
 * another app (the camera has no Internet); a network is offered to the system's own add-network dialog.
 */
@Composable
fun QrSheet(content: QrContent, onDismiss: () -> Unit, onNoApp: () -> Unit, onWifiRefused: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(qrIcon(content)), contentDescription = null, tint = colors.onSecondaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(if (content is QrContent.Wifi) R.string.title_qr_wifi else R.string.title_qr_code), style = MaterialTheme.typography.titleLarge)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(if (content is QrContent.Wifi) content.ssid else content.raw.trim(), style = MaterialTheme.typography.bodyLarge)
                }
                val supporting = when (content) {
                    is QrContent.Link -> listOf(stringResource(R.string.label_qr_link))
                    is QrContent.Text -> listOf(stringResource(R.string.label_qr_text))
                    is QrContent.Wifi -> listOfNotNull(stringResource(R.string.label_qr_wifi_name, content.ssid), content.security?.let { stringResource(R.string.label_qr_wifi_security, it) })
                }
                supporting.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
            // Wrapped onto a second line rather than squeezed (Polish words are long)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (content) {
                    is QrContent.Link -> {
                        OutlinedButton(onClick = { copy(context, content.raw.trim(), sensitive = false) }) { ButtonContent(R.drawable.ic_content_copy, stringResource(R.string.button_copy)) }
                        OutlinedButton(onClick = { share(context, content.raw.trim()) }) { ButtonContent(R.drawable.ic_share, stringResource(R.string.button_share)) }
                        Button(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, content.raw.trim().toUri().normalizeScheme()))
                                onDismiss()
                            } catch (_: ActivityNotFoundException) {
                                onNoApp()
                            }
                        }) { ButtonContent(R.drawable.ic_open_in_new, stringResource(R.string.button_open)) }
                    }
                    is QrContent.Text -> {
                        OutlinedButton(onClick = { copy(context, content.raw, sensitive = false) }) { ButtonContent(R.drawable.ic_content_copy, stringResource(R.string.button_copy)) }
                        Button(onClick = { share(context, content.raw) }) { ButtonContent(R.drawable.ic_share, stringResource(R.string.button_share)) }
                    }
                    is QrContent.Wifi -> {
                        content.password?.let { password ->
                            OutlinedButton(onClick = { copy(context, password, sensitive = true) }) { ButtonContent(R.drawable.ic_content_copy, stringResource(R.string.button_copy_password)) }
                        }
                        // Always offered; a network the system won't take (a password under 8 characters or not in ASCII, WEP, enterprise) says why
                        val network = remember(content) { suggestion(content) }
                        Button(onClick = {
                            if (network == null) {
                                onWifiRefused()
                                return@Button
                            }
                            try {
                                context.startActivity(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(network)))
                                onDismiss()
                            } catch (_: ActivityNotFoundException) {
                                onNoApp()
                            }
                        }) { ButtonContent(R.drawable.ic_wifi, stringResource(R.string.button_connect)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonContent(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, maxLines = 1, softWrap = false)
}

/** A network the system can add: WPA2 and WPA3 (SAE) with their password, an open one without; WEP has no suggestion (only its password can be copied). */
private fun suggestion(wifi: QrContent.Wifi): WifiNetworkSuggestion? = runCatching {
    // The whole chain may throw: a passphrase the system can't encode (not ASCII, too short or long) refuses in its setter
    val security = wifi.security?.uppercase()
    // Enterprise networks (EAP) need more than a code gives
    if (security != null && "EAP" in security) return@runCatching null
    val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).setIsHiddenSsid(wifi.hidden)
    val password = wifi.password
    when (security) {
        "WEP" -> return@runCatching null
        "SAE", "WPA3" -> builder.setWpa3Passphrase(password ?: return@runCatching null)
        null, "", "NOPASS" -> if (password != null) builder.setWpa2Passphrase(password)
        else -> builder.setWpa2Passphrase(password ?: return@runCatching null)
    }
    builder.build()
}.getOrNull()

/** To the clipboard; a password is marked sensitive, so the system doesn't show it in its preview. */
private fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("", text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun share(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}
