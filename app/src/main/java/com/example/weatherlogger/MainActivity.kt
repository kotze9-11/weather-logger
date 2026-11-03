package com.example.weatherlogger

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherlogger.data.StatusStore
import com.example.weatherlogger.data.WeatherCloudClient
import com.example.weatherlogger.data.WeatherCloudException
import com.example.weatherlogger.data.WeatherDatabase
import com.example.weatherlogger.data.WeatherReading
import com.example.weatherlogger.worker.WeatherPollWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

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

/**
 * WeatherCloud's "rain" value is believed to be a running total for the day that resets at midnight.
 * Each step is how much rain fell between two readings (a drop in the total means a new day started).
 */
private fun rainSteps(readings: List<WeatherReading>): List<Pair<Long, Double>> {
    val sorted = readings.filter { it.rainMm != null }.sortedBy { it.observedAtEpochMs }
    return sorted.zipWithNext { a, b ->
        val prev = a.rainMm ?: 0.0
        val cur = b.rainMm ?: 0.0
        b.observedAtEpochMs to (if (cur >= prev) cur - prev else cur)
    }
}

private fun hourlyRain(readings: List<WeatherReading>, startMs: Long, hours: Int): DoubleArray {
    val buckets = DoubleArray(hours)
    val endMs = startMs + hours * 3_600_000L
    for ((time, mm) in rainSteps(readings)) {
        if (time < startMs || time >= endMs) continue
        val index = ((time - startMs) / 3_600_000L).toInt().coerceIn(0, hours - 1)
        buckets[index] += mm
    }
    return buckets
}

@Composable
private fun HourlyRainChart(readings: List<WeatherReading>) {
    val hours = 24
    val now = System.currentTimeMillis()
    val endMs = (now / 3_600_000L + 1) * 3_600_000L
    val startMs = endMs - hours * 3_600_000L
    val buckets = remember(readings, endMs) { hourlyRain(readings, startMs, hours) }
    val total = buckets.sum()
    val peak = buckets.maxOrNull() ?: 0.0
    val peakIndex = buckets.indices.maxByOrNull { buckets[it] } ?: 0
    val scaleMax = maxOf(peak, 1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val hourFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Text("Hourly rainfall (last 24 hours)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Total ${"%.1f".format(total)} mm" +
            if (peak > 0.0) " · wettest hour ${hourFormat.format(Date(startMs + peakIndex * 3_600_000L))} (${"%.1f".format(peak)} mm)"
            else " · no rain recorded"
    )
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val barWidth = size.width / hours
        buckets.forEachIndexed { i, mm ->
            val barHeight = (mm / scaleMax).toFloat() * size.height
            drawRect(
                color = barColor,
                topLeft = Offset(i * barWidth + barWidth * 0.1f, size.height - barHeight),
                size = Size(barWidth * 0.8f, barHeight)
            )
        }
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 6, 12, 18).forEach { i ->
            Text(hourFormat.format(Date(startMs + i * 3_600_000L)), style = MaterialTheme.typography.bodySmall)
        }
        Text("now", style = MaterialTheme.typography.bodySmall)
    }
    Text("Tallest possible bar = ${"%.1f".format(scaleMax)} mm", style = MaterialTheme.typography.bodySmall)
}

private fun DrawScope.drawSeries(values: DoubleArray, color: Color, lo: Double, hi: Double) {
    val count = values.size
    val path = Path()
    var penDown = false
    for (i in 0 until count) {
        val v = values[i]
        if (v.isNaN()) {
            penDown = false
            continue
        }
        val x = (i + 0.5f) * size.width / count
        val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
        penDown = true
        drawCircle(color, radius = 3f, center = Offset(x, y))
    }
    drawPath(path, color, style = Stroke(width = 4f))
}

