package com.mukimov.salom

/** Creates the sound channel for every process entry, including background FCM delivery. */
class SalomApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        PushNotifications.createChannel(this)
    }
}
