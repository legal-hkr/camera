package sh.hnet.camera.ui.viewfinder

import android.view.KeyEvent

/** The volume keys while the viewfinder is shown (SPEC §7.9): the activity hands them here first; the viewfinder takes them or lets them change the volume. */
object HardwareKeys {
    /** Returns true when the viewfinder took the event. */
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return handler?.invoke(event) == true
    }
}
