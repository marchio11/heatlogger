package io.github.marchio11.heatlogger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
