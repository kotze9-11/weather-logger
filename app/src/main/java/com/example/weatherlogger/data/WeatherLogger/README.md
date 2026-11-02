# Weather Logger (Android) — source project

Starter Android project for a local WeatherCloud history logger for public station `d5015340147` (Zeus, Cape Town).

## Included
- Kotlin + Jetpack Compose app shell
- Room SQLite database for persistent readings
- Weather-reading schema: temperature, humidity, pressure, wind, gust, direction, rainfall, dew point, solar radiation and UV
- WorkManager periodic background worker (15-minute minimum periodic interval)
- Recent-record display and daily/weekly/monthly summary calculations (min/average/max temperature, average humidity, rainfall total)
- Export all locally stored readings to CSV via Android file picker (Excel-compatible)
- Android internet permissions

## Important: WeatherCloud connector is not yet activated
WeatherCloud's own FAQ says it does not offer an official data-retrieval API. An unofficial reverse-engineered public interface exists, but its endpoint and response schema must be validated against the actual station before polling is enabled. `data/WeatherCloudClient.kt` intentionally fails closed instead of guessing an endpoint or recording false data.

The public station ID is configured from the URL supplied by the user: `d5015340147`.

## Export
Use **Export all data to CSV** in the app. The Android file picker lets you save the file locally or to a cloud drive. Excel opens CSV files directly. Excel opens CSV files directly.

## Build
Open this folder in Android Studio (JDK 17, Android Gradle Plugin 8.7.3) and sync Gradle. Build > Build APK(s).

## Scheduling note
Android WorkManager periodic jobs have a 15-minute minimum and are inexact; Android controls the actual run time based on battery and background restrictions. The app therefore cannot promise an exact 10-minute schedule. A 15-minute collection interval is a practical background target, and the worker retries transient network errors.

## References
- WeatherCloud FAQ: https://weathercloud.net/en/faq
- Unofficial public client/API research: https://github.com/MauroDruwel/Weathercloud
