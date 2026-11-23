package sh.hnet.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit

/** The first run's own state (not settings: Reset and backups leave it): the wizard gone through, and which accesses were asked for. */
object FirstRun {
    private const val WIZARD_DONE = "wizard_done"

    private fun Context.firstRun() = getSharedPreferences("first_run", Context.MODE_PRIVATE)

    fun wizardDone(context: Context): Boolean = context.firstRun().getBoolean(WIZARD_DONE, false)

    fun markWizardDone(context: Context) = context.firstRun().edit { putBoolean(WIZARD_DONE, true) }

    fun granted(context: Context, permissions: Array<String>): Boolean = permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun requested(context: Context, permission: String): Boolean = context.firstRun().getBoolean("asked:$permission", false)

    fun markRequested(context: Context, permissions: Array<String>) = context.firstRun().edit { permissions.forEach { putBoolean("asked:$it", true) } }

    /**
     * Right after a request came back refused: whether the system refused for good (it stops showing its dialog after the second refusal), so only its settings
     * page helps. Asked only then: before a request the same answer also means a one-time grant expired or a dialog closed with Back.
     */
    fun refusedForGood(activity: Activity?, permissions: Array<String>): Boolean = activity != null && permissions.none { activity.shouldShowRequestPermissionRationale(it) }

    /** The app's page in the system's settings, where its permissions are. */
    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
