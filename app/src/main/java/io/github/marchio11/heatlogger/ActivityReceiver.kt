package io.github.marchio11.heatlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult

/** Riceve da Android l'attività riconosciuta (auto, a piedi, ...) e la passa al servizio. */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val best = result.mostProbableActivity
        TrackingService.instance?.onActivity(best.type, best.confidence)
    }
}
