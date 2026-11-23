@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.camera.R
import sh.hnet.camera.ui.components.EmptyState
import sh.hnet.camera.ui.components.TooltipIconButton

/** The camera's frame until its stage of the UI handoff is built: the title, Settings and "In development". */
@Composable
fun PlaceholderScreen(title: String, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.title_settings), onOpenSettings) },
        )
        // The app's mark is drawn on the launcher icon's canvas, hence the larger size
        EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_in_development), iconSize = 108.dp)
    }
}
