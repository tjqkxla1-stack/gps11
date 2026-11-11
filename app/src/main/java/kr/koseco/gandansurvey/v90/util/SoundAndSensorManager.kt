package kr.koseco.gandansurvey.v90.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

class SoundAndSensorManager(private val context: Context) : SensorEventListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 95)
    } catch (_: Exception) {
        null
    }

    private val vibrator: Vibrator? = try {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (_: Exception) {
        null
    }

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _deviceAzimuthDeg = MutableStateFlow(0f)
    val deviceAzimuthDeg: StateFlow<Float> = _deviceAzimuthDeg.asStateFlow()

    private val _deviceTiltAngleDeg = MutableStateFlow(0f)
    val deviceTiltAngleDeg: StateFlow<Float> = _deviceTiltAngleDeg.asStateFlow()

    private val _deviceTiltPitchDeg = MutableStateFlow(0f)
    val deviceTiltPitchDeg: StateFlow<Float> = _deviceTiltPitchDeg.asStateFlow()

    private val _deviceTiltRollDeg = MutableStateFlow(0f)
    val deviceTiltRollDeg: StateFlow<Float> = _deviceTiltRollDeg.asStateFlow()

    private val _deviceTiltAzimuthDeg = MutableStateFlow(0f)
    val deviceTiltAzimuthDeg: StateFlow<Float> = _deviceTiltAzimuthDeg.asStateFlow()

    private var pitchZeroOffset = 0f
    private var rollZeroOffset = 0f
    private var rawPitch = 0f
    private var rawRoll = 0f
    private var lastSensorEmitTimeMs: Long = 0L

    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private val bellChimeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var currentStakeoutDistMeters: Double = 9999.0
    @Volatile private var stakeoutBellEnabled: Boolean = false
    private var stakeoutBellThread: Thread? = null

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.KOREAN
                    ttsReady = true
                }
            }
        } catch (_: Exception) {}

        try {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val nowMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelValues, 0, 3)
                hasAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magValues, 0, 3)
                hasMag = true
            }
        }
        // 배터리·발열 절약을 위해 최대 초당 약 6~7회(145ms 간격)로 스로틀링
        if (nowMs - lastSensorEmitTimeMs < 145L) return
        lastSensorEmitTimeMs = nowMs

        if (hasAccel) {
            val ax = accelValues[0].toDouble()
            val ay = accelValues[1].toDouble()
            val az = accelValues[2].toDouble()
            val norm = hypot(hypot(ax, ay), az).coerceAtLeast(0.001)
            rawPitch = Math.toDegrees(atan2(ay, az)).toFloat()
            rawRoll = Math.toDegrees(atan2(-ax, hypot(ay, az))).toFloat()

            val calPitch = rawPitch - pitchZeroOffset
            val calRoll = rawRoll - rollZeroOffset
            val totalTilt = hypot(calPitch.toDouble(), calRoll.toDouble()).toFloat()

            if (abs(totalTilt - _deviceTiltAngleDeg.value) >= 0.25f) {
                _deviceTiltPitchDeg.value = calPitch
                _deviceTiltRollDeg.value = calRoll
                _deviceTiltAngleDeg.value = totalTilt
            }

            if (hasMag) {
                val rMat = FloatArray(9)
                val iMat = FloatArray(9)
                if (SensorManager.getRotationMatrix(rMat, iMat, accelValues, magValues)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(rMat, orient)
                    val azDeg = ((Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f)
                    if (abs(azDeg - _deviceAzimuthDeg.value) >= 1.5f) {
                        _deviceAzimuthDeg.value = azDeg
                    }
                    val tiltDir = ((azDeg + Math.toDegrees(atan2(calRoll.toDouble(), calPitch.toDouble())).toFloat() + 360f) % 360f)
                    _deviceTiltAzimuthDeg.value = tiltDir
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun calibrateBubbleZero() {
        pitchZeroOffset = rawPitch
        rollZeroOffset = rawRoll
        _deviceTiltPitchDeg.value = 0f
        _deviceTiltRollDeg.value = 0f
        _deviceTiltAngleDeg.value = 0f
    }

    fun resetBubbleZeroCalibration() {
        pitchZeroOffset = 0f
        rollZeroOffset = 0f
    }

    fun playPointSavedBeep() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 130)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45L)
            }
        } catch (_: Exception) {}
    }

    fun speak(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gandan_utt_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    private fun playSynthesizedBellChime(isWithinOneMeter: Boolean) {
        bellChimeExecutor.execute {
            try {
                val sampleRate = 24000
                val durationMs = if (isWithinOneMeter) 210 else 155
                val numSamples = (sampleRate * durationMs) / 1000
                val pcm = ShortArray(numSamples)

                if (!isWithinOneMeter) {
                    // 15m ~ 1m 구간: 간헐적인 맑은 단일 종소리 ("딩~" 880Hz + 1760Hz 배음)
                    val f1 = 880.0
                    val f2 = 1760.0
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val env = exp(-t / 0.055)
                        val wave = (0.72 * sin(2.0 * Math.PI * f1 * t) + 0.28 * sin(2.0 * Math.PI * f2 * t)) * env
                        pcm[i] = (wave * 26000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                } else {
                    // 1m 이내 구간: 확연히 구분되는 다른 고음 2연타 종소리 ("딩-동!" 1318.5Hz E6 -> 1760Hz A6)
                    val splitSample = (sampleRate * 85) / 1000
                    for (i in 0 until numSamples) {
                        val isSecondNote = i >= splitSample
                        val localIdx = if (isSecondNote) (i - splitSample) else i
                        val t = localIdx.toDouble() / sampleRate
                        val f1 = if (isSecondNote) 1760.0 else 1318.5
                        val f2 = f1 * 2.0
                        val env = exp(-t / 0.048)
                        val wave = (0.75 * sin(2.0 * Math.PI * f1 * t) + 0.25 * sin(2.0 * Math.PI * f2 * t)) * env
                        pcm[i] = (wave * 29000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((durationMs + 35).toLong())
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    fun updateStakeoutDistance(distanceMeters: Double, soundEnabled: Boolean) {
        currentStakeoutDistMeters = distanceMeters
        stakeoutBellEnabled = soundEnabled
        if (!soundEnabled || distanceMeters > 15.0 || distanceMeters.isNaN()) {
            stakeoutBellEnabled = false
            return
        }
        if (stakeoutBellThread?.isAlive == true) return
        stakeoutBellThread = Thread {
            var lastRingMs = 0L
            while (stakeoutBellEnabled) {
                val d = currentStakeoutDistMeters
                if (!stakeoutBellEnabled || d > 15.0 || d.isNaN()) {
                    try { Thread.sleep(150L) } catch (_: Exception) { break }
                    continue
                }
                val isInner1m = (d <= 1.0)
                val intervalMs = if (isInner1m) {
                    (240L + ((d / 1.0).coerceIn(0.0, 1.0) * 200.0).toLong())
                } else {
                    (450L + (((d - 1.0) / 14.0).coerceIn(0.0, 1.0) * 1350.0).toLong())
                }
                val now = System.currentTimeMillis()
                if (now - lastRingMs >= intervalMs) {
                    lastRingMs = now
                    playSynthesizedBellChime(isWithinOneMeter = isInner1m)
                }
                try { Thread.sleep(40L) } catch (_: Exception) { break }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopStakeoutSound() {
        stakeoutBellEnabled = false
        currentStakeoutDistMeters = 9999.0
    }

    fun release() {
        stopStakeoutSound()
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            toneGen?.release()
        } catch (_: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
