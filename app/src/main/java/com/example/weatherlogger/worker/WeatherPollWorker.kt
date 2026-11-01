package com.example.weatherlogger.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.weatherlogger.data.WeatherCloudClient
import com.example.weatherlogger.data.WeatherDatabase

class WeatherPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val reading = WeatherCloudClient().fetchLatest()
        WeatherDatabase.get(applicationContext).weatherDao().insert(reading)
        Result.success()
    } catch (_: NotImplementedError) {
        // Deliberately fail until the WeatherCloud public endpoint has been verified.
        Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}
