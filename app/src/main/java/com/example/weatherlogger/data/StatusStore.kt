package com.example.weatherlogger.data

import android.content.Context

/** Remembers the result of the last check so the screen can show it in plain words. */
object StatusStore {
    private const val FILE = "weather_status"
    private const val KEY = "last_status"

    fun save(context: Context, message: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, message).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "Not checked yet") ?: "Not checked yet"
}
