package com.szymi.mapy

import android.app.Application
import org.osmdroid.config.Configuration

class MapyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = USER_AGENT
        RouteStore.init(this)
    }
}
