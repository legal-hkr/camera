package sh.hnet.camera.camera

import java.io.ByteArrayOutputStream
import java.net.URI

/** What a QR code holds (SPEC §7.10): a link handed to another app, a Wi-Fi network to join, a business card for the contacts, or text. */
sealed interface QrContent {
    val raw: String

    data class Link(override val raw: String) : QrContent {
        /** The address's host without "www.", for the suggestion chip ("example.com"). */
        val domain: String get() = runCatching { URI(raw.trim()).host }.getOrNull()?.removePrefix("www.") ?: raw.trim()
    }

    /** `WIFI:T:WPA;S:name;P:password;H:true;;` – [security] as written (WPA, SAE, WEP, nopass or none). */
    data class Wifi(override val raw: String, val ssid: String, val password: String?, val security: String?, val hidden: Boolean) : QrContent

    /**
     * A business card: vCard (2.1, 3.0, 4.0) or MECARD, with the fields shown in the sheet; [vcard] is what goes to the contacts app – the code's own vCard
     * unchanged, or one written from a MECARD.
     */
    data class Contact(
        override val raw: String,
        val vcard: String,
        val name: String?,
        val organization: String?,
        val title: String?,
        val phones: List<String>,
        val emails: List<String>,
        val urls: List<String>,
        val address: String?,
    ) : QrContent {
        /** What the chip and the sheet call the card: the name, else the organization. */
        val label: String? get() = name ?: organization
    }

    data class Text(override val raw: String) : QrContent {
        /** The first words for the chip: up to [MAX] characters, cut at a word. */
        val preview: String
            get() {
                val flat = raw.trim().replace(Regex("\\s+"), " ")
                if (flat.length <= MAX) return flat
                val cut = flat.take(MAX).substringBeforeLast(' ').ifEmpty { flat.take(MAX) }
                return "$cut…"
            }

        private companion object {
            const val MAX = 32
        }
    }

