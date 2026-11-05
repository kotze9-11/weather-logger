package com.example.weatherlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.weatherlogger.worker.PollService
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
        val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Keeps checking in the background, even when the app screen is closed.
        ContextCompat.startForegroundService(this, Intent(this, PollService::class.java))

        val request = PeriodicWorkRequestBuilder<WeatherPollWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("weathercloud-zeus-poll", ExistingPeriodicWorkPolicy.KEEP, request)
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

/** Draws one smooth line through every point that has a value, bridging any gaps. */
private fun DrawScope.drawSeries(values: DoubleArray, color: Color, lo: Double, hi: Double) {
    val count = values.size
    val points = ArrayList<Offset>()
    for (i in 0 until count) {
        val v = values[i]
        if (v.isNaN()) continue
        val x = (i + 0.5f) * size.width / count
        val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
        points.add(Offset(x, y))
    }
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = 5f, center = points[0])
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val midX = (p0.x + p1.x) / 2f
        path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
    }
    drawPath(path, color, style = Stroke(width = 4f))
}

private fun compass(deg: Double): String {
    val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return names[((((deg % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16]
}

/** A Day / Week / Month line graph showing the min, average and max of one kind of reading. */
@Composable
private fun RangeChart(
    title: String,
    unit: String,
    readings: List<WeatherReading>,
    latestText: String?,
    minColor: Color,
    avgColor: Color,
    maxColor: Color,
    value: (WeatherReading) -> Double?
) {
    var range by remember { mutableStateOf("Day") }
    val now = System.currentTimeMillis()
    val hourMs = 3_600_000L
    val bucketMs = if (range == "Day") hourMs else 24 * hourMs
    val count = when (range) { "Day" -> 24; "Week" -> 7; else -> 30 }
    val offset = TimeZone.getDefault().getOffset(now).toLong()
    val endMs = ((now + offset) / bucketMs + 1) * bucketMs - offset
    val startMs = endMs - bucketMs * count

    val inRange = readings.filter { value(it) != null && it.observedAtEpochMs >= startMs && it.observedAtEpochMs < endMs }
    val mins = DoubleArray(count) { Double.NaN }
    val avgs = DoubleArray(count) { Double.NaN }
    val maxs = DoubleArray(count) { Double.NaN }
    val groups = inRange.groupBy { ((it.observedAtEpochMs - startMs) / bucketMs).toInt().coerceIn(0, count - 1) }
    for ((i, list) in groups) {
        val v = list.mapNotNull(value)
        if (v.isNotEmpty()) {
            mins[i] = v.minOrNull() ?: Double.NaN
            avgs[i] = v.average()
            maxs[i] = v.maxOrNull() ?: Double.NaN
        }
    }
    val all = inRange.mapNotNull(value)
    val overallMin = all.minOrNull()
    val overallMax = all.maxOrNull()
    val lo = floor(overallMin ?: 0.0)
    var hi = ceil(overallMax ?: 1.0)
    if (hi - lo < 2.0) hi = lo + 2.0

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val labelFormat = remember(range) { SimpleDateFormat(if (range == "Day") "HH:mm" else "dd MMM", Locale.getDefault()) }
    val labelIndexes = if (range == "Day") listOf(0, 6, 12, 18, 23) else listOf(0, count / 2, count - 1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title ($unit)", style = MaterialTheme.typography.titleMedium)
        Text(latestText ?: "Latest: no reading yet", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Day", "Week", "Month").forEach { label ->
                FilterChip(selected = range == label, onClick = { range = label }, label = { Text(label) })
            }
        }
        if (all.isEmpty()) {
            Text("No readings in this period yet")
        } else {
            Text("Min ${"%.1f".format(overallMin)} · Avg ${"%.1f".format(all.average())} · Max ${"%.1f".format(overallMax)} $unit")
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
private fun TemperatureChart(readings: List<WeatherReading>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val latest = readings.firstOrNull { it.temperatureC != null }
    val latestText = latest?.let { "Latest: ${"%.1f".format(it.temperatureC)} °C at ${timeFormat.format(Date(it.observedAtEpochMs))}" }
    RangeChart("Temperature", "°C", readings, latestText,
        Color(0xFFFF7070), Color(0xFFE00000), Color(0xFF8B1A1A)) { it.temperatureC }
}

@Composable
private fun WindChart(readings: List<WeatherReading>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val latest = readings.firstOrNull { it.windSpeedMs != null }
    val latestText = latest?.let { r ->
        buildString {
            append("Latest: ${"%.1f".format(r.windSpeedMs)} km/h")
            r.windDirectionDeg?.let { append(" from ${compass(it)}") }
            r.windGustMs?.let { append(", gusts ${"%.1f".format(it)} km/h") }
            append(" at ${timeFormat.format(Date(r.observedAtEpochMs))}")
        }
    }
    RangeChart("Wind speed", "km/h", readings, latestText,
        Color(0xFF7FB3FF), Color(0xFF1565C0), Color(0xFF0D2A5C)) { it.windSpeedMs }
}

/** Reads a CSV made by "Export all data to CSV" back into readings. Returns null for rows it can't read. */
private fun parseCsv(text: String): List<WeatherReading?> {
    val formats = listOf(
        SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()),
        SimpleDateFormat("dd MMM yyyy HH:mm", Locale.ENGLISH)
    )
    fun num(v: String?): Double? = v?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.toDoubleOrNull()
    return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(",")
        if (c.size < 13) return@map null
        val time = formats.firstNotNullOfOrNull { f -> try { f.parse(c[0].trim())?.time } catch (e: Exception) { null } }
            ?: return@map null
        WeatherReading(
            stationId = c[1].trim(),
            observedAtEpochMs = time,
            temperatureC = num(c[2]), humidityPct = num(c[3]), pressureHpa = num(c[4]),
            windSpeedMs = num(c[5]), windGustMs = num(c[6]), windDirectionDeg = num(c[7]),
            rainMm = num(c[8]), rainRateMmHr = num(c[9]), dewPointC = num(c[10]),
            solarRadiationWm2 = num(c[11]), uvIndex = num(c[12]),
            sourceUpdatedAtEpochMs = time
        )
    }.toList()
}

@Composable
private fun WeatherHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WeatherDatabase.get(context).weatherDao() }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exportMessage by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf(StatusStore.read(context)) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            statusText = StatusStore.read(context)
            unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
            delay(5000)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingCsv) }
            exportMessage = "CSV exported successfully"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            exportMessage = try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val rows = parseCsv(text)
                val existing = dao.allForExport("d5015340147").map { it.observedAtEpochMs / 60_000L }.toMutableSet()
                var added = 0
                for (r in rows.filterNotNull()) {
                    if (existing.add(r.observedAtEpochMs / 60_000L)) { dao.insert(r); added++ }
                }
                val skipped = rows.count { it == null }
                "Imported $added readings" + (if (skipped > 0) " ($skipped lines could not be read)" else "")
            } catch (e: Exception) {
                "Could not import that file"
            }
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
                        Text("The app checks WeatherCloud about every 5 minutes, even when the screen is closed. You can also press the button to check right now.", style = MaterialTheme.typography.bodySmall)
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
                        if (!unrestricted) {
                            Text("To keep checking when the phone is idle, allow the app to run without battery limits.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                )
                            }) { Text("Allow background running") }
                        }
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
            item { WindChart(readings) }
            item { HourlyRainChart(readings) }
            item {
                Button(onClick = { scope.launch {
                    val rows = dao.allForExport("d5015340147")
                    val header = "timestamp,station_id,temperature_c,humidity_pct,pressure_hpa,wind_speed_ms,wind_gust_ms,wind_direction_deg,rain_mm,rain_rate_mm_hr,dew_point_c,solar_radiation_wm2,uv_index\n"
                    val body = rows.joinToString("") { r -> listOf(dateFormat.format(Date(r.observedAtEpochMs)),r.stationId,r.temperatureC,r.humidityPct,r.pressureHpa,r.windSpeedMs,r.windGustMs,r.windDirectionDeg,r.rainMm,r.rainRateMmHr,r.dewPointC,r.solarRadiationWm2,r.uvIndex).joinToString(",") + "\n" }
                    pendingCsv = header + body
                    exportLauncher.launch("weathercloud-zeus-${System.currentTimeMillis()}.csv")
                } }) { Text("Export all data to CSV") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/*")) }) { Text("Import data from CSV") }
                if (exportMessage.isNotBlank()) Text(exportMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
