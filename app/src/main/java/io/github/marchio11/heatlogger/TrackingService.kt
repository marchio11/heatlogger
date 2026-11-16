package io.github.marchio11.heatlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Il motore di registrazione.
 *
 * - Intervallo adattivo in base al modo di spostamento (auto 15 s, a piedi 60 s, ...).
 * - Modo riconosciuto con l'API di Android (Activity Recognition); se manca il permesso, dalla velocità.
 * - Pausa sul Wi-Fi di casa/lavoro e quando si è fermi a lungo: il GPS si spegne e resta solo un
 *   "battito" leggero ogni 30 minuti. Le pause vengono registrate come eventi (inizio, fine, motivo).
 * - Un punto "transition" subito a ogni cambio di stato (partenza, arrivo, cambio di modo).
 * - Nessun punto viene scartato: precisione sempre salvata, filtrano server e webapp.
 */
@SuppressLint("MissingPermission")
class TrackingService : Service() {

    enum class Mode(val api: String?, val label: String) {
        VEHICLE("vehicle", "In auto"),
        BICYCLE("bicycle", "In bici"),
        RUNNING("running", "Di corsa"),
        WALKING("walking", "A piedi"),
        STILL("still", "Fermo"),
        UNKNOWN(null, "Rilevamento…"),
    }

    companion object {
        const val CHANNEL = "tracking"
        const val NOTIF_ID = 1

        @Volatile var instance: TrackingService? = null
            private set

        fun start(context: Context) {
            Prefs(context).running = true
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).running = false
            context.stopService(Intent(context, TrackingService::class.java))
        }

