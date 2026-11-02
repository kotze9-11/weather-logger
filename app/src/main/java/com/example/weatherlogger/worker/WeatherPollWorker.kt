package com.example.weatherlogger.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.weatherlogger.data.StatusStore
import com.example.weatherlogger.data.WeatherCloudClient
import com.example.weatherlogger.data.WeatherCloudException
import com.example.weatherlogger.data.WeatherDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        return try {
            val reading = WeatherCloudClient().fetchLatest()
            val dao = WeatherDatabase.get(applicationContext).weatherDao()
            val last = dao.latest(reading.stationId)
            if (last?.observedAtEpochMs == reading.observedAtEpochMs) {
                StatusStore.save(applicationContext, "Checked $stamp: no new reading yet")
            } else {
                dao.insert(reading)
                StatusStore.save(applicationContext, "Checked $stamp: new reading saved")
            }
            Result.success()
        } catch (e: WeatherCloudException) {
            StatusStore.save(applicationContext, "Checked $stamp: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            StatusStore.save(applicationContext, "Checked $stamp: could not connect (${e.javaClass.simpleName})")
            Result.retry()
        }
    }
}
