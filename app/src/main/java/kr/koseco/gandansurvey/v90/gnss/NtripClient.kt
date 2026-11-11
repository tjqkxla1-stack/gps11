package kr.koseco.gandansurvey.v90.gnss

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.koseco.gandansurvey.v90.model.NtripConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class NtripClient(
    private val scope: CoroutineScope,
    private val onRtcmReceived: (ByteArray, Int) -> Unit,
    private val onStatusChanged: (Boolean, String) -> Unit
) {
    private var streamJob: Job? = null
    @Volatile private var currentLat: Double = 36.642435
    @Volatile private var currentLon: Double = 127.489031
    @Volatile private var currentHeight: Double = 80.0

    fun updateRoverPosition(lat: Double, lon: Double, height: Double) {
        if (lat != 0.0 && lon != 0.0) {
            currentLat = lat
            currentLon = lon
            currentHeight = height
        }
    }

    suspend fun fetchMountpoints(host: String, port: Int): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host.trim(), port), 6000)
                sock.soTimeout = 6000
                val req = "GET / HTTP/1.0\r\nUser-Agent: NTRIP GandanSurvey/9.5\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                val mounts = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.startsWith("STR;")) {
                        val parts = l.split(";")
                        if (parts.size >= 2 && parts[1].isNotBlank()) {
                            mounts.add(parts[1].trim())
                        }
                    }
                }
                if (mounts.isEmpty()) {
                    mounts.addAll(listOf("VRS-RTCM32", "VRS-RTCM31", "FKP-RTCM32", "SSR-RTCM32"))
                }
                Result.success(mounts.distinct())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun startStreaming(config: NtripConfig, initialLat: Double, initialLon: Double, initialHeight: Double) {
        updateRoverPosition(initialLat, initialLon, initialHeight)
        streamJob?.cancel()
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                onStatusChanged(false, "VRS 서버(${config.host}:${config.port}) 접속 중...")
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(config.host.trim(), config.port), 8000)
                    sock.soTimeout = 12000
                    val authRaw = "${config.username.trim()}:${config.password.trim()}"
                    val authB64 = Base64.encodeToString(authRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val mount = config.mountpoint.trim().removePrefix("/")
                    val gga = buildNmeaGgaSentence(currentLat, currentLon, currentHeight)

                    val req = buildString {
                        append("GET /$mount HTTP/1.0\r\n")
                        append("Host: ${config.host.trim()}:${config.port}\r\n")
                        append("User-Agent: NTRIP GandanSurvey/9.5\r\n")
                        append("Authorization: Basic $authB64\r\n")
                        append("Ntrip-GGA: $gga\r\n")
                        append("\r\n")
                        append("$gga\r\n")
                    }
                    val out = sock.getOutputStream()
                    out.write(req.toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val inp = sock.getInputStream()
                    val buf = ByteArray(4096)
                    var connected = false
                    var lastGgaSentMs = System.currentTimeMillis()
                    var bytesInWindow = 0
                    var windowStartMs = System.currentTimeMillis()

                    while (isActive) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        if (!connected) {
                            val header = String(buf, 0, minOf(n, 256), Charsets.US_ASCII)
                            if (header.contains("401 Unauthorized", true)) {
                                onStatusChanged(false, "VRS 아이디/비밀번호 인증 실패 (401)")
                                return@use
                            }
                            connected = true
                            onStatusChanged(true, "VRS 수신 중 (${config.mountpoint})")
                        }
                        bytesInWindow += n
                        val now = System.currentTimeMillis()
                        val elapsed = (now - windowStartMs).coerceAtLeast(1L)
                        val bps = ((bytesInWindow * 1000L) / elapsed).toInt()
                        if (elapsed >= 1000L) {
                            bytesInWindow = 0
                            windowStartMs = now
                        }
                        onRtcmReceived(buf.copyOf(n), bps)

                        if (now - lastGgaSentMs >= (config.autoSendGgaIntervalSec.coerceAtLeast(3) * 1000L)) {
                            lastGgaSentMs = now
                            val periodicGga = buildNmeaGgaSentence(currentLat, currentLon, currentHeight) + "\r\n"
                            try {
                                out.write(periodicGga.toByteArray(Charsets.US_ASCII))
                                out.flush()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                onStatusChanged(false, "VRS 연결 끊김: ${e.message ?: "오류"}")
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun buildNmeaGgaSentence(lat: Double, lon: Double, height: Double): String {
        val utcFmt = SimpleDateFormat("HHmmss.00", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timeStr = utcFmt.format(Date())
        val absLat = abs(lat)
        val latDeg = absLat.toInt()
        val latMin = (absLat - latDeg) * 60.0
        val latHemi = if (lat >= 0) "N" else "S"

        val absLon = abs(lon)
        val lonDeg = absLon.toInt()
        val lonMin = (absLon - lonDeg) * 60.0
        val lonHemi = if (lon >= 0) "E" else "W"

        val body = String.format(
            Locale.US,
            "GPGGA,%s,%02d%08.5f,%s,%03d%08.5f,%s,4,12,0.8,%.3f,M,25.0,M,1.0,0000",
            timeStr, latDeg, latMin, latHemi, lonDeg, lonMin, lonHemi, height
        )
        var cs = 0
        for (ch in body) {
            cs = cs xor ch.code
        }
        return String.format(Locale.US, "$%s*%02X", body, cs)
    }
}
