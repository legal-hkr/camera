@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.welcome

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.FirstRun
import sh.hnet.camera.R
import sh.hnet.camera.ui.components.AppLogo
import sh.hnet.camera.ui.components.FormPage
import sh.hnet.camera.ui.components.RowIcon
import sh.hnet.camera.ui.components.SettingsNote
import sh.hnet.camera.ui.components.SettingsRadioRow
import sh.hnet.camera.ui.components.groupShapes
import sh.hnet.camera.ui.components.settingsRowColors
import sh.hnet.camera.ui.settings.rememberRestoreAction

private enum class Step { ACCESS, BACKUP }

/** An access the camera asks for, with its row's icon and texts (SPEC §16). */
private class Access(val permissions: Array<String>, @param:DrawableRes val icon: Int, val title: Int, val supporting: Int)

private val ACCESSES = listOf(
    Access(arrayOf(Manifest.permission.CAMERA), R.drawable.ic_photo_camera, R.string.label_access_camera, R.string.desc_access_camera),
    Access(arrayOf(Manifest.permission.RECORD_AUDIO), R.drawable.ic_mic, R.string.label_access_microphone, R.string.desc_access_microphone),
    Access(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), R.drawable.ic_location_on, R.string.label_access_location, R.string.desc_access_location),
)

/**
 * The first run (SPEC §16, as Gallery's wizard, no separate welcome page): Access with the app's mark, what the app does with photos, and a row for each access
 * – given, or Allow – then the backup or a fresh start. Access already given at the start skips its step. Lies over the viewfinder until it is done.
 */
@Composable
fun WizardScreen(snackbars: SnackbarHostState, messageScope: CoroutineScope, onDone: () -> Unit) {
    val context = LocalContext.current
    val startedWithAccess = rememberSaveable { ACCESSES.all { FirstRun.granted(context, it.permissions) } }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val steps = buildList {
        if (!startedWithAccess) add(Step.ACCESS)
        add(Step.BACKUP)
    }
    val step = steps.getOrElse(index) { steps.last() }
    val next: () -> Unit = { if (index >= steps.lastIndex) onDone() else index++ }
    val label = stringResource(R.string.label_step, index + 1, steps.size)
    when (step) {
        Step.ACCESS -> AccessStep(label, onNext = next)
        Step.BACKUP -> BackupStep(label, snackbars, messageScope, onNext = next)
    }
}

/** A step's frame: the app's mark, "Step n of 2", the title and its paragraph, then the step's own content. */
@Composable
private fun WizardStep(step: String, title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    FormPage(title = null, confirmLabel = confirmLabel, onConfirm = onConfirm, contentSpacing = 0.dp) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(step, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = TITLE_INSET))
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 4.dp).semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp, bottom = 20.dp))
        content()
    }
}

/**
 * Step 1 (SPEC §16): where photos go, and the three accesses, each asked on its own (Allow, or the app's system settings once the system stops asking). Next
 * goes on whatever was given: without the camera the viewfinder shows its own empty state. Location given here turns location in photos on.
 */
@Composable
private fun AccessStep(step: String, onNext: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val app = context.applicationContext as CameraApplication
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ACCESSES.map { FirstRun.granted(context, it.permissions) }) }
    // Accesses the system just refused for good: their Allow opens the system's settings, until the next return from there
    var refused by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    // Location given during the wizard, however (its dialog or the system's settings), turns location in photos on once
    var locationAtStart by rememberSaveable { mutableStateOf(granted[LOCATION_ROW]) }
    fun recheck() {
        granted = ACCESSES.map { FirstRun.granted(context, it.permissions) }
        if (granted[LOCATION_ROW] && !locationAtStart) {
            locationAtStart = true
            scope.launch { app.preferences.setLocation(true) }
        }
    }
    // Given or taken in the system's settings meanwhile
    LifecycleResumeEffect(Unit) {
        refused = emptyList()
        recheck()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val permissions = result.keys.toTypedArray()
        FirstRun.markRequested(context, permissions)
        val row = ACCESSES.indexOfFirst { access -> access.permissions.any { it in result.keys } }
        if (row >= 0 && result.values.none { it } && FirstRun.refusedForGood(activity, permissions)) refused = refused + row
        recheck()
    }
    WizardStep(step, stringResource(R.string.app_name), stringResource(R.string.desc_welcome), stringResource(R.string.button_next), onNext) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ACCESSES.forEachIndexed { i, access ->
                AccessRow(i, access, granted[i]) {
                    if (i in refused) FirstRun.openAppSettings(context) else launcher.launch(access.permissions)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsNote(stringResource(R.string.desc_access))
    }
}

/** A segmented row of an access: its icon and what it is for, then a check when given, else Allow (SPEC §16). */
@Composable
private fun AccessRow(index: Int, access: Access, granted: Boolean, onAllow: () -> Unit) {
    SegmentedListItem(
        shapes = groupShapes(index, ACCESSES.size),
        colors = settingsRowColors(),
        leadingContent = { RowIcon(access.icon) },
        trailingContent = {
            if (granted) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.button_allow)) }
        },
        supportingContent = { Text(stringResource(access.supporting)) },
    ) {
        Text(stringResource(access.title))
    }
}

/** Step 2 (the family's question): start fresh or restore a backup, and Start, which acts on the choice. */
@Composable
private fun BackupStep(step: String, snackbars: SnackbarHostState, messageScope: CoroutineScope, onNext: () -> Unit) {
    var restore by rememberSaveable { mutableStateOf(false) }
    val restoreAction = rememberRestoreAction(snackbars, messageScope, onRestored = onNext)
    WizardStep(step, stringResource(R.string.title_wizard_backup), stringResource(R.string.desc_backup_no_media), stringResource(R.string.button_start), onConfirm = { if (restore) restoreAction.pick() else onNext() }) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsRadioRow(groupShapes(0, 2), stringResource(R.string.title_start_fresh), selected = !restore, onSelect = { restore = false }, supporting = stringResource(R.string.desc_start_fresh))
            SettingsRadioRow(groupShapes(1, 2), stringResource(R.string.title_restore_backup), selected = restore, onSelect = { restore = true }, supporting = stringResource(R.string.desc_restore_backup))
        }
    }
}

private const val LOCATION_ROW = 2

/** Start inset of the title and texts, which the logo lines up with. */
private val TITLE_INSET = 8.dp
