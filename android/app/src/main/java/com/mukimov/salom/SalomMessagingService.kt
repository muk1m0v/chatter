package com.mukimov.salom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object PushNotifications {
    const val CHANNEL = "salom_messages"
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Новые сообщения в Салом"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }
    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun sync(context: Context) {
        createChannel(context)
        WorkManager.getInstance(context).enqueueUniqueWork("salom-push-registration", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val local = applicationContext.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
        // Keep a disabled registration after sign-out so old pushes stop on this installation.
        if (local.getString("name", "").isNullOrBlank()) return Result.success()
        val token = suspendCancellableCoroutine<String?> { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
            }
        } ?: return Result.retry()
        return try {
            DirectoryClient(applicationContext).registerPush(token,
                local.getString("signed_in", "") == "yes" && PushNotifications.allowed(applicationContext))
            Result.success()
        } catch (exception: DirectoryException) { Result.retry() }
    }
}

class SalomMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushNotifications.sync(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        if (getSharedPreferences("salom_local", MODE_PRIVATE).getString("signed_in", "") != "yes") return
        if (!PushNotifications.allowed(this)) return
        val peerId = message.data["peer_id"] ?: return
        val messageId = message.data["message_id"] ?: return
        PushNotifications.createChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("peer_id", peerId)
            data = android.net.Uri.parse("salom://chat/$peerId")
        }
        val pending = PendingIntent.getActivity(this, peerId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = message.notification?.title ?: "Салом"
        val body = message.notification?.body ?: "Новое сообщение"
        val notification = NotificationCompat.Builder(this, PushNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(this).notify("salom-$messageId", 0, notification) }
        catch (_: SecurityException) { /* Permission can be revoked between checking and posting. */ }
    }
}
