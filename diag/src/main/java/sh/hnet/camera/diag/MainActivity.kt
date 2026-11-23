package sh.hnet.camera.diag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import sh.hnet.camera.diag.live.LiveTests
import sh.hnet.camera.diag.live.PreviewHost
import sh.hnet.camera.diag.live.QualityTests

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tests run for seconds with the phone held still; the screen must not dim meanwhile
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { DiagTheme { DiagScreen() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiagTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}

/** The preview a test draws into: a SurfaceView whose buffer gets the size the test asks for; waits until the surface has that size. */
private class SurfacePreview : PreviewHost, SurfaceHolder.Callback {
    var view: SurfaceView? = null
    private var wanted: Size? = null
    private var waiter: CompletableDeferred<Surface>? = null

    override suspend fun surface(size: Size): Surface = withContext(Dispatchers.Main) {
        val surfaceView = view?.takeIf { it.isAttachedToWindow } ?: error("the preview is not on screen")
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        if (holder.surface.isValid && frame.width() == size.width && frame.height() == size.height) return@withContext holder.surface
        val deferred = CompletableDeferred<Surface>()
        wanted = size
        waiter = deferred
        holder.setFixedSize(size.width, size.height)
        withTimeout(5000) { deferred.await() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (wanted == Size(width, height)) waiter?.complete(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiagScreen(model: DiagViewModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val preview = remember { SurfacePreview() }
    val tests = remember { LiveTests(context.applicationContext, preview).all + QualityTests(context.applicationContext, preview).all }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.msg_permission))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.button_allow)) }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The preview stays on screen, outside the list: a SurfaceView scrolled out of a lazy list is disposed with its surface, and the tests after it timed out waiting for it
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(135.dp).height(240.dp)) {
                    AndroidView(factory = { ctx -> SurfaceView(ctx).also { view -> preview.view = view; view.holder.addCallback(preview) } }, modifier = Modifier.fillMaxSize())
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.cameras.forEach { camera ->
                            FilterChip(selected = camera.id == state.selected, enabled = state.busy == null, onClick = { model.select(camera.id) }, label = { Text(stringResource(R.string.label_camera, camera.id, stringResource(facingLabel(camera.facing)))) })
                        }
                    }
                    Button(enabled = state.busy == null, onClick = { model.runProbes(context.display) }) { Text(stringResource(R.string.button_probes)) }
                    Button(enabled = state.busy == null, onClick = { model.runAll(tests, preview) }) { Text(stringResource(R.string.button_run_all)) }
                    if (state.busy != null) LoadingIndicator()
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.msg_aim), style = MaterialTheme.typography.bodyMedium) }
                if (state.probeSummary.isNotEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(state.probeSummary.joinToString("\n"), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(tests, key = { it.key }) { test ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(test.title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (state.busy == test.key) LoadingIndicator() else OutlinedButton(enabled = state.busy == null, onClick = { model.runTest(test, preview) }) { Text(stringResource(R.string.button_run)) }
                            }
                            state.results["${test.key}/${state.selected}"]?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                state.reportName?.let { name ->
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.msg_report, name), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
