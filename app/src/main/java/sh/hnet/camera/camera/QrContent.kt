package sh.hnet.camera.camera

import java.net.URI

/** What a QR code holds (SPEC §7.10): a link handed to another app, a Wi-Fi network to join, or text. */
sealed interface QrContent {
    val raw: String

    data class Link(override val raw: String) : QrContent {
        /** The address's host without "www.", for the suggestion chip ("example.com"). */
        val domain: String get() = runCatching { URI(raw.trim()).host }.getOrNull()?.removePrefix("www.") ?: raw.trim()
    }

    /** `WIFI:T:WPA;S:name;P:password;H:true;;` – [security] as written (WPA, SAE, WEP, nopass or none). */
    data class Wifi(override val raw: String, val ssid: String, val password: String?, val security: String?, val hidden: Boolean) : QrContent

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
            return Text(text)
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
