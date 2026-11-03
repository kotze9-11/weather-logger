package com.example.weatherlogger.worker

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
import com.example.weatherlogger.data.StatusStore
import com.example.weatherlogger.data.WeatherCloudClient
import com.example.weatherlogger.data.WeatherCloudException
import com.example.weatherlogger.data.WeatherDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps checking WeatherCloud every few minutes, even when the app screen is closed. */
class PollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weather checking", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification("Checking WeatherCloud in the background")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun pollOnce() {
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val message = try {
            val reading = WeatherCloudClient().fetchLatest()
            val dao = WeatherDatabase.get(applicationContext).weatherDao()
            val last = dao.latest(reading.stationId)
            if (last?.observedAtEpochMs == reading.observedAtEpochMs) {
                "Checked $stamp: no new reading yet"
            } else {
                dao.insert(reading)
                "Checked $stamp: new reading saved"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WeatherCloudException) {
            "Checked $stamp: ${e.message}"
        } catch (e: Exception) {
            "Checked $stamp: could not connect (${e.javaClass.simpleName})"
        }
        StatusStore.save(applicationContext, message)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Weather Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "weather_check"
        private const val NOTIFICATION_ID = 42
        private const val INTERVAL_MS = 5 * 60 * 1000L
    }
}
