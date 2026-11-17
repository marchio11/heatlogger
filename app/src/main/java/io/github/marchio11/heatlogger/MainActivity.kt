package io.github.marchio11.heatlogger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.common.api.ApiException

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    // --- Permessi, uno alla volta (Android vuole così, soprattutto per la posizione "sempre")
    private var permStep = 0
    private var startAfterPermissions = false
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        nextPermissionStep()
    }

    private val driveLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val result = DriveAuth.client(this).getAuthorizationResultFromIntent(res.data)
            if (result.accessToken != null) driveConnected()
        } catch (e: ApiException) {
            toast("Collegamento a Drive non riuscito (codice ${e.statusCode})")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (prefs.running) {
                TrackingService.stop(this)
            } else {
                startPermissionFlow(start = true)
            }
            refresh()
        }
        findViewById<Button>(R.id.permissions).setOnClickListener { startPermissionFlow(start = false) }
        findViewById<Button>(R.id.drive).setOnClickListener { connectDrive() }
        findViewById<Button>(R.id.send).setOnClickListener {
            Uploads.qsNow(this)
            Uploads.driveNow(this)
            toast("Invio avviato")
        }
        findViewById<Button>(R.id.share).setOnClickListener { shareToday() }
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        // Registrazione accesa ma servizio sparito (es. dopo un aggiornamento): lo riavvio da qui
        if (prefs.running && TrackingService.instance == null) runCatching { TrackingService.start(this) }
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startPermissionFlow(start: Boolean) {
        startAfterPermissions = start
        permStep = 0
        nextPermissionStep()
    }

    private fun nextPermissionStep() {
        when (permStep++) {
            0 -> if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else nextPermissionStep()

            1 -> if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                AlertDialog.Builder(this)
                    .setTitle("Posizione «sempre»")
                    .setMessage("Per registrare anche a schermo spento e ripartire dopo un riavvio, nella prossima schermata scegli «Consenti sempre».")
                    .setPositiveButton("Continua") { _, _ -> permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
                    .setNegativeButton("Salta") { _, _ -> nextPermissionStep() }
                    .setCancelable(false)
                    .show()
            } else nextPermissionStep()

            2 -> if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                permLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            } else nextPermissionStep()

            3 -> if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else nextPermissionStep()

            4 -> {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName)) requestBatteryExemption()
                nextPermissionStep()
            }

            else -> finishPermissionFlow()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun finishPermissionFlow() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Senza il permesso di posizione non posso registrare")
            return
        }
        if (startAfterPermissions || prefs.running) {
            // riavvio il servizio così usa i permessi appena concessi
            if (prefs.running) TrackingService.stop(this)
            TrackingService.start(this)
        }
        refresh()
    }

    // --- Google Drive

    private fun connectDrive() {
        DriveAuth.client(this).authorize(DriveAuth.request())
            .addOnSuccessListener { result ->
                val pi = result.pendingIntent
                if (result.hasResolution() && pi != null) {
                    driveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    driveConnected()
                }
            }
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                AlertDialog.Builder(this)
                    .setTitle("Drive non collegato")
                    .setMessage(
                        if (code == 10) "Google non riconosce l'app (codice 10): manca la chiave «Android» nella Google Cloud Console, " +
                            "con nome pacchetto $packageName e l'impronta SHA-1 indicata nel README."
                        else "Errore: ${e.message}"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    private fun driveConnected() {
        prefs.setStatus("drive", "collegato, primo caricamento in corso…")
        Uploads.driveNow(this)
        toast("Google Drive collegato ✓")
    }

    // --- Condivisione

    private fun shareToday() {
        val file = Gpx.writeDay(this, Gpx.dayStart(System.currentTimeMillis()))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Condividi ${file.name}"))
    }

    // --- Stato

    private fun refresh() {
        val running = prefs.running && TrackingService.instance != null
        toggle.text = if (prefs.running) "Ferma registrazione" else "Avvia registrazione"
        val db = Db.get(this)
        val start = Gpx.dayStart(System.currentTimeMillis())
        val today = db.count(start, Gpx.nextDay(start))
        fun line(label: String, value: String) = if (value.isBlank()) "" else "$label: $value\n"
        val missing = buildList {
            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add("posizione «sempre»")
            if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) add("attività fisica")
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add("batteria senza restrizioni")
        }
        status.text = buildString {
            append(line("Registrazione", if (running) "attiva" else if (prefs.running) "in avvio…" else "ferma"))
            if (running) {
                append(line("Stato", prefs.status("mode")))
                append(line("Pausa", prefs.status("pause")))
                append(line("Frequenza", prefs.status("interval")))
                append(line("Attività rilevata", prefs.status("detected")))
                append(line("Riconoscimento", prefs.status("activity")))
            }
            append(line("Ultimo punto", prefs.status("last")))
            append(line("Punti oggi", today.toString()))
            append(line("Wi-Fi", prefs.status("ssid").ifBlank { "non collegato" }))
            if (prefs.qsEnabled) append(line("Server QS", prefs.status("qs").ifBlank { "in attesa" } + " · in coda ${db.unsentCount()}"))
            if (prefs.driveEnabled) append(line("Drive", prefs.status("drive").ifBlank { "non ancora collegato" }))
            if (missing.isNotEmpty()) append(line("⚠️ Mancano", missing.joinToString(", ")))
            append(line("🔧 Autocontrollo", prefs.status("watchdog")))
            append(line("💥 Ultimo errore", prefs.status("crash")))
            append("Versione: ${BuildConfig.VERSION_NAME}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