        fun modeOf(type: Int): Mode? = when (type) {
            DetectedActivity.IN_VEHICLE -> Mode.VEHICLE
            DetectedActivity.ON_BICYCLE -> Mode.BICYCLE
            DetectedActivity.RUNNING -> Mode.RUNNING
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Mode.WALKING
            DetectedActivity.STILL -> Mode.STILL
            else -> null
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var fused: FusedLocationProviderClient
    private val main = Handler(Looper.getMainLooper())

    // Modo di spostamento
    private var mode = Mode.UNKNOWN
    private var activityId: Long? = null
    private var candidate: Mode? = null
    private var candidateCount = 0
    private var activityUpdatesOn = false
    private var stillSince: Long? = null

    // Pausa in corso
    private var pauseId: Long? = null
    private var pauseReason: String? = null
    private var pauseSsid: String? = null
    private var pauseStart = 0L

    // Posizione
    private var lastFix: Location? = null
    private var lastInsertedTime = 0L
    private var anchor: Location? = null
    private var anchorTime = 0L
    private var fastFixes = 0
    private var slowSince: Long? = null
    private var requestKey: String? = null

    // Wi-Fi
    private var currentSsid: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLostAt: Long? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) handleLocation(loc, if (pauseId != null) "heartbeat" else "fix")
        }
    }

    private val stillCheck = Runnable { checkStill() }
    private val wifiEndCheck = Runnable { confirmWifiLost() }
    private val qsTick = object : Runnable {
        override fun run() {
            Uploads.qsNow(this@TrackingService)
            main.postDelayed(this, prefs.qsMinutes * 60_000L)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> Unit
            key.startsWith("interval_") || key == "heartbeat_minutes" -> { requestKey = null; applyRequest() }
            key.startsWith("wifi_") -> onWifi(currentSsid)
            key.startsWith("drive_") || key.startsWith("qs_minutes") -> Uploads.schedule(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        db = Db.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)

        createChannel(this)
        val notif = buildNotification("Avvio…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // Se l'app era stata chiusa di colpo, chiude attività e pause rimaste aperte
        db.closeDangling(db.lastPointTime() ?: System.currentTimeMillis())

        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        startActivityRecognition()
        startWifiMonitoring()
        applyRequest()
        recordTransition()
        Uploads.schedule(this)
        main.postDelayed(qsTick, 60_000L)
        updateStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        fused.removeLocationUpdates(locationCallback)
        if (activityUpdatesOn) ActivityRecognition.getClient(this).removeActivityUpdates(activityPendingIntent())
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        val now = System.currentTimeMillis()
        activityId?.let { db.closeActivity(it, now) }
        pauseId?.let { db.closePause(it, now) }
        prefs.setStatus("mode", "Fermato")
        prefs.setStatus("pause", "")
        Uploads.qsNow(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Posizione

    private fun intervalFor(m: Mode): Int = when (m) {
        Mode.VEHICLE -> prefs.intervalVehicle
        Mode.BICYCLE -> prefs.intervalBicycle
        Mode.RUNNING -> prefs.intervalRunning
        Mode.WALKING -> prefs.intervalWalking
        Mode.STILL, Mode.UNKNOWN -> prefs.intervalUnknown
    }

    private fun applyRequest() {
        val paused = pauseId != null
        val priority = if (paused) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        // Fermo senza riconoscimento dell'attività: battito più fitto, per accorgersi quando si riparte
        val seconds = when {
            !paused -> intervalFor(mode)
            pauseReason == "immobile" && !activityUpdatesOn -> 120
            else -> prefs.heartbeatMinutes * 60
        }
        val key = "$priority/$seconds"
        if (key == requestKey) return
        requestKey = key
        fused.removeLocationUpdates(locationCallback)
        val req = LocationRequest.Builder(priority, seconds * 1000L)
            .setMinUpdateIntervalMillis(seconds * 1000L)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        prefs.setStatus("interval", if (paused) "battito ogni ${prefs.heartbeatMinutes} min" else "un punto ogni $seconds s")
    }

    /** Un punto subito, per segnare con precisione partenze, arrivi e cambi di modo. */
    private fun recordTransition() {
        val req = CurrentLocationRequest.Builder()
            .setPriority(if (pauseId != null) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(15_000L)
            .setDurationMillis(30_000L)
            .build()
        fused.getCurrentLocation(req, CancellationTokenSource().token)
            .addOnSuccessListener { loc -> if (loc != null) handleLocation(loc, "transition") }
    }

    private fun battery(): Int? = runCatching {
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 0..100 }

    private fun handleLocation(loc: Location, kind: String) {
        if (loc.time == lastInsertedTime) return // stessa posizione già salvata
        lastInsertedTime = loc.time
        val prev = lastFix
        val spd: Float? = when {
            loc.hasSpeed() -> loc.speed
            prev != null && loc.time > prev.time -> prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f)
            else -> null
        }
        db.insertPoint(
            Point(
                time = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                acc = if (loc.hasAccuracy()) loc.accuracy else null,
                alt = if (loc.hasAltitude()) loc.altitude else null,
                spd = spd,
                dir = if (loc.hasBearing()) loc.bearing else null,
                prov = loc.provider ?: "fused",
                batt = battery(),
                act = if (pauseId != null) Mode.STILL.api else mode.api,
                kind = kind,
            )
        )
        if (kind != "heartbeat") lastFix = loc
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        prefs.setStatus("last", "${timeFmt.format(Date(loc.time))} · ±${acc.toInt()} m · $kind")

        if (pauseId != null) {
            // Fermo "immobile" ma il battito dice che ci siamo spostati parecchio: riprendo
            val a = anchor
            if (pauseReason == "immobile" && a != null && acc < 100 && loc.distanceTo(a) > 150) endPause(System.currentTimeMillis())
            updateStatus()
            return
        }

        val kmh = (spd ?: 0f) * 3.6f
        // Senza riconoscimento dell'attività: decide la velocità
        if (!activityUpdatesOn || mode == Mode.UNKNOWN) {
            if (kmh > prefs.vehicleKmh && acc < 100) {
                fastFixes++
                if (fastFixes >= 2 && mode != Mode.VEHICLE) setMode(Mode.VEHICLE, 60)
            } else {
                fastFixes = 0
            }
        }
        if (!activityUpdatesOn && mode == Mode.VEHICLE) {
            if (kmh < 12) {
                val since = slowSince ?: loc.time.also { slowSince = it }
                if (loc.time - since > 120_000L) setMode(Mode.WALKING, 50)
            } else {
                slowSince = null
            }
        }

        // Fermo nello stesso posto per qualche minuto (e non in auto, es. al semaforo): pausa
        val a = anchor
        if (a == null || loc.distanceTo(a) > max(40f, acc)) {
            anchor = loc
            anchorTime = loc.time
        } else if (mode != Mode.VEHICLE && loc.time - anchorTime >= prefs.stillMinutes * 60_000L) {
            startPause("immobile", null)
        }
        updateStatus()
    }

    // ------------------------------------------------------------------ Modo di spostamento

    private fun activityPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 7, Intent(this, ActivityReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun startActivityRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            activityUpdatesOn = false
            prefs.setStatus("activity", "permesso \"Attività fisica\" mancante: uso la velocità")
            return
        }
        ActivityRecognition.getClient(this).requestActivityUpdates(20_000L, activityPendingIntent())
            .addOnSuccessListener {
                activityUpdatesOn = true
                prefs.setStatus("activity", "riconoscimento attivo")
            }
            .addOnFailureListener {
                activityUpdatesOn = false
                prefs.setStatus("activity", "riconoscimento non disponibile: uso la velocità")
            }
    }

    /** Chiamato da [ActivityReceiver] con l'attività più probabile. */
    fun onActivity(type: Int, confidence: Int) {
        val m = modeOf(type) ?: return
        prefs.setStatus("detected", "${m.label} ($confidence%)")
        // Sul Wi-Fi di casa/lavoro i movimenti tra una stanza e l'altra non contano
        if (pauseReason?.startsWith("wifi") == true) return

        if (m == candidate) candidateCount++ else { candidate = m; candidateCount = 1 }
        val accepted = confidence >= 75 || (candidateCount >= 2 && confidence >= 50)
        if (!accepted) return

        if (m == Mode.STILL) {
            if (stillSince == null) stillSince = System.currentTimeMillis()
            if (mode != Mode.STILL && pauseId == null) setMode(Mode.STILL, confidence)
            main.removeCallbacks(stillCheck)
            main.postDelayed(stillCheck, prefs.stillMinutes * 60_000L)
        } else {
            stillSince = null
            main.removeCallbacks(stillCheck)
            if (pauseReason == "immobile") endPause(System.currentTimeMillis())
            if (m != mode) setMode(m, confidence)
        }
    }

    private fun checkStill() {
        val since = stillSince ?: return
        if (pauseId == null && mode == Mode.STILL && System.currentTimeMillis() - since >= prefs.stillMinutes * 60_000L - 1000) {
            startPause("immobile", null)
        }
    }

    private fun setMode(m: Mode, confidence: Int) {
        val now = System.currentTimeMillis()
        activityId?.let { db.closeActivity(it, now) }
        activityId = m.api?.let { db.openActivity(it, now, confidence) }
        mode = m
        fastFixes = 0
        slowSince = null
        recordTransition()
        applyRequest()
        updateStatus()
    }

    // ------------------------------------------------------------------ Pause

    private fun startPause(reason: String, ssid: String?) {
        if (pauseReason == reason && pauseSsid == ssid) return
        val now = System.currentTimeMillis()
        if (pauseId != null) closePauseRow(now)
        pauseId = db.openPause(reason, ssid, now)
        pauseReason = reason
        pauseSsid = ssid
        pauseStart = now
        if (mode != Mode.STILL) {
            activityId?.let { db.closeActivity(it, now) }
            activityId = db.openActivity(Mode.STILL.api!!, now, 100)
            mode = Mode.STILL
        }
        anchor = lastFix
        main.removeCallbacks(stillCheck)
        recordTransition()
        applyRequest()
        updateStatus()
    }

    private fun closePauseRow(end: Long) {
        pauseId?.let { db.closePause(it, end) }
        pauseId = null
        pauseReason = null
        pauseSsid = null
    }

    private fun endPause(end: Long) {
        if (pauseId == null) return
        closePauseRow(end)
        val now = System.currentTimeMillis()
        activityId?.let { db.closeActivity(it, now) }
        activityId = null
        mode = Mode.UNKNOWN
        stillSince = null
        candidate = null
        anchor = null
        recordTransition()
        applyRequest()
        updateStatus()
    }

    // ------------------------------------------------------------------ Wi-Fi

    private fun ssidOf(caps: NetworkCapabilities?): String? {
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps?.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        }
        val ssid = info?.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")
        return ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    private fun startWifiMonitoring() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb: ConnectivityManager.NetworkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ssid = ssidOf(caps); main.post { onWifi(ssid) }
                }
                override fun onLost(network: Network) { main.post { onWifi(null) } }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ssid = ssidOf(caps); main.post { onWifi(ssid) }
                }
                override fun onLost(network: Network) { main.post { onWifi(null) } }
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb); netCallback = cb }
    }

    private fun onWifi(ssid: String?) {
        currentSsid = ssid
        prefs.setStatus("ssid", ssid ?: "")
        val reason = when {
            ssid == null -> null
            prefs.homeSsids.contains(ssid) -> "wifi_casa"
            prefs.workSsids.contains(ssid) -> "wifi_lavoro"
            else -> null
        }
        if (reason != null) {
            wifiLostAt = null
            main.removeCallbacks(wifiEndCheck)
            startPause(reason, ssid)
        } else if (pauseReason?.startsWith("wifi") == true && wifiLostAt == null) {
            // Il Wi-Fi a volte cade per pochi secondi: aspetto un minuto prima di chiudere la pausa
            wifiLostAt = System.currentTimeMillis()
            main.removeCallbacks(wifiEndCheck)
            main.postDelayed(wifiEndCheck, 60_000L)
        }
        updateStatus()
    }

    private fun confirmWifiLost() {
        val lost = wifiLostAt ?: return
        wifiLostAt = null
        if (pauseReason?.startsWith("wifi") == true) endPause(lost)
    }

    // ------------------------------------------------------------------ Stato e notifica

    private fun pauseLabel(): String = when (pauseReason) {
        "wifi_casa" -> "In pausa: Wi-Fi di casa"
        "wifi_lavoro" -> "In pausa: Wi-Fi del lavoro"
        "immobile" -> "In pausa: fermo"
        else -> ""
    }

    private fun updateStatus() {
        val pause = pauseLabel()
        prefs.setStatus("mode", if (pause.isNotEmpty()) "Fermo" else mode.label)
        prefs.setStatus("pause", if (pause.isNotEmpty()) "$pause dalle ${SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(pauseStart))}" else "")
        val start = Gpx.dayStart(System.currentTimeMillis())
        val today = db.count(start, Gpx.nextDay(start))
        val text = (if (pause.isNotEmpty()) pause else mode.label) + " · $today punti oggi"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Heatlogger")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

fun createChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(TrackingService.CHANNEL) == null) {
        nm.createNotificationChannel(
            NotificationChannel(TrackingService.CHANNEL, "Registrazione", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifica fissa mentre Heatlogger registra"
                setShowBadge(false)
            }
        )
    }
}