@Composable
private fun TemperatureChart(readings: List<WeatherReading>) {
    var range by remember { mutableStateOf("Day") }
    val now = System.currentTimeMillis()
    val hourMs = 3_600_000L
    val bucketMs = if (range == "Day") hourMs else 24 * hourMs
    val count = when (range) { "Day" -> 24; "Week" -> 7; else -> 30 }
    val offset = TimeZone.getDefault().getOffset(now).toLong()
    val endMs = ((now + offset) / bucketMs + 1) * bucketMs - offset
    val startMs = endMs - bucketMs * count

    val inRange = readings.filter { it.temperatureC != null && it.observedAtEpochMs >= startMs && it.observedAtEpochMs < endMs }
    val mins = DoubleArray(count) { Double.NaN }
    val avgs = DoubleArray(count) { Double.NaN }
    val maxs = DoubleArray(count) { Double.NaN }
    val groups = inRange.groupBy { ((it.observedAtEpochMs - startMs) / bucketMs).toInt().coerceIn(0, count - 1) }
    for ((i, list) in groups) {
        val t = list.mapNotNull { it.temperatureC }
        if (t.isNotEmpty()) {
            mins[i] = t.minOrNull() ?: Double.NaN
            avgs[i] = t.average()
            maxs[i] = t.maxOrNull() ?: Double.NaN
        }
    }
    val all = inRange.mapNotNull { it.temperatureC }
    val overallMin = all.minOrNull()
    val overallMax = all.maxOrNull()
    val lo = floor(overallMin ?: 0.0)
    var hi = ceil(overallMax ?: 1.0)
    if (hi - lo < 2.0) hi = lo + 2.0

    val minColor = Color(0xFFFF7070)
    val avgColor = Color(0xFFE00000)
    val maxColor = Color(0xFF8B1A1A)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val labelFormat = remember(range) { SimpleDateFormat(if (range == "Day") "HH:mm" else "dd MMM", Locale.getDefault()) }
    val labelIndexes = if (range == "Day") listOf(0, 6, 12, 18, 23) else listOf(0, count / 2, count - 1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Temperature (°C)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Day", "Week", "Month").forEach { label ->
                FilterChip(selected = range == label, onClick = { range = label }, label = { Text(label) })
            }
        }
        if (all.isEmpty()) {
            Text("No temperature readings in this period yet")
        } else {
            Text("Min ${"%.1f".format(overallMin)} · Avg ${"%.1f".format(all.average())} · Max ${"%.1f".format(overallMax)} °C")
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${hi.toInt()}", style = MaterialTheme.typography.bodySmall)
                Text("${lo.toInt()}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).height(140.dp)) {
                for (k in 0..4) {
                    val y = size.height * k / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawSeries(mins, minColor, lo, hi)
                drawSeries(avgs, avgColor, lo, hi)
                drawSeries(maxs, maxColor, lo, hi)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labelIndexes.forEach { i ->
                Text(labelFormat.format(Date(startMs + i * bucketMs)), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("— Min", color = minColor, style = MaterialTheme.typography.bodySmall)
            Text("— Avg", color = avgColor, style = MaterialTheme.typography.bodySmall)
            Text("— Max", color = maxColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeatherHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WeatherDatabase.get(context).weatherDao() }
    var exportMessage by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf(StatusStore.read(context)) }
    var checking by remember { mutableStateOf(false) }
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
                        Text(statusText)
                        Text("The app checks WeatherCloud about every 15 minutes. You can also press the button to check right now.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !checking, onClick = {
                            scope.launch {
                                checking = true
                                val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
                                val message = try {
                                    val reading = WeatherCloudClient().fetchLatest()
                                    val last = dao.latest(reading.stationId)
                                    if (last?.observedAtEpochMs == reading.observedAtEpochMs) {
                                        "Checked $stamp: no new reading yet"
                                    } else {
                                        dao.insert(reading)
                                        "Checked $stamp: new reading saved"
                                    }
                                } catch (e: WeatherCloudException) {
                                    "Checked $stamp: ${e.message}"
                                } catch (e: Exception) {
                                    "Checked $stamp: could not connect (${e.javaClass.simpleName})"
                                }
                                StatusStore.save(context, message)
                                statusText = message
                                checking = false
                            }
                        }) { Text(if (checking) "Checking…" else "Check now") }
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
                val rain = rainSteps(readings).filter { it.first >= start }.sumOf { it.second }
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
            item { TemperatureChart(readings) }
            item { HourlyRainChart(readings) }
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

private fun compass(deg: Double): String {
    val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return names[((((deg % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16]
}

@Composable
private fun ReadingRow(reading: WeatherReading, dateFormat: SimpleDateFormat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(dateFormat.format(Date(reading.observedAtEpochMs)), style = MaterialTheme.typography.labelMedium)
            Text("Temp ${reading.temperatureC?.let { "$it °C" } ?: "—"}  ·  Humidity ${reading.humidityPct?.let { "$it %" } ?: "—"}")
            Text("Wind ${reading.windSpeedMs?.let { "$it km/h" } ?: "—"}  ·  Gust ${reading.windGustMs?.let { "$it km/h" } ?: "—"}  ·  Direction ${reading.windDirectionDeg?.let { "${compass(it)} (${it.toInt()}°)" } ?: "—"}")
            Text("Solar ${reading.solarRadiationWm2?.let { "$it W/m²" } ?: "—"}  ·  UV ${reading.uvIndex?.let { "$it" } ?: "—"}")
            Text("Pressure ${reading.pressureHpa?.let { "$it hPa" } ?: "—"}  ·  Rain ${reading.rainMm?.let { "$it mm" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
