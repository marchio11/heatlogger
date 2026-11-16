package io.github.marchio11.heatlogger

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Impostazioni (modificabili dalla schermata Impostazioni) e piccolo stato condiviso. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun int(key: String, def: Int): Int =
        sp.getString(key, null)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: def

    private fun str(key: String, def: String = ""): String = sp.getString(key, null)?.trim() ?: def

    private fun list(key: String): List<String> =
        str(key).split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    // --- Intervalli di registrazione (secondi) per modo di spostamento
    val intervalVehicle get() = int("interval_vehicle", 15)
    val intervalBicycle get() = int("interval_bicycle", 30)
    val intervalRunning get() = int("interval_running", 30)
    val intervalWalking get() = int("interval_walking", 60)
    val intervalUnknown get() = int("interval_unknown", 45)

    // --- Pause e battito
    val heartbeatMinutes get() = int("heartbeat_minutes", 30)
    val stillMinutes get() = int("still_minutes", 4)
    val homeSsids get() = list("wifi_home")
    val workSsids get() = list("wifi_work")
    val vehicleKmh get() = int("vehicle_kmh", 25)

    // --- Invio al server Quantified Self
    val qsEnabled get() = sp.getBoolean("qs_enabled", false)
    val qsUrl get() = str("qs_url")
    val qsToken get() = str("qs_token")
    val qsDevice get() = str("qs_device", "pixel")
    val qsSource get() = str("qs_source", "heatlogger")
    val qsMinutes get() = int("qs_minutes", 10)

    // --- Google Drive
    val driveEnabled get() = sp.getBoolean("drive_enabled", true)
    val driveFolder get() = str("drive_folder", "Heatlogger")
    val driveMinutes get() = int("drive_minutes", 60)

    // --- Stato
    var running: Boolean
        get() = sp.getBoolean("state_running", false)
        set(v) = sp.edit().putBoolean("state_running", v).apply()

    fun status(key: String): String = sp.getString("status_$key", "") ?: ""
    fun setStatus(key: String, value: String) = sp.edit().putString("status_$key", value).apply()

    fun getLong(key: String, def: Long = 0L) = sp.getLong(key, def)
    fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    fun getStr(key: String): String? = sp.getString(key, null)
    fun putStr(key: String, v: String?) = sp.edit().putString(key, v).apply()
}
