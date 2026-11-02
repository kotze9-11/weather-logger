package com.example.weatherlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weather_readings")
data class WeatherReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String,
    val observedAtEpochMs: Long,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val pressureHpa: Double? = null,
    val windSpeedMs: Double? = null,
    val windGustMs: Double? = null,
    val windDirectionDeg: Double? = null,
    val rainMm: Double? = null,
    val rainRateMmHr: Double? = null,
    val dewPointC: Double? = null,
    val solarRadiationWm2: Double? = null,
    val uvIndex: Double? = null,
    val sourceUpdatedAtEpochMs: Long? = null
)
