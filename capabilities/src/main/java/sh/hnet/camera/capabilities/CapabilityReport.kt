package sh.hnet.camera.capabilities

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Display
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything the platform tells an app about the phone's cameras, without opening them; each section fails on its own. */
class CapabilityReport(private val context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val device = DeviceProbe(context)

    fun run(display: Display?): JsonObject = buildJsonObject {
        put("device", probe { device.device() })
        put("cameras", probe { CameraProbe(manager).run() })
        // Asking about a session without opening the camera needs Android 15
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) put("sessions", probe { SessionProbe(manager).run() })
        put("extensions", probe { ExtensionProbe(manager).run() })
        put("encoders", probe { CodecProbe().run() })
        if (display != null) put("display", probe { device.display(display) })
        put("audio", probe { device.audio() })
        put("sensors", probe { device.sensors() })
    }
}
