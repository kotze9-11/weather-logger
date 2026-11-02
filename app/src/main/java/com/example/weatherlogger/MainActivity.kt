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
