package sh.hnet.camera.diag

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.CapabilityReport
import sh.hnet.camera.capabilities.Names
import sh.hnet.camera.diag.live.LiveTest
import sh.hnet.camera.diag.live.LiveTests
import sh.hnet.camera.diag.live.Media
import sh.hnet.camera.diag.live.PreviewHost

data class CameraChoice(val id: String, val label: String)

data class DiagState(
    val cameras: List<CameraChoice> = emptyList(),
    val selected: String? = null,
    val busy: String? = null,
    val probeSummary: List<String> = emptyList(),
    /** Test key + camera id → a short result line. */
    val results: Map<String, String> = emptyMap(),
    val reportName: String? = null,
)

/** Runs the probes and the live tests one at a time and keeps one report file per app run, rewritten after every step. */
class DiagViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = application.getSystemService(CameraManager::class.java)
    private val resolver = application.contentResolver
    private val json = Json { prettyPrint = true }
    private val sections = linkedMapOf<String, JsonElement>()
    private val live = linkedMapOf<String, JsonObject>()
    private var reportUri: Uri? = null
    private val reportName = "camera-diag_${Media.stamp()}.json"

    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state.asStateFlow()

    init {
        val cameras = manager.cameraIdList.map { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)?.let { Names.facing(it).lowercase() } ?: "?"
            CameraChoice(id, "$id ($facing)")
        }
        _state.update { it.copy(cameras = cameras, selected = cameras.firstOrNull()?.id) }
    }

    fun select(id: String) = _state.update { it.copy(selected = id) }

    fun runProbes(display: Display?) = exclusive("probes") {
        val report = withContext(Dispatchers.Default) { CapabilityReport(getApplication()).run(display) }
        report.forEach { (k, v) -> sections[k] = v }
        sections["cameraX"] = withContext(Dispatchers.Default) { runCatching { CameraXProbe(getApplication()).run() }.getOrElse { error(it) } }
        _state.update { it.copy(probeSummary = summarize()) }
    }

    fun runTest(test: LiveTest, preview: PreviewHost) {
        val camera = _state.value.selected ?: return
        exclusive(test.key) { runOne(test, camera, preview) }
    }

    fun runAll(tests: List<LiveTest>, preview: PreviewHost) {
        val camera = _state.value.selected ?: return
        exclusive("all") { tests.filter { it.inRunAll }.forEach { runOne(it, camera, preview) } }
    }

    private suspend fun runOne(test: LiveTest, camera: String, preview: PreviewHost) {
        _state.update { it.copy(busy = test.key) }
        val result = runCatching { test.run(camera) }.getOrElse { error(it) }
        live["${test.key} (camera $camera)"] = result
        _state.update { it.copy(results = it.results + ("${test.key}/$camera" to line(result))) }
        save()
    }

    private fun exclusive(name: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = name) }
        viewModelScope.launch {
            try {
                block()
                save()
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun error(e: Throwable) = buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }

    /** One line per result: the error or skip reason, else the compact JSON cut short (the file has all of it). */
    private fun line(result: JsonObject): String {
        (result["error"] ?: result["skipped"])?.let { return (it as? JsonPrimitive)?.content ?: it.toString() }
        val text = result.toString()
        return if (text.length > 600) text.take(600) + "…" else text
    }

    private fun summarize(): List<String> = buildList {
        val cameras = (sections["cameras"] as? JsonObject)?.get("cameras") as? JsonObject
        val sessions = sections["sessions"] as? JsonObject
        val extensions = sections["extensions"] as? JsonObject
        for (choice in _state.value.cameras) {
            val summary = (cameras?.get(choice.id) as? JsonObject)?.get("summary") as? JsonObject
            val outputs = summary?.get("largestOutputs") as? JsonObject
            val combos = (sessions?.get(choice.id) as? JsonObject)?.values?.mapNotNull { (it as? JsonObject)?.get("supported") as? JsonPrimitive }
            val ext = (extensions?.get(choice.id) as? JsonObject)?.get("supported")
            add("Camera ${choice.label}")
            add("  JPEG_R: ${outputs?.get("JPEG_R") ?: "no"}")
            add("  dynamic ranges: ${(summary?.get("dynamicRangeProfiles") as? JsonObject)?.keys ?: "?"}")
            add("  sessions supported: ${combos?.count { it.content == "true" } ?: "?"} of ${combos?.size ?: "?"}")
            add("  Camera2 extensions: ${ext?.let { runCatching { it.jsonArray.joinToString { e -> (e as JsonPrimitive).content } }.getOrDefault(it.toString()) } ?: "?"}")
        }
        (sections["cameraX"] as? JsonObject)?.forEach { (id, camera) ->
            val ext = (camera as? JsonObject)?.get("extensions")?.jsonObject?.filterValues { it is JsonObject }?.keys
            add("CameraX $id extensions: ${ext ?: "?"}")
        }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val report = buildJsonObject {
            sections.forEach { (k, v) -> put(k, v) }
            put("live", JsonObject(live))
        }
        val uri = reportUri ?: Media.newReport(resolver, reportName).also { reportUri = it }
        resolver.openOutputStream(uri, "wt")!!.use { it.write(json.encodeToString(JsonObject.serializer(), report).toByteArray()) }
        Media.publish(resolver, uri)
        _state.update { it.copy(reportName = "${Media.REPORT_DIR}/$reportName") }
    }
}
