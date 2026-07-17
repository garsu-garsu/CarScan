package com.bruni.carscan.platform.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A do-nothing foreground service.
 *
 * All the actual work — watching GPS speed, recording a trip — is `DrivingDetector` and
 * `GpsRecorder`, already running on the app-scoped `CoroutineScope` started in
 * `CarScanApplication`. That logic runs for as long as the process is alive; this service's only
 * job is to keep the process alive (and background location legal) once the screen goes off,
 * by holding an ongoing notification the OS won't kill for it.
 */
class TripTrackingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarScan")
            .setContentText("Tracking your drives")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TripTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripTrackingService::class.java))
        }
    }
}
