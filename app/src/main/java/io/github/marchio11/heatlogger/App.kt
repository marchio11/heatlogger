package io.github.marchio11.heatlogger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registro degli errori: l'ultimo arresto anomalo resta visibile nella schermata principale
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val time = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(java.util.Date())
                val where = e.stackTrace.firstOrNull { it.className.contains("heatlogger") }?.let { "${it.fileName}:${it.lineNumber}" } ?: ""
                Prefs(this).sp.edit().putString("status_crash", "$time ${e.javaClass.simpleName}: ${e.message?.take(120)} $where").commit()
            }
            previous?.uncaughtException(t, e)
        }
        createChannel(this)
        fixOnce()
        Uploads.schedule(this)
    }

    /**
     * Correzioni puntuali dei dati, eseguite una volta sola.
     * 17/11/2026: passeggiata col cane dalle 14:20:39 rimasta "in auto" fino all'aggiornamento
     * alla 0.1.4 (14:57:56). Dopo la correzione il GPX del giorno viene ricaricato su Drive.
     */
    private fun fixOnce() {
        val sp = Prefs(this).sp
        if (sp.getBoolean("fix_20261117_walk", false)) return
        runCatching {
            Db.get(this).relabelVehicleAsWalking(1794921639000L, 1794923880000L)
            sp.edit().remove("drive_count_20261117").putBoolean("fix_20261117_walk", true).apply()
            Uploads.qsNow(this)
            Uploads.driveNow(this)
        }
    }
}
