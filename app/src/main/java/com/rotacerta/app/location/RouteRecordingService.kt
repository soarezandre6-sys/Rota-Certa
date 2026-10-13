package com.rotacerta.app.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rotacerta.app.MainActivity
import com.rotacerta.app.data.RouteStore
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RouteRecordingService : Service() {

    data class Snapshot(
        val recording: Boolean,
        val startedAt: Long,
        val distanceMeters: Float,
        val points: List<RoutePoint>
    )

    interface Listener {
        fun onRecordingUpdated(snapshot: Snapshot)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RouteRecordingService = this@RouteRecordingService
    }

    private val binder = LocalBinder()
    private lateinit var recorder: RouteRecorder
    private var listener: Listener? = null
    private var recording = false
    private var startedAt = 0L
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        recorder = RouteRecorder(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FROM_NOTIFICATION -> stopAndAutoSave()
            ACTION_START, null -> startRecordingIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        listener = null
        return true
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onRecordingUpdated(snapshot())
    }

    fun isRecording(): Boolean = recording

    fun snapshot(): Snapshot = Snapshot(
        recording = recording,
        startedAt = startedAt,
        distanceMeters = recorder.currentDistanceMeters(),
        points = recorder.snapshot()
    )

    fun stopRecording(): Snapshot {
        val points = recorder.stop()
        val final = Snapshot(
            recording = false,
            startedAt = startedAt,
            distanceMeters = recorder.currentDistanceMeters(),
            points = points
        )
        recording = false
        listener?.onRecordingUpdated(final)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return final
    }

    private fun startRecordingIfNeeded() {
        if (recording) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        startedAt = System.currentTimeMillis()
        recording = true
        startAsForeground()

        recorder.start { _, _ ->
            val current = snapshot()
            listener?.onRecordingUpdated(current)
            val now = System.currentTimeMillis()
            if (now - lastNotificationUpdate >= NOTIFICATION_REFRESH_MS) {
                lastNotificationUpdate = now
                updateNotification(current)
            }
        }
    }

    private fun stopAndAutoSave() {
        if (!recording) {
            stopSelf()
            return
        }

        val final = stopRecording()
        if (final.points.size >= 2) {
            val now = System.currentTimeMillis()
            val label = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))
            RouteStore(this).save(
                RecordedRoute(
                    id = now,
                    name = "Rota $label",
                    startedAt = final.startedAt,
                    finishedAt = now,
                    distanceMeters = final.distanceMeters,
                    points = final.points
                )
            )
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(snapshot())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(snapshot: Snapshot) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: Snapshot) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Rota Certa • gravando")
            .setContentText(
                "%.2f km • %d pontos".format(
                    Locale.getDefault(),
                    snapshot.distanceMeters / 1_000f,
                    snapshot.points.size
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    10,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Parar e salvar",
                PendingIntent.getService(
                    this,
                    11,
                    Intent(this, RouteRecordingService::class.java).apply {
                        action = ACTION_STOP_FROM_NOTIFICATION
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gravação de rota",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém o GPS ativo enquanto uma rota está sendo gravada."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (recorder.isRunning()) recorder.stop()
        listener = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.rotacerta.app.action.START_RECORDING"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.rotacerta.app.action.STOP_RECORDING"
        private const val CHANNEL_ID = "route_recording"
        private const val NOTIFICATION_ID = 1201
        private const val NOTIFICATION_REFRESH_MS = 5_000L
    }
}
