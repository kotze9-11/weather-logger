package com.example.weatherlogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reading: WeatherReading): Long

    @Query("SELECT * FROM weather_readings WHERE stationId = :stationId ORDER BY observedAtEpochMs DESC")
    fun observeAll(stationId: String): Flow<List<WeatherReading>>

    @Query("SELECT * FROM weather_readings WHERE stationId = :stationId AND observedAtEpochMs BETWEEN :from AND :to ORDER BY observedAtEpochMs ASC")
    suspend fun between(stationId: String, from: Long, to: Long): List<WeatherReading>

    @Query("SELECT * FROM weather_readings WHERE stationId = :stationId ORDER BY observedAtEpochMs ASC")
    suspend fun allForExport(stationId: String): List<WeatherReading>

    @Query("SELECT * FROM weather_readings WHERE stationId = :stationId ORDER BY observedAtEpochMs DESC LIMIT 1")
    suspend fun latest(stationId: String): WeatherReading?

    @Query("SELECT COUNT(*) FROM weather_readings WHERE stationId = :stationId")
    fun count(stationId: String): Flow<Int>
}
