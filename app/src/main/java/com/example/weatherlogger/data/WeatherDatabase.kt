package com.example.weatherlogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WeatherReading::class], version = 1, exportSchema = true)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    companion object {
        @Volatile private var INSTANCE: WeatherDatabase? = null
        fun get(context: Context): WeatherDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, WeatherDatabase::class.java, "weather-history.db").build().also { INSTANCE = it }
        }
    }
}