    companion object {
        private val SCHEMES = listOf("http://", "https://")

        fun of(text: String): QrContent {
            val trimmed = text.trim()
            if (SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) } && trimmed.none { it.isWhitespace() }) return Link(trimmed)
            if (trimmed.startsWith("WIFI:", ignoreCase = true)) wifi(trimmed)?.let { return it }
            if (trimmed.startsWith("BEGIN:VCARD", ignoreCase = true)) vcard(trimmed)?.let { return it }
            if (trimmed.startsWith("MECARD:", ignoreCase = true)) mecard(trimmed)?.let { return it }
            return Text(text)
        }

        /** The shown fields of a vCard: lines unfolded, a property's parameters set apart, quoted-printable decoded, text escapes undone. */
        private fun vcard(text: String): Contact? {
            val lines = mutableListOf<String>()
            for (line in text.replace("\r\n", "\n").split('\n')) {
                // A line starting with a space or a tab continues the one before (folding); quoted-printable continues with a soft break "="
                // The soft break first: its next line may start with a space that belongs to the value
                if (lines.isNotEmpty() && lines.last().endsWith("=") && lines.last().contains("QUOTED-PRINTABLE", ignoreCase = true)) lines[lines.lastIndex] = lines.last().dropLast(1) + line
                else if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += line.substring(1)
                else lines += line
            }
            var formatted: String? = null
            var structured: String? = null
            var organization: String? = null
            var title: String? = null
            var address: String? = null
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (line in lines) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val head = line.substring(0, colon).split(';')
                // Apple's groups ("item1.TEL") don't change the property
                val name = head[0].substringAfter('.').uppercase()
                val params = head.drop(1).map { it.uppercase() }
                var value = line.substring(colon + 1)
                if (params.any { "QUOTED-PRINTABLE" in it }) value = quotedPrintable(value, params.firstOrNull { it.startsWith("CHARSET=") }?.substringAfter('='))
                when (name) {
                    "FN" -> formatted = unescape(value).trim().ifEmpty { null }
                    "N" -> structured = fields(value).map { unescape(it).trim() }.let { parts -> listOfNotNull(parts.getOrNull(3), parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(0), parts.getOrNull(4)).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null } }
                    "ORG" -> organization = fields(value).map { unescape(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                    "TITLE" -> title = unescape(value).trim().ifEmpty { null }
                    "TEL" -> unescape(value).removePrefix("tel:").trim().takeIf { it.isNotEmpty() }?.let { phones += it }
                    "EMAIL" -> unescape(value).trim().takeIf { it.isNotEmpty() }?.let { emails += it }
                    "URL" -> unescape(value).trim().takeIf { it.isNotEmpty() }?.let { urls += it }
                    "ADR" -> if (address == null) address = fields(value).map { unescape(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                }
            }
            val name = formatted ?: structured
            if (name == null && organization == null && phones.isEmpty() && emails.isEmpty()) return null
            return Contact(text, text, name, organization, title, phones, emails, urls, address)
        }

        /** `MECARD:N:Kowalski,Jan;TEL:…;EMAIL:…;;` – `\` escapes; written out as a vCard 3.0 for the contacts app. */
        private fun mecard(text: String): Contact? {
            val fields = mutableListOf<Pair<String, String>>()
            val body = text.substring(7)
            var key = StringBuilder()
            var value = StringBuilder()
            var inValue = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> {
                        (if (inValue) value else key).append(body[i + 1])
                        i++
                    }
                    c == ':' && !inValue -> inValue = true
                    c == ';' -> {
                        if (key.isNotEmpty()) fields += key.toString().uppercase() to value.toString()
                        key = StringBuilder()
                        value = StringBuilder()
                        inValue = false
                    }
                    else -> (if (inValue) value else key).append(c)
                }
                i++
            }
            fun all(k: String) = fields.filter { it.first == k }.map { it.second.trim() }.filter { it.isNotEmpty() }
            // MECARD's name is "Last,First"
            val parts = all("N").firstOrNull()?.split(',')?.map { it.trim() }
            val name = parts?.let { listOfNotNull(it.getOrNull(1), it.getOrNull(0)).filter { p -> p.isNotEmpty() }.joinToString(" ").ifEmpty { null } }
            val organization = all("ORG").firstOrNull()
            val phones = all("TEL")
            val emails = all("EMAIL")
            if (name == null && organization == null && phones.isEmpty() && emails.isEmpty()) return null
            val urls = all("URL")
            val address = all("ADR").firstOrNull()
            fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
            val card = buildString {
                append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
                append("N:").append(esc(parts?.getOrNull(0).orEmpty())).append(';').append(esc(parts?.getOrNull(1).orEmpty())).append(";;;\r\n")
                append("FN:").append(esc(name ?: organization.orEmpty())).append("\r\n")
                organization?.let { append("ORG:").append(esc(it)).append("\r\n") }
                phones.forEach { append("TEL:").append(esc(it)).append("\r\n") }
                emails.forEach { append("EMAIL:").append(esc(it)).append("\r\n") }
                urls.forEach { append("URL:").append(esc(it)).append("\r\n") }
                address?.let { append("ADR:;;").append(esc(it)).append(";;;;\r\n") }
                all("NOTE").firstOrNull()?.let { append("NOTE:").append(esc(it)).append("\r\n") }
                append("END:VCARD\r\n")
            }
            return Contact(text, card, name, organization, null, phones, emails, urls, address)
        }

        /** A structured value's fields: split at the `;` that aren't escaped (`\;` stays in its field, unescaped later). */
        private fun fields(value: String): List<String> {
            val parts = mutableListOf<String>()
            val part = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '\\' && i + 1 < value.length -> {
                        part.append(c).append(value[i + 1])
                        i++
                    }
                    c == ';' -> {
                        parts += part.toString()
                        part.clear()
                    }
                    else -> part.append(c)
                }
                i++
            }
            parts += part.toString()
            return parts
        }

        /** vCard text escapes: `\n`, `\,`, `\;`, `\\`. */
        private fun unescape(value: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    val next = value[i + 1]
                    out.append(if (next == 'n' || next == 'N') '\n' else next)
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** Quoted-printable (vCard 2.1): `=XX` bytes in the declared charset, UTF-8 when none is given. */
        private fun quotedPrintable(value: String, charset: String?): String {
            val cs = charset?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '=' && i + 2 < value.length) {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                // A character left unencoded stays itself, in the declared charset
                if (c.code < 0x80) bytes.write(c.code) else bytes.write(c.toString().toByteArray(cs))
                i++
            }
            return String(bytes.toByteArray(), cs)
        }

        /** The fields of a `WIFI:` code; `\` escapes `;`, `,`, `:` and itself. Null without a network name. */
        private fun wifi(text: String): Wifi? {
            val fields = mutableMapOf<String, String>()
            val body = text.substring(5)
            var key = StringBuilder()
            var value = StringBuilder()
            var inValue = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> {
                        (if (inValue) value else key).append(body[i + 1])
                        i++
                    }
                    c == ':' && !inValue -> inValue = true
                    c == ';' -> {
                        if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
                        key = StringBuilder()
                        value = StringBuilder()
                        inValue = false
                    }
                    else -> (if (inValue) value else key).append(c)
                }
                i++
            }
            if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
            val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
            return Wifi(text, ssid, fields["P"]?.takeIf { it.isNotEmpty() }, fields["T"]?.takeIf { it.isNotEmpty() }, fields["H"].equals("true", ignoreCase = true))
        }
    }
}
