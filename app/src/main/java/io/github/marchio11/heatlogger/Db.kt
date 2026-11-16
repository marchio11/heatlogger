package io.github.marchio11.heatlogger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Point(
    val id: Long = 0,
    val time: Long,          // ms UTC
    val lat: Double,
    val lon: Double,
    val acc: Float?,
    val alt: Double?,
    val spd: Float?,         // m/s
    val dir: Float?,
    val prov: String?,
    val batt: Int?,
    val act: String?,        // vehicle|bicycle|walking|running|still
    val kind: String,        // fix|heartbeat|transition
)

data class ActivityRow(val id: Long, val type: String, val start: Long, val end: Long?, val confidence: Int)
data class PauseRow(val id: Long, val reason: String, val ssid: String?, val start: Long, val end: Long?)

/** Archivio locale: tutto viene salvato qui, poi inviato al QS e trasformato in GPX per Drive. */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "heatlogger.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(context: Context): Db = inst ?: synchronized(this) { inst ?: Db(context).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE points(
                id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, alt REAL, spd REAL, dir REAL,
                prov TEXT, batt INTEGER, act TEXT, kind TEXT NOT NULL, qs_sent INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX points_time ON points(time)")
        db.execSQL("CREATE INDEX points_qs ON points(qs_sent, time)")
        db.execSQL(
            """CREATE TABLE activities(
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, start INTEGER NOT NULL,
                end_time INTEGER, confidence INTEGER NOT NULL, qs_done INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE pauses(
                id INTEGER PRIMARY KEY AUTOINCREMENT, reason TEXT NOT NULL, ssid TEXT,
                start INTEGER NOT NULL, end_time INTEGER, qs_done INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------- Punti

    fun insertPoint(p: Point): Long = writableDatabase.insert("points", null, ContentValues().apply {
        put("time", p.time); put("lat", p.lat); put("lon", p.lon)
        p.acc?.let { put("acc", it) }; p.alt?.let { put("alt", it) }
        p.spd?.let { put("spd", it) }; p.dir?.let { put("dir", it) }
        put("prov", p.prov); p.batt?.let { put("batt", it) }
        put("act", p.act); put("kind", p.kind)
    })

    private fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.flt(i: Int): Float? = if (isNull(i)) null else getFloat(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.lngOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun readPoints(sql: String, args: Array<String>): List<Point> {
        val out = ArrayList<Point>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += Point(
                id = c.getLong(0), time = c.getLong(1), lat = c.getDouble(2), lon = c.getDouble(3),
                acc = c.flt(4), alt = c.dbl(5), spd = c.flt(6), dir = c.flt(7), prov = c.strOrNull(8),
                batt = c.intOrNull(9), act = c.strOrNull(10), kind = c.getString(11),
            )
        }
        return out
    }

    private val cols = "id,time,lat,lon,acc,alt,spd,dir,prov,batt,act,kind"

    fun pointsBetween(from: Long, to: Long): List<Point> =
        readPoints("SELECT $cols FROM points WHERE time >= ? AND time < ? ORDER BY time", arrayOf("$from", "$to"))

    fun unsentPoints(limit: Int): List<Point> =
        readPoints("SELECT $cols FROM points WHERE qs_sent = 0 ORDER BY time LIMIT $limit", arrayOf())

    fun markPointsSent(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                db.execSQL("UPDATE points SET qs_sent = 1 WHERE id IN (${chunk.joinToString(",")})")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(from: Long, to: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM points WHERE time >= ? AND time < ?", arrayOf("$from", "$to"))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun unsentCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM points WHERE qs_sent = 0", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lastPointTime(): Long? =
        readableDatabase.rawQuery("SELECT MAX(time) FROM points", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * Pulizia: elimina i dati più vecchi di [days] giorni (i GPX sono già su Drive).
     * Se l'invio al QS è attivo, elimina solo quelli già inviati.
     */
    fun prune(days: Int, onlySent: Boolean) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        val sent = if (onlySent) " AND qs_sent = 1" else ""
        val done = if (onlySent) " AND qs_done = 1" else ""
        writableDatabase.execSQL("DELETE FROM points WHERE time < $limit$sent")
        writableDatabase.execSQL("DELETE FROM activities WHERE start < $limit AND end_time IS NOT NULL$done")
        writableDatabase.execSQL("DELETE FROM pauses WHERE start < $limit AND end_time IS NOT NULL$done")
    }

    // ---------------- Attività (modo di spostamento)

    fun openActivity(type: String, start: Long, confidence: Int): Long =
        writableDatabase.insert("activities", null, ContentValues().apply {
            put("type", type); put("start", start); put("confidence", confidence)
        })

    fun closeActivity(id: Long, end: Long) {
        writableDatabase.execSQL("UPDATE activities SET end_time = ? WHERE id = ? AND end_time IS NULL", arrayOf<Any>(end, id))
    }

    fun updateActivityConfidence(id: Long, confidence: Int) {
        writableDatabase.execSQL("UPDATE activities SET confidence = ? WHERE id = ?", arrayOf<Any>(confidence, id))
    }

    fun pendingActivities(): List<ActivityRow> {
        val out = ArrayList<ActivityRow>()
        readableDatabase.rawQuery(
            "SELECT id,type,start,end_time,confidence FROM activities WHERE qs_done = 0 ORDER BY start LIMIT 2000", null
        ).use { c ->
            while (c.moveToNext()) out += ActivityRow(c.getLong(0), c.getString(1), c.getLong(2), c.lngOrNull(3), c.getInt(4))
        }
        return out
    }

    // ---------------- Pause

    fun openPause(reason: String, ssid: String?, start: Long): Long =
        writableDatabase.insert("pauses", null, ContentValues().apply {
            put("reason", reason); put("ssid", ssid); put("start", start)
        })

    fun closePause(id: Long, end: Long) {
        writableDatabase.execSQL("UPDATE pauses SET end_time = ? WHERE id = ? AND end_time IS NULL", arrayOf<Any>(end, id))
    }

    fun pendingPauses(): List<PauseRow> {
        val out = ArrayList<PauseRow>()
        readableDatabase.rawQuery(
            "SELECT id,reason,ssid,start,end_time FROM pauses WHERE qs_done = 0 ORDER BY start LIMIT 2000", null
        ).use { c ->
            while (c.moveToNext()) out += PauseRow(c.getLong(0), c.getString(1), c.strOrNull(2), c.getLong(3), c.lngOrNull(4))
        }
        return out
    }

    /** Le voci concluse e inviate non vanno più reinviate; quelle in corso sì (con la fine aggiornata). */
    fun markClosedDone(activityIds: List<Long>, pauseIds: List<Long>) {
        if (activityIds.isNotEmpty())
            writableDatabase.execSQL("UPDATE activities SET qs_done = 1 WHERE end_time IS NOT NULL AND id IN (${activityIds.joinToString(",")})")
        if (pauseIds.isNotEmpty())
            writableDatabase.execSQL("UPDATE pauses SET qs_done = 1 WHERE end_time IS NOT NULL AND id IN (${pauseIds.joinToString(",")})")
    }

    /** Dopo un riavvio dell'app chiude le voci rimaste aperte all'ultimo momento noto. */
    fun closeDangling(at: Long) {
        writableDatabase.execSQL("UPDATE activities SET end_time = ? WHERE end_time IS NULL", arrayOf<Any>(at))
        writableDatabase.execSQL("UPDATE pauses SET end_time = ? WHERE end_time IS NULL", arrayOf<Any>(at))
    }
}
