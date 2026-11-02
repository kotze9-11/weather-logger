package com.example.weatherlogger.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeatherCloudException(message: String) : Exception(message)

/**
 * Reads the latest numbers for a public WeatherCloud station.
 *
 * WeatherCloud has no official public API. This uses the same address the WeatherCloud website
 * uses itself. It may stop working at any time, and it may need a login cookie (see sessionCookie).
 * Field names below follow community documentation and have NOT been tested against the live
 * station yet. Anything missing is saved as empty rather than invented.
 */
class WeatherCloudClient(
    private val stationId: String = "d5015340147",
    private val sessionCookie: String? = null
) {
    private val deviceCode = stationId.removePrefix("d")
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatest(): WeatherReading = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://app.weathercloud.net/device/values?code=$deviceCode")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://app.weathercloud.net/$stationId")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) WeatherLogger/1.0")
        if (!sessionCookie.isNullOrBlank()) builder.header("Cookie", sessionCookie)

        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw WeatherCloudException("WeatherCloud replied with error ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw WeatherCloudException("WeatherCloud did not send readable data (it may want a login)")
            }
            val data = root.optJSONObject("values") ?: root
            val epochSeconds = data.optLong("epoch", 0L)
            if (epochSeconds <= 0L) {
                throw WeatherCloudException("WeatherCloud's reply had no time stamp")
            }
            val observedMs = epochSeconds * 1000L
            WeatherReading(
                stationId = stationId,
                observedAtEpochMs = observedMs,
                temperatureC = data.num("temp"),
                humidityPct = data.num("hum"),
                pressureHpa = data.num("bar"),
                windSpeedMs = data.num("wspdavg", "wspd"),
                windGustMs = data.num("wspdhi", "gust"),
                windDirectionDeg = data.num("wdir", "wdiravg"),
                rainMm = data.num("rain"),
                rainRateMmHr = data.num("rainrate"),
                dewPointC = data.num("dew"),
                solarRadiationWm2 = data.num("solarrad", "solar"),
                uvIndex = data.num("uvi", "uv"),
                sourceUpdatedAtEpochMs = observedMs
            )
        }
    }

    private fun JSONObject.num(vararg keys: String): Double? {
        for (key in keys) {
            if (has(key) && !isNull(key)) {
                val value = optDouble(key, Double.NaN)
                if (!value.isNaN()) return value
            }
        }
        return null
    }
}
