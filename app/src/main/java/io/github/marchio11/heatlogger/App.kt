package io.github.marchio11.heatlogger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        Uploads.schedule(this)
    }
}
