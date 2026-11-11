package kr.koseco.gandansurvey.v90.util

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.koseco.gandansurvey.v90.model.BackgroundMapType
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

class TileMapManager(
    private val scope: CoroutineScope,
    private val onTileLoaded: () -> Unit
) {
    data class MapTileRenderItem(
        val key: String,
        val northLat: Double,
        val southLat: Double,
        val westLon: Double,
        val eastLon: Double,
        val bitmap: ImageBitmap?
    )

    data class AddressSearchResult(
        val displayName: String,
        val latitude: Double,
        val longitude: Double
    )

    private val tileCache = ConcurrentHashMap<String, ImageBitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun getVisibleTiles(
        mapType: BackgroundMapType,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double
    ): List<MapTileRenderItem> {
        if (mapType == BackgroundMapType.NONE || centerLat == 0.0 || centerLon == 0.0) return emptyList()
        val zoom = when {
            metersPerPixel <= 0.05 -> 19
            metersPerPixel <= 0.15 -> 18
            metersPerPixel <= 0.40 -> 17
            metersPerPixel <= 1.00 -> 16
            metersPerPixel <= 2.50 -> 15
            else -> 14
        }
        val (cx, cy) = latLonToTileXY(centerLat, centerLon, zoom)
        val list = mutableListOf<MapTileRenderItem>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = cx + dx
                val ty = cy + dy
                val key = "${mapType.name}_${zoom}_${tx}_${ty}"
                val bmp = tileCache[key]
                if (bmp == null && inFlight.add(key)) {
                    fetchTileAsync(mapType, zoom, tx, ty, key)
                }
                val north = tileYToLat(ty, zoom)
                val south = tileYToLat(ty + 1, zoom)
                val west = tileXToLon(tx, zoom)
                val east = tileXToLon(tx + 1, zoom)
                list.add(MapTileRenderItem(key, north, south, west, east, bmp))
            }
        }
        return list
    }

    private fun fetchTileAsync(mapType: BackgroundMapType, z: Int, x: Int, y: Int, key: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val urlStr = when (mapType) {
                    BackgroundMapType.GOOGLE_SATELLITE -> "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$z"
                    BackgroundMapType.CADASTRAL_HYBRID -> "https://mt1.google.com/vt/lyrs=y&x=$x&y=$y&z=$z"
                    BackgroundMapType.OSM_ROAD -> "https://tile.openstreetmap.org/$z/$x/$y.png"
                    BackgroundMapType.NONE -> return@launch
                }
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "GandanSurveyV90/1.0")
                }
                conn.inputStream.use { inp ->
                    val bytes = inp.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        tileCache[key] = bmp.asImageBitmap()
                        onTileLoaded()
                    }
                }
            } catch (_: Exception) {
            } finally {
                inFlight.remove(key)
            }
        }
    }

    fun searchKoreanAddressBlocking(query: String): List<AddressSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$enc&format=json&limit=8&countrycodes=kr")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "GandanSurveyV90/1.0 (kr.koseco.gandansurvey)")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(resp)
            val results = mutableListOf<AddressSearchResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("display_name", "")
                val lat = obj.optString("lat", "0.0").toDoubleOrNull() ?: 0.0
                val lon = obj.optString("lon", "0.0").toDoubleOrNull() ?: 0.0
                if (name.isNotBlank() && lat != 0.0 && lon != 0.0) {
                    results.add(AddressSearchResult(name, lat, lon))
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun latLonToTileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 2.0.pow(zoom.toDouble())
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    private fun tileXToLon(x: Int, zoom: Int): Double {
        return x.toDouble() / 2.0.pow(zoom.toDouble()) * 360.0 - 180.0
    }

    private fun tileYToLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y.toDouble() / 2.0.pow(zoom.toDouble())
        return Math.toDegrees(atan(sinh(n)))
    }
}
