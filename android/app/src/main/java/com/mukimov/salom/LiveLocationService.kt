package com.mukimov.salom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LiveLocationService : Service(), LocationListener {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private lateinit var manager: LocationManager
    private var shareId = ""
    private var expires = 0L
    private var peer = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= expires) { stopShare(updateServer = true); return }
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() { super.onCreate(); manager = getSystemService(LOCATION_SERVICE) as LocationManager }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopShare(updateServer = true); return START_NOT_STICKY }
        val state=getSharedPreferences("live_location",MODE_PRIVATE)
        shareId = intent?.getStringExtra(EXTRA_ID) ?: state.getString(EXTRA_ID, "").orEmpty()
        peer = intent?.getStringExtra(EXTRA_PEER) ?: state.getString(EXTRA_PEER, "").orEmpty()
        expires = intent?.getLongExtra(EXTRA_EXPIRES, 0L)?.takeIf { it>0 } ?: state.getLong(EXTRA_EXPIRES,0L)
        if (shareId.isBlank()) { stopSelf(); return START_NOT_STICKY }
        if (expires <= System.currentTimeMillis()) { stopShare(updateServer=true); return START_NOT_STICKY }
        state.edit().putString(EXTRA_ID,shareId).putString(EXTRA_PEER,peer).putLong(EXTRA_EXPIRES,expires).apply()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification)
        pushLastKnown()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20_000L, 10f, this) }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 20_000L, 10f, this) }
        }
        handler.removeCallbacks(ticker); handler.postDelayed(ticker, 60_000)
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val id = shareId
        if (id.isNotBlank()) scope.launch { runCatching { DirectoryClient(this@LiveLocationService).updateLiveLocation(id, location.latitude, location.longitude) } }
    }

    private fun pushLastKnown() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permission) return
        val location = runCatching { listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
        location?.let { onLocationChanged(it) }
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun buildNotification(): Notification {
        val channel = "salom_live_location"
        if (Build.VERSION.SDK_INT >= 26) notificationManager().createNotificationChannel(NotificationChannel(channel, "Живая геопозиция", NotificationManager.IMPORTANCE_LOW).apply { description = "Показывает активную передачу геопозиции" })
        val stopIntent = Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 73, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mins = ((expires - System.currentTimeMillis()).coerceAtLeast(0) / 60_000L)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("Геопозиция отправляется")
            .setContentText("Для $peer · ещё ${mins} мин").setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Остановить", pending).build()
    }

    private fun stopShare(updateServer: Boolean) {
        handler.removeCallbacks(ticker)
        runCatching { manager.removeUpdates(this) }
        val id = shareId
        if (updateServer && id.isNotBlank()) scope.launch {
            runCatching { DirectoryClient(this@LiveLocationService).stopLiveLocation(id) }
            withContext(Dispatchers.Main) { finishStop() }
        } else finishStop()
    }

    private fun finishStop() {
        getSharedPreferences("live_location",MODE_PRIVATE).edit().clear().apply()
        notificationManager().cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker); runCatching { manager.removeUpdates(this) }; scope.cancel(); super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "share_id"
        const val EXTRA_PEER = "peer_name"
        const val EXTRA_EXPIRES = "expires_at"
        private const val ACTION_STOP = "com.mukimov.salom.STOP_LIVE_LOCATION"
        private const val NOTIFICATION_ID = 812
    }
}
