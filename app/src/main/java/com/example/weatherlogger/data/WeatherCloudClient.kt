package com.example.weatherlogger.data

/**
 * WeatherCloud public-data adapter.
 *
 * WeatherCloud does not publish an official retrieval API. Public endpoints are reverse-engineered
 * and may change. Do not guess an endpoint or silently store fabricated values. The live endpoint
 * and JSON field mapping must be verified against the public Zeus station response before enabling
 * scheduled collection. Once verified, map the response into WeatherReading here.
 */
class WeatherCloudClient(
    private val stationId: String = "d5015340147"
) {
    suspend fun fetchLatest(): WeatherReading {
        throw NotImplementedError(
            "WeatherCloud endpoint contract has not yet been verified. " +
            "Connect the public station response and JSON mapping before enabling polling."
        )
    }
}
