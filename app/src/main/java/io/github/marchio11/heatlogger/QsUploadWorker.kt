package io.github.marchio11.heatlogger

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invio a blocchi al server Quantified Self: punti, attività e pause in un'unica richiesta JSON.
 * I punti restano in coda finché il server non risponde OK; attività e pause "in corso"
 * vengono reinviate (con la fine aggiornata) finché non si chiudono. Il server scarta i doppioni.
 */
class QsUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private fun JSONObject.opt(key: String, v: Any?): JSONObject = put(key, v ?: JSONObject.NULL)

    private fun pointJson(p: Point) = JSONObject()
        .put("time", p.time)
        .put("lat", p.lat)
        .put("lon", p.lon)
        .opt("acc", p.acc?.toDouble())
        .opt("alt", p.alt)
        .opt("spd", p.spd?.toDouble())
        .opt("dir", p.dir?.toDouble())
        .opt("prov", p.prov)
        .opt("batt", p.batt)
        .opt("act", p.act)
        .put("kind", p.kind)

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.qsEnabled || prefs.qsUrl.isBlank()) return Result.success()
        val db = Db.get(applicationContext)
        val now = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())

        repeat(20) {
            val points = db.unsentPoints(5000)
            val activities = db.pendingActivities()
            val pauses = db.pendingPauses()
            if (points.isEmpty() && activities.isEmpty() && pauses.isEmpty()) {
                prefs.setStatus("qs", "OK alle $now, niente in coda")
                return Result.success()
            }

            val body = JSONObject()
                .put("device", prefs.qsDevice)
                .put("source", prefs.qsSource)
                .put("points", JSONArray().apply { points.forEach { put(pointJson(it)) } })
                .put("activities", JSONArray().apply {
                    activities.forEach {
                        put(JSONObject().put("type", it.type).put("start", it.start).opt("end", it.end).put("confidence", it.confidence))
                    }
                })
                .put("pauses", JSONArray().apply {
                    pauses.forEach {
                        put(JSONObject().put("reason", it.reason).opt("ssid", it.ssid).put("start", it.start).opt("end", it.end))
                    }
                })

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (prefs.qsToken.isNotBlank()) headers["Authorization"] = "Bearer ${prefs.qsToken}"

            val resp = try {
                Http.request(prefs.qsUrl, "POST", headers, body.toString().toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                prefs.setStatus("qs", "rete assente alle $now, riprovo")
                return Result.retry()
            }

            when {
                resp.ok -> {
                    db.markPointsSent(points.map { it.id })
                    db.markClosedDone(
                        activities.filter { it.end != null }.map { it.id },
                        pauses.filter { it.end != null }.map { it.id },
                    )
                    prefs.setStatus("qs", "OK alle $now (${points.size} punti inviati)")
                    if (points.size < 5000) return Result.success()
                }
                resp.code in 400..499 && resp.code != 408 && resp.code != 429 -> {
                    // Errore di configurazione (URL, token...): i dati restano in coda
                    prefs.setStatus("qs", "errore ${resp.code} alle $now: ${resp.body.take(120)}")
                    return Result.failure()
                }
                else -> {
                    prefs.setStatus("qs", "server non disponibile (${resp.code}) alle $now, riprovo")
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }
}
