package sh.hnet.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrContentTest {
    @Test fun links() {
        val link = QrContent.of("https://www.example.com/path?q=1") as QrContent.Link
        assertEquals("example.com", link.domain)
        assertTrue(QrContent.of("HTTP://hnet.sh") is QrContent.Link)
        // A sentence that starts with an address is text
        assertTrue(QrContent.of("https://a.b and more") is QrContent.Text)
    }

    @Test fun wifi() {
        val wifi = QrContent.of("WIFI:T:WPA;S:Dom;P:tajne\\;hasło;H:true;;") as QrContent.Wifi
        assertEquals("Dom", wifi.ssid)
        assertEquals("tajne;hasło", wifi.password)
        assertEquals("WPA", wifi.security)
        assertTrue(wifi.hidden)
        val open = QrContent.of("WIFI:S:Kawiarnia;T:nopass;;") as QrContent.Wifi
        assertNull(open.password)
        assertFalse(open.hidden)
        // Without a name it isn't a network
        assertTrue(QrContent.of("WIFI:T:WPA;P:x;;") is QrContent.Text)
    }

    @Test fun textPreview() {
        val text = QrContent.of("Zaproszenie na spotkanie w sobotę o dziesiątej rano") as QrContent.Text
        assertEquals("Zaproszenie na spotkanie w…", text.preview)
        assertEquals("Krótki", (QrContent.of("  Krótki \n") as QrContent.Text).preview)
    }
}
