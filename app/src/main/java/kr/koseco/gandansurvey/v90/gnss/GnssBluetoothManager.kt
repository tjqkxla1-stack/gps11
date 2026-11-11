package kr.koseco.gandansurvey.v90.gnss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.koseco.gandansurvey.v90.model.BtDeviceItem
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

@SuppressLint("MissingPermission")
class GnssBluetoothManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNmeaLineReceived: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean, String) -> Unit
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var readJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<BtDeviceItem>>(emptyList())
    val discoveredDevices: StateFlow<List<BtDeviceItem>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var receiverRegistered = false
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null && !dev.address.isNullOrBlank()) {
                        val dName = dev.name ?: "GNSS (${dev.address.takeLast(5)})"
                        val isV90 = dName.contains("V90", true) || dName.contains("V200", true) ||
                            dName.contains("HI-TARGET", true) || dName.contains("GNSS", true) ||
                            dName.contains("RTK", true) || dName.contains("SINO", true)
                        val item = BtDeviceItem(
                            name = dName,
                            address = dev.address,
                            isHiTargetV90 = isV90,
                            isBonded = dev.bondState == BluetoothDevice.BOND_BONDED
                        )
                        val current = _discoveredDevices.value.toMutableList()
                        if (current.none { it.address == item.address }) {
                            current.add(item)
                            _discoveredDevices.value = current.sortedByDescending { it.isHiTargetV90 }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    fun getPairedDevices(): List<BtDeviceItem> {
        val bonded = try {
            btAdapter?.bondedDevices.orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
        return bonded.map { dev ->
            val dName = dev.name ?: "블루투스 기기 (${dev.address})"
            val isV90 = dName.contains("V90", true) || dName.contains("V200", true) ||
                dName.contains("HI-TARGET", true) || dName.contains("GNSS", true) ||
                dName.contains("RTK", true)
            BtDeviceItem(
                name = dName,
                address = dev.address,
                isHiTargetV90 = isV90,
                isBonded = true
            )
        }.sortedByDescending { it.isHiTargetV90 }
    }

    fun startDiscoveryScan() {
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(discoveryReceiver, filter)
                receiverRegistered = true
            }
            _discoveredDevices.value = getPairedDevices()
            btAdapter?.cancelDiscovery()
            _isScanning.value = true
            btAdapter?.startDiscovery()
        } catch (_: Exception) {
            _isScanning.value = false
        }
    }

    fun connect(address: String, deviceName: String) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                btAdapter?.cancelDiscovery()
                socket?.close()
                val device = btAdapter?.getRemoteDevice(address)
                    ?: throw IllegalStateException("블루투스 기기를 찾을 수 없습니다.")

                val sock = try {
                    device.createRfcommSocketToServiceRecord(sppUuid)
                } catch (_: Exception) {
                    device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                }

                try {
                    sock.connect()
                } catch (_: Exception) {
                    val fallback = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                    fallback.connect()
                    socket = fallback
                }
                if (socket == null || socket?.isConnected != true) {
                    socket = sock
                }

                val activeSock = socket ?: throw IllegalStateException("소켓 연결 실패")
                outStream = activeSock.outputStream
                onConnectionStateChanged(true, deviceName)

                // Hi-Target V90 / OEM7 / Unicore / Trimble NMEA 출력 자동 활성화 명령 전송
                sendV90NmeaWakeupCommands()

                val inp = activeSock.inputStream
                val readBuf = ByteArray(2048)
                val rollingStream = ByteArrayOutputStream(8192)
                var lastNmeaTimeMs = System.currentTimeMillis()
                var wakeupRetried = false

                while (isActive && activeSock.isConnected) {
                    val n = inp.read(readBuf)
                    if (n <= 0) break

                    rollingStream.write(readBuf, 0, n)
                    var data = rollingStream.toByteArray()

                    // 1. 혼재된 원시 스트림(바이너리 + 텍스트)에서 모든 $GP/$GN/$GL/$GB/$BD...*XX NMEA 문장 추출
                    val extractedCount = extractEmbeddedNmeaAndBinaryPackets(data)
                    if (extractedCount > 0) {
                        lastNmeaTimeMs = System.currentTimeMillis()
                    } else if (!wakeupRetried && System.currentTimeMillis() - lastNmeaTimeMs > 3000L) {
                        wakeupRetried = true
                        sendV90NmeaWakeupCommands()
                    }

                    // 버퍼 크기 유지 (최근 512바이트만 남겨 경계 패킷 보존)
                    if (data.size > 1024) {
                        val tail = data.copyOfRange(data.size - 384, data.size)
                        rollingStream.reset()
                        rollingStream.write(tail)
                    } else if (extractedCount > 0) {
                        rollingStream.reset()
                    }
                }
            } catch (_: Exception) {
            } finally {
                onConnectionStateChanged(false, "미연결")
            }
        }
    }

    /**
     * Hi-Target V90 등 GNSS 수신기가 원시 바이너리 모드에 있거나 출력이 꺼져 있을 때
     * 표준 NMEA(GGA, RMC, GST, GSV) 스트림을 함께 출력하도록 깨우는 명령어 전송
     */
    private fun sendV90NmeaWakeupCommands() {
        try {
            val cmds = listOf(
                "\r\n",
                "LOG GPGGA ONTIME 0.2\r\n",
                "LOG GNGGA ONTIME 0.2\r\n",
                "LOG GPGST ONTIME 1\r\n",
                "LOG GNGST ONTIME 1\r\n",
                "LOG GPRMC ONTIME 1\r\n",
                "\$CMD,OUTPUT,COM1,GGA,0.2\r\n",
                "\$CMD,OUTPUT,BLUETOOTH,GGA,0.2\r\n",
                "\$JASC,GPGGA,5\r\n"
            )
            for (c in cmds) {
                outStream?.write(c.toByteArray(Charsets.US_ASCII))
            }
            outStream?.flush()
        } catch (_: Exception) {}
    }

    /**
     * 원시 암호(Binary OEM7 / Hi-Target V90 Binary / RTCM 혼재 스트림)에서:
     * 1) 바이트 중간에 낀 '$...*XX' 표준 NMEA 문장을 모두 발라내어 전달하고
     * 2) 0xAA 0x44 0x12 (OEM7/Unicore BESTPOS MsgID=42) 또는 Hi-Target V90 원시 좌표 패킷을 해독하여
     *    표준 $GNGGA 및 $GNGST 문장으로 변환해 전달합니다.
     */
    private fun extractEmbeddedNmeaAndBinaryPackets(data: ByteArray): Int {
        var count = 0
        var i = 0
        val len = data.size

        while (i < len) {
            val b = data[i].toInt() and 0xFF

            // Case A: '$' (0x24) 시작 NMEA 문장 스캔 (앞에 바이너리 쓰레기 바이트가 붙어 있어도 정확히 추출)
            if (b == 0x24 && i + 10 < len) {
                val c1 = data[i + 1].toInt() and 0xFF
                val c2 = data[i + 2].toInt() and 0xFF
                if (c1 in 65..90 && c2 in 65..90) {
                    var starPos = -1
                    var j = i + 3
                    val maxScan = minOf(len, i + 128)
                    while (j < maxScan) {
                        val ch = data[j].toInt() and 0xFF
                        if (ch == 0x24) break // 새로운 '$' 시작
                        if (ch == 0x2A) { // '*'
                            starPos = j
                            break
                        }
                        if (ch < 32 || ch > 126) break
                        j++
                    }
                    if (starPos != -1 && starPos + 2 < len) {
                        val h1 = data[starPos + 1].toInt() and 0xFF
                        val h2 = data[starPos + 2].toInt() and 0xFF
                        if (isHexAscii(h1) && isHexAscii(h2)) {
                            val sentence = String(data, i, (starPos + 3) - i, Charsets.US_ASCII)
                            onNmeaLineReceived(sentence)
                            count++
                            i = starPos + 3
                            continue
                        }
                    }
                }
            }

            // Case B: OEM7 / Unicore / Hi-Target V90 내장 보드 원시 바이너리 BESTPOS (Sync: 0xAA 0x44 0x12)
            if (b == 0xAA && i + 100 <= len) {
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF
                if (b1 == 0x44 && b2 == 0x12) {
                    val hdrLen = data[i + 3].toInt() and 0xFF
                    if (hdrLen in 20..36 && i + hdrLen + 72 <= len) {
                        val bb = ByteBuffer.wrap(data, i, hdrLen + 72).order(ByteOrder.LITTLE_ENDIAN)
                        val msgId = bb.getShort(4).toInt() and 0xFFFF
                        if (msgId == 42) { // BESTPOS = 42
                            val solStatus = bb.getInt(hdrLen)
                            val posType = bb.getInt(hdrLen + 4)
                            val lat = bb.getDouble(hdrLen + 8)
                            val lon = bb.getDouble(hdrLen + 16)
                            val hgt = bb.getDouble(hdrLen + 24)
                            val undulation = bb.getFloat(hdrLen + 32).toDouble()
                            val latStd = bb.getFloat(hdrLen + 40).toDouble()
                            val lonStd = bb.getFloat(hdrLen + 44).toDouble()
                            val hgtStd = bb.getFloat(hdrLen + 48).toDouble()
                            val satsTracked = data[i + hdrLen + 64].toInt() and 0xFF
                            val satsUsed = data[i + hdrLen + 65].toInt() and 0xFF

                            if (solStatus == 0 && lat in 30.0..45.0 && lon in 120.0..135.0) {
                                val qual = when (posType) {
                                    50, 48, 34 -> 4 // RTK_FIXED (NARROW_INT / WIDE_INT)
                                    32, 33, 49 -> 5 // RTK_FLOAT (NARROW_FLOAT / L1_FLOAT)
                                    17, 18 -> 2     // DGPS / PSRDIFF
                                    16 -> 1         // SINGLE
                                    else -> if (latStd < 0.03) 4 else if (latStd < 0.5) 5 else 1
                                }
                                emitSyntheticNmeaFromBinary(lat, lon, hgt, undulation, qual, satsUsed.coerceAtLeast(satDefault(satsTracked)), latStd, lonStd, hgtStd)
                                count++
                                i += hdrLen + 72
                                continue
                            }
                        }
                    }
                }
            }

            i++
        }
        return count
    }

    private fun satDefault(tracked: Int): Int = if (tracked > 0) tracked else 14

    private fun isHexAscii(c: Int): Boolean {
        return (c in 48..57) || (c in 65..70) || (c in 97..102)
    }

    private fun emitSyntheticNmeaFromBinary(
        lat: Double,
        lon: Double,
        orthoH: Double,
        geoidSep: Double,
        qual: Int,
        satsUsed: Int,
        latStd: Double,
        lonStd: Double,
        hgtStd: Double
    ) {
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

        val ggaBody = String.format(
            Locale.US,
            "GNGGA,%s,%02d%08.5f,%s,%03d%08.5f,%s,%d,%02d,0.8,%.4f,M,%.4f,M,1.0,0000",
            timeStr, latDeg, latMin, latHemi, lonDeg, lonMin, lonHemi, qual, satsUsed.coerceIn(4, 45), orthoH, geoidSep
        )
        onNmeaLineReceived(wrapNmeaChecksum(ggaBody))

        val gstBody = String.format(
            Locale.US,
            "GNGST,%s,1.0,0.01,0.01,0.0,%.4f,%.4f,%.4f",
            timeStr, latStd.coerceIn(0.002, 9.9), lonStd.coerceIn(0.002, 9.9), hgtStd.coerceIn(0.002, 9.9)
        )
        onNmeaLineReceived(wrapNmeaChecksum(gstBody))
    }

    private fun wrapNmeaChecksum(body: String): String {
        var cs = 0
        for (ch in body) {
            cs = cs xor ch.code
        }
        return String.format(Locale.US, "\$%s*%02X", body, cs)
    }

    fun writeRtcmBytes(bytes: ByteArray) {
        try {
            outStream?.write(bytes)
            outStream?.flush()
        } catch (_: Exception) {}
    }

    fun disconnect() {
        readJob?.cancel()
        try {
            outStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        outStream = null
        socket = null
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }
}
