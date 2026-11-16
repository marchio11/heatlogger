package io.github.marchio11.heatlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Sveglia del battito durante le pause: funziona anche con il telefono in Doze. */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingService.instance?.onHeartbeatAlarm()
    }
}
