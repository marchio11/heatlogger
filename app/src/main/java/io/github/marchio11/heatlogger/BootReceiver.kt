package io.github.marchio11.heatlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia la registrazione dopo l'accensione del telefono o un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).running) return
        runCatching { TrackingService.start(context) }
        Uploads.schedule(context)
    }
}
