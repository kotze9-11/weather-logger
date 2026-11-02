package com.example.weatherlogger

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherlogger.data.StatusStore
import com.example.weatherlogger.data.WeatherDatabase
import com.example.weatherlogger.data.WeatherReading
import com.example.weatherlogger.worker.WeatherPollWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PeriodicWorkRequestBuilder<WeatherPollWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("weathercloud-zeus-poll", ExistingPeriodicWorkPolicy.KEEP, request)
        // Also check once right now, so you don't have to wait for the 15-minute timer.
        WorkManager.getInstance(this).enqueueUniqueWork("weathercloud-zeus-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WeatherPollWorker>().build())
        setContent { MaterialTheme { WeatherHome() } }
    }
}

@Composable
private fun WeatherHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WeatherDatabase.get(context).weatherDao() }
    var exportMessage by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingCsv) }
            exportMessage = "CSV exported successfully"
        }
    }
    val readings by dao.observeAll("d5015340147").collectAsState(initial = emptyList())
    val count by dao.count("d5015340147").collectAsState(initial = 0)
    var period by remember { mutableStateOf("Today") }
    val latest = readings.firstOrNull()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Weather Logger", style = MaterialTheme.typography.headlineMedium)
                Text("Zeus · WeatherCloud station d5015340147", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Connection status", style = MaterialTheme.typography.titleMedium)
                        Text(StatusStore.read(context))
                        Text("The app checks WeatherCloud about every 15 minutes. Close and reopen the app to refresh this message.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Local archive", style = MaterialTheme.typography.titleLarge)
                Text("Saved readings: $count")
                Text("Latest saved reading: ${latest?.let { dateFormat.format(Date(it.observedAtEpochMs)) } ?: "No readings yet"}")
            }
            item {
                val now = System.currentTimeMillis()
                val start = when (period) { "Today" -> now - 24L*60*60*1000; "Week" -> now - 7L*24*60*60*1000; else -> now - 30L*24*60*60*1000 }
                val window = readings.filter { it.observedAtEpochMs >= start }
                val temps = window.mapNotNull { it.temperatureC }
                val humidities = window.mapNotNull { it.humidityPct }
                val rain = window.mapNotNull { it.rainMm }.sum()
                Text("Statistics period", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today", "Week", "Month").forEach { label ->
                        FilterChip(selected = period == label, onClick = { period = label }, label = { Text(label) })
                    }
                }
                Text("${period} · ${window.size} readings")
                Text("Temperature min / avg / max: ${temps.minOrNull()?.let { "%.1f".format(it) } ?: "—"} / ${temps.takeIf { it.isNotEmpty() }?.average()?.let { "%.1f".format(it) } ?: "—"} / ${temps.maxOrNull()?.let { "%.1f".format(it) } ?: "—"} °C")
                Text("Average humidity: ${humidities.takeIf { it.isNotEmpty() }?.average()?.let { "%.0f".format(it) } ?: "—"}% · Rain total: ${"%.1f".format(rain)} mm")
            }
            item {
                Button(onClick = { scope.launch {
                    val rows = dao.allForExport("d5015340147")
                    val header = "timestamp,station_id,temperature_c,humidity_pct,pressure_hpa,wind_speed_ms,wind_gust_ms,wind_direction_deg,rain_mm,rain_rate_mm_hr,dew_point_c,solar_radiation_wm2,uv_index\n"
                    val body = rows.joinToString("") { r -> listOf(dateFormat.format(Date(r.observedAtEpochMs)),r.stationId,r.temperatureC,r.humidityPct,r.pressureHpa,r.windSpeedMs,r.windGustMs,r.windDirectionDeg,r.rainMm,r.rainRateMmHr,r.dewPointC,r.solarRadiationWm2,r.uvIndex).joinToString(",") + "\n" }
                    pendingCsv = header + body
                    exportLauncher.launch("weathercloud-zeus-${System.currentTimeMillis()}.csv")
                } }) { Text("Export all data to CSV") }
                if (exportMessage.isNotBlank()) Text(exportMessage, style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Recent records", style = MaterialTheme.typography.titleMedium) }
            items(readings.take(30)) { reading -> ReadingRow(reading, dateFormat) }
        }
    }
}

@Composable
private fun ReadingRow(reading: WeatherReading, dateFormat: SimpleDateFormat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(dateFormat.format(Date(reading.observedAtEpochMs)), style = MaterialTheme.typography.labelMedium)
            Text("Temp ${reading.temperatureC?.let { "$it °C" } ?: "—"}  ·  Humidity ${reading.humidityPct?.let { "$it %" } ?: "—"}")
            Text("Wind ${reading.windSpeedMs?.let { "$it m/s" } ?: "—"}  ·  Gust ${reading.windGustMs?.let { "$it m/s" } ?: "—"}  ·  Direction ${reading.windDirectionDeg?.let { "$it °" } ?: "—"}")
            Text("Solar ${reading.solarRadiationWm2?.let { "$it W/m²" } ?: "—"}  ·  UV ${reading.uvIndex?.let { "$it" } ?: "—"}")
            Text("Pressure ${reading.pressureHpa?.let { "$it hPa" } ?: "—"}  ·  Rain ${reading.rainMm?.let { "$it mm" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
