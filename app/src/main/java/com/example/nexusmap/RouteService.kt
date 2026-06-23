package com.example.nexusmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class TrafficLevel { NORMAL, MEDIUM, HEAVY }

data class TrafficSegment(val points: List<GeoPoint>, val level: TrafficLevel)

data class RouteStep(val instruction: String, val distanceM: Int, val icon: String)

data class RouteResult(
    val segments: List<TrafficSegment>,
    val distanceM: Int,
    val durationSec: Int,
    val via: String,
    val steps: List<RouteStep>
)

data class SearchResult(val displayName: String, val shortName: String, val lat: Double, val lon: Double)

object RouteService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=5&addressdetails=0"
            val req = Request.Builder().url(url).header("User-Agent", "NexusMapApp/1.0").build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val display = obj.getString("display_name")
                val short = display.split(",").take(2).joinToString(", ").trim()
                SearchResult(display, short, obj.getDouble("lat"), obj.getDouble("lon"))
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun geocode(query: String): GeoPoint? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=1"
            val req = Request.Builder().url(url).header("User-Agent", "NexusMapApp/1.0").build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null
            val obj = arr.getJSONObject(0)
            GeoPoint(obj.getDouble("lat"), obj.getDouble("lon"))
        } catch (e: Exception) { null }
    }

    suspend fun getRoute(from: GeoPoint, to: GeoPoint, tomtomKey: String): RouteResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${from.longitude},${from.latitude};" +
                "${to.longitude},${to.latitude}" +
                "?overview=full&geometries=geojson&steps=true"
            val req = Request.Builder().url(url).header("User-Agent", "NexusMapApp/1.0").build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext null
            val json = JSONObject(body)
            if (json.getString("code") != "Ok") return@withContext null

            val route = json.getJSONArray("routes").getJSONObject(0)
            val distanceM = route.getDouble("distance").toInt()
            val durationSec = route.getDouble("duration").toInt()

            // Parse geometry
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val allPoints = (0 until coords.length()).map { i ->
                val c = coords.getJSONArray(i)
                GeoPoint(c.getDouble(1), c.getDouble(0))
            }

            // Via name from first leg
            val legs = route.getJSONArray("legs")
            val via = if (legs.length() > 0) {
                val leg = legs.getJSONObject(0)
                if (leg.has("summary") && leg.getString("summary").isNotEmpty())
                    leg.getString("summary") else "Fastest Route"
            } else "Fastest Route"

            // Parse steps
            val steps = mutableListOf<RouteStep>()
            for (li in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(li).getJSONArray("steps")
                for (si in 0 until minOf(legSteps.length(), 20)) {
                    val step = legSteps.getJSONObject(si)
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.getString("type")
                    val modifier = if (maneuver.has("modifier")) maneuver.getString("modifier") else ""
                    val dist = step.getDouble("distance").toInt()
                    val name = if (step.has("name") && step.getString("name").isNotEmpty())
                        "onto ${step.getString("name")}" else ""
                    val instruction = buildInstruction(type, modifier, name)
                    val icon = getStepIcon(type, modifier)
                    steps.add(RouteStep(instruction, dist, icon))
                }
            }

            // Split into segments for traffic coloring
            val segments = buildTrafficSegments(allPoints, tomtomKey)

            RouteResult(segments, distanceM, durationSec, via, steps)
        } catch (e: Exception) { null }
    }

    private suspend fun buildTrafficSegments(
        points: List<GeoPoint>,
        tomtomKey: String
    ): List<TrafficSegment> {
        if (points.isEmpty()) return emptyList()
        if (tomtomKey.isBlank() || points.size < 4) {
            return listOf(TrafficSegment(points, TrafficLevel.NORMAL))
        }

        val numSegments = minOf(8, points.size - 1)
        val chunkSize = points.size / numSegments
        val result = mutableListOf<TrafficSegment>()

        for (i in 0 until numSegments) {
            val start = i * chunkSize
            val end = if (i == numSegments - 1) points.size else (i + 1) * chunkSize + 1
            val segPoints = points.subList(start, minOf(end, points.size))
            if (segPoints.isEmpty()) continue
            val midPoint = segPoints[segPoints.size / 2]
            val level = queryTrafficLevel(midPoint, tomtomKey)
            result.add(TrafficSegment(segPoints, level))
        }
        return result
    }

    private suspend fun queryTrafficLevel(point: GeoPoint, apiKey: String): TrafficLevel = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.tomtom.com/traffic/services/4/flowSegmentData/relative0/10/json" +
                "?point=${point.latitude},${point.longitude}&key=$apiKey"
            val req = Request.Builder().url(url).header("User-Agent", "NexusMapApp/1.0").build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext TrafficLevel.NORMAL
            val json = JSONObject(body)
            val data = json.getJSONObject("flowSegmentData")
            val current = data.getDouble("currentSpeed")
            val freeFlow = data.getDouble("freeFlowSpeed")
            val ratio = if (freeFlow > 0) current / freeFlow else 1.0
            when {
                ratio >= 0.75 -> TrafficLevel.NORMAL
                ratio >= 0.45 -> TrafficLevel.MEDIUM
                else -> TrafficLevel.HEAVY
            }
        } catch (e: Exception) { TrafficLevel.NORMAL }
    }

    private fun buildInstruction(type: String, modifier: String, onto: String): String {
        val base = when (type) {
            "depart" -> "Head ${modifier.ifEmpty { "forward" }}"
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn ${modifier.ifEmpty { "straight" }}"
            "new name" -> "Continue"
            "merge" -> "Merge ${modifier.ifEmpty { "" }}"
            "on ramp" -> "Take the ramp"
            "off ramp" -> "Take the exit"
            "fork" -> "Keep ${modifier.ifEmpty { "straight" }} at the fork"
            "end of road" -> "Turn ${modifier.ifEmpty { "" }} at end of road"
            "roundabout" -> "Enter roundabout"
            "rotary" -> "Enter rotary"
            "roundabout turn" -> "At roundabout, turn ${modifier.ifEmpty { "" }}"
            "notification" -> "Continue"
            else -> "Continue"
        }
        return if (onto.isNotEmpty()) "$base $onto" else base
    }

    private fun getStepIcon(type: String, modifier: String): String = when (type) {
        "depart" -> "↑"
        "arrive" -> "⬤"
        "turn" -> when (modifier) {
            "left", "sharp left" -> "←"
            "slight left" -> "↙"
            "right", "sharp right" -> "→"
            "slight right" -> "↘"
            else -> "↑"
        }
        "merge" -> "⤴"
        "fork" -> when {
            modifier.contains("left") -> "↙"
            modifier.contains("right") -> "↘"
            else -> "↑"
        }
        "roundabout", "rotary" -> "⟳"
        "off ramp" -> "⤵"
        else -> "↑"
    }
}
