package com.umer.voicesched

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.init(this)
    }
}
