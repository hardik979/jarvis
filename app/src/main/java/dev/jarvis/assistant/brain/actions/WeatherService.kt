package dev.jarvis.assistant.brain.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import dev.jarvis.assistant.voice.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Weather from Open-Meteo: free and no API key. */
class WeatherService(private val context: Context) {

    class Report(val place: String?, val now: Int, val code: Int, val high: Int, val low: Int, val rainChance: Int)

    enum class Problem { NoLocation, UnknownPlace, Offline }

    class Failure(val problem: Problem) : Exception()

    /** Weather for [placeName], or for the phone's location when it's null. Throws [Failure]. */
    suspend fun report(placeName: String?, lang: Lang, tomorrow: Boolean): Report = withContext(Dispatchers.IO) {
        val (place, lat, lon) = if (placeName != null) {
            geocode(placeName, lang)
        } else {
            here().let { Triple(null, it.latitude, it.longitude) }
        }
        val json = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=2"
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val day = if (tomorrow) 1 else 0
        Report(
            place = place,
            now = current.getDouble("temperature_2m").roundToInt(),
            code = if (tomorrow) daily.getJSONArray("weather_code").getInt(1) else current.getInt("weather_code"),
            high = daily.getJSONArray("temperature_2m_max").getDouble(day).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(day).roundToInt(),
            rainChance = daily.getJSONArray("precipitation_probability_max").optInt(day, 0),
        )
    }

    private fun geocode(name: String, lang: Lang): Triple<String, Double, Double> {
        val language = if (lang == Lang.Hindi) "hi" else "en"
        val json = getJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}" +
                "&count=1&language=$language&format=json"
        )
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: throw Failure(Problem.UnknownPlace)
        return Triple(result.getString("name"), result.getDouble("latitude"), result.getDouble("longitude"))
    }

    @SuppressLint("MissingPermission") // checked at the top
    private suspend fun here(): Location {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw Failure(Problem.NoLocation)
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val lastKnown = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        val fresh = lastKnown?.takeIf { System.currentTimeMillis() - it.time < THREE_HOURS_MS }
        return fresh ?: currentLocation(manager) ?: lastKnown ?: throw Failure(Problem.NoLocation)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager): Location? = withTimeoutOrNull(5_000) {
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return@withTimeoutOrNull null
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Failure(Problem.Offline)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            throw Failure(Problem.Offline)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L

        /** Plain words for WMO weather codes. */
        fun describe(code: Int, lang: Lang): String {
            val hindi = lang == Lang.Hindi
            return when (code) {
                0 -> if (hindi) "आसमान साफ" else "clear"
                1, 2 -> if (hindi) "हल्के बादल" else "partly cloudy"
                3 -> if (hindi) "बादल छाए" else "cloudy"
                45, 48 -> if (hindi) "कोहरा" else "foggy"
                in 51..57 -> if (hindi) "बूंदाबांदी" else "drizzly"
                in 61..67, in 80..82 -> if (hindi) "बारिश" else "rainy"
                in 71..77, 85, 86 -> if (hindi) "बर्फबारी" else "snowy"
                in 95..99 -> if (hindi) "आंधी-तूफान" else "stormy"
                else -> if (hindi) "मौसम सामान्य" else "fair"
            }
        }
    }
}
