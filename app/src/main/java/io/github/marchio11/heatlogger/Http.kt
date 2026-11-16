package io.github.marchio11.heatlogger

import java.net.HttpURLConnection
import java.net.URL

/** Richieste HTTP minime, senza librerie esterne. */
object Http {
    class Resp(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            if (method == "PATCH") {
                // HttpURLConnection non conosce PATCH: le API Google accettano l'override
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }
}
