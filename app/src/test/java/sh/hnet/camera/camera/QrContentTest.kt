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

class QrContactTest {
    @Test fun vcard3() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Kowalski;Jan;;;\r\nFN:Jan Kowalski\r\nORG:HNet\\, sp. z o.o.\r\nTITLE:Radca prawny\r\nTEL;TYPE=CELL:+48 600 100 200\r\nitem1.EMAIL;TYPE=INTERNET:jan@example.com\r\nURL:https://hnet.sh\r\nADR;TYPE=WORK:;;ul. Długa 1;Warszawa;;00-001;Polska\r\nEND:VCARD"
        val contact = QrContent.of(card) as QrContent.Contact
        assertEquals("Jan Kowalski", contact.name)
        assertEquals("HNet, sp. z o.o.", contact.organization)
        assertEquals("Radca prawny", contact.title)
        assertEquals(listOf("+48 600 100 200"), contact.phones)
        assertEquals(listOf("jan@example.com"), contact.emails)
        assertEquals("ul. Długa 1, Warszawa, 00-001, Polska", contact.address)
        // The code's own vCard goes to the contacts app unchanged
        assertEquals(card, contact.vcard)
    }

    @Test fun vcard21QuotedPrintableAndFolding() {
        val card = "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=C5=81ukasz;=C5=BBak\nTEL;WORK:22\n 1234567\nEND:VCARD"
        val contact = QrContent.of(card) as QrContent.Contact
        assertEquals("Żak Łukasz", contact.name)
        assertEquals(listOf("221234567"), contact.phones)
    }

    @Test fun mecard() {
        val contact = QrContent.of("MECARD:N:Nowak,Anna;TEL:+48111222333;EMAIL:anna@example.com;ORG:Kancelaria\\;Nowak;;") as QrContent.Contact
        assertEquals("Anna Nowak", contact.name)
        assertEquals("Kancelaria;Nowak", contact.organization)
        assertTrue(contact.vcard.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Nowak;Anna;;;\r\nFN:Anna Nowak\r\n"))
        assertTrue("ORG:Kancelaria\\;Nowak\r\n" in contact.vcard)
        assertTrue("TEL:+48111222333\r\n" in contact.vcard)
    }

    @Test fun escapesAndUnencodedLetters() {
        // An escaped ";" stays in its field; a letter left unencoded in a quoted-printable value stays itself; a soft break before a line starting with a space
        val card = "BEGIN:VCARD\nVERSION:2.1\nORG:Kowalski\\; Nowak;Dział\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Łukasz =C5=BBak=\n Junior\nEND:VCARD"
        val contact = QrContent.of(card) as QrContent.Contact
        assertEquals("Kowalski; Nowak, Dział", contact.organization)
        assertEquals("Łukasz Żak Junior", contact.name)
    }

    @Test fun emptyCardIsText() {
        assertTrue(QrContent.of("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD") is QrContent.Text)
    }
}
