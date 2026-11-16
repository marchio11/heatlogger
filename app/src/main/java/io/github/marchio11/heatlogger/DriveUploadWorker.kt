package io.github.marchio11.heatlogger

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Carica su Google Drive il GPX dei giorni che hanno punti nuovi (oggi e i due giorni prima),
 * nella cartella scelta (predefinita "Heatlogger"). La webapp Heatmap li legge da lì.
 */
class DriveUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val prefs = Prefs(context)

    private class DriveError(val code: Int, msg: String) : Exception(msg)

    override fun doWork(): Result {
        if (!prefs.driveEnabled) return Result.success()
        val now = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())
        val token = try {
            DriveAuth.tokenBlocking(applicationContext)
        } catch (e: Exception) {
            prefs.setStatus("drive", "accesso non riuscito alle $now, riprovo")
            return Result.retry()
        }
        if (token == null) {
            prefs.setStatus("drive", "da collegare: tocca «Collega Google Drive»")
            return Result.success()
        }

        return try {
            val db = Db.get(applicationContext)
            val folder = folderId(token, prefs.driveFolder)
            var uploaded = 0
            for (start in lastDays(3)) {
                val count = db.count(start, Gpx.nextDay(start))
                val name = Gpx.dayName(start)
                val key = "drive_count_$name"
                if (count == 0 || prefs.getLong(key, -1L) == count.toLong()) continue
                val file = Gpx.writeDay(applicationContext, start)
                uploadFile(token, folder, "$name.gpx", file.readBytes())
                prefs.putLong(key, count.toLong())
                uploaded++
            }
            prefs.setStatus("drive", if (uploaded > 0) "caricato alle $now" else "aggiornato (controllo delle $now)")
            db.prune(90, onlySent = prefs.qsEnabled)
            Result.success()
        } catch (e: IOException) {
            prefs.setStatus("drive", "rete assente alle $now, riprovo")
            Result.retry()
        } catch (e: DriveError) {
            if (e.code == 404) {
                // cartella o file eliminati su Drive: li ricreo al prossimo giro
                prefs.sp.edit().apply {
                    prefs.sp.all.keys.filter { it.startsWith("drive_folder_id_") || it.startsWith("drive_file_") }.forEach { remove(it) }
                }.apply()
            }
            prefs.setStatus("drive", "errore ${e.code} alle $now: ${e.message?.take(100)}")
            Result.retry()
        }
    }

    private fun lastDays(n: Int): List<Long> {
        val out = ArrayList<Long>()
        val cal = Calendar.getInstance().apply { timeInMillis = Gpx.dayStart(System.currentTimeMillis()) }
        repeat(n) {
            out += cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return out
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun check(r: Http.Resp): Http.Resp {
        if (!r.ok) throw DriveError(r.code, r.body)
        return r
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun quote(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun folderId(token: String, name: String): String {
        val key = "drive_folder_id_$name"
        prefs.getStr(key)?.let { return it }
        val query = "name = '${quote(name)}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = check(Http.request(
            "https://www.googleapis.com/drive/v3/files?q=${q(query)}&fields=files(id)&spaces=drive", "GET", auth(token),
        ))
        val files = JSONObject(found.body).getJSONArray("files")
        val id = if (files.length() > 0) {
            files.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            val created = check(Http.request(
                "https://www.googleapis.com/drive/v3/files?fields=id", "POST",
                auth(token) + ("Content-Type" to "application/json; charset=UTF-8"),
                meta.toString().toByteArray(),
            ))
            JSONObject(created.body).getString("id")
        }
        prefs.putStr(key, id)
        return id
    }

    private fun uploadFile(token: String, folder: String, fileName: String, bytes: ByteArray) {
        val key = "drive_file_${folder}_$fileName"
        var id = prefs.getStr(key)
        if (id == null) {
            val query = "name = '${quote(fileName)}' and '$folder' in parents and trashed = false"
            val found = check(Http.request(
                "https://www.googleapis.com/drive/v3/files?q=${q(query)}&fields=files(id)&spaces=drive", "GET", auth(token),
            ))
            val files = JSONObject(found.body).getJSONArray("files")
            if (files.length() > 0) id = files.getJSONObject(0).getString("id")
        }
        if (id != null) {
            val r = Http.request(
                "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", "PATCH",
                auth(token) + ("Content-Type" to "application/gpx+xml"), bytes,
            )
            if (r.ok) { prefs.putStr(key, id); return }
            if (r.code != 404) throw DriveError(r.code, r.body)
            prefs.putStr(key, null)
        }
        // file nuovo: metadati + contenuto in un'unica richiesta
        val boundary = "heatlogger" + System.nanoTime()
        val meta = JSONObject().put("name", fileName).put("parents", org.json.JSONArray().put(folder))
            .put("mimeType", "application/gpx+xml")
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/gpx+xml\r\n\r\n"
        val tail = "\r\n--$boundary--"
        val body = head.toByteArray() + bytes + tail.toByteArray()
        val created = check(Http.request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST",
            auth(token) + ("Content-Type" to "multipart/related; boundary=$boundary"), body,
        ))
        prefs.putStr(key, JSONObject(created.body).getString("id"))
    }
}
