package io.github.marchio11.heatlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Giorni (ora locale) e file GPX giornalieri, compatibili con la webapp Heatmap. */
object Gpx {

    /** Inizio del giorno locale che contiene [t]. */
    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextDay(start: Long): Long = Calendar.getInstance().apply {
        timeInMillis = start
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun dayName(start: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(start))

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * GPX 1.1 con tutti i punti del giorno. Nelle estensioni (namespace hl) ci sono precisione,
     * velocità, modo di spostamento e tipo di punto: la webapp può filtrare e classificare meglio.
     */
    fun build(points: List<Point>, name: String): String {
        val sb = StringBuilder(points.size * 300 + 600)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Heatlogger ${BuildConfig.VERSION_NAME}\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:hl=\"https://github.com/marchio11/heatlogger/gpx/1\">\n")
        sb.append("<trk><name>").append(esc(name)).append("</name><trkseg>\n")
        for (p in points) {
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.7f", p.lat))
                .append("\" lon=\"").append(String.format(Locale.US, "%.7f", p.lon)).append("\">")
            p.alt?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
            synchronized(iso) { sb.append("<time>").append(iso.format(Date(p.time))).append("</time>") }
            p.prov?.let { sb.append("<src>").append(esc(it)).append("</src>") }
            sb.append("<extensions>")
            p.acc?.let { sb.append("<hl:acc>").append(String.format(Locale.US, "%.1f", it)).append("</hl:acc>") }
            p.spd?.let { sb.append("<hl:spd>").append(String.format(Locale.US, "%.2f", it)).append("</hl:spd>") }
            p.dir?.let { sb.append("<hl:dir>").append(String.format(Locale.US, "%.0f", it)).append("</hl:dir>") }
            p.act?.let { sb.append("<hl:act>").append(it).append("</hl:act>") }
            sb.append("<hl:kind>").append(p.kind).append("</hl:kind>")
            sb.append("</extensions></trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Scrive il GPX del giorno nella cartella dell'app (per condividerlo o caricarlo). */
    fun writeDay(context: Context, dayStart: Long): File {
        val name = dayName(dayStart)
        val points = Db.get(context).pointsBetween(dayStart, nextDay(dayStart))
        val dir = File(context.filesDir, "gpx").apply { mkdirs() }
        val file = File(dir, "$name.gpx")
        file.writeText(build(points, name))
        return file
    }
}
