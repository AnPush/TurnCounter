package com.example.turncounter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.turncounter.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var csvLogger: CsvLogger

    private var sensorManager: SensorManager? = null
    private var magneticSensor: Sensor? = null

    private var listening = false
    private var userWantsListening = true

    private var rawCount = 0
    private var lastDisplayedCount = 0
    private var eventsPerTurn = 1

    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 0f
    private var baselineReady = false

    private var signalMean = 0f
    private var noise = 0f
    private var smoothedSignal = 0f

    private var armed = true
    private var aboveThresholdCount = 0

    private var lastEventTime = 0L
    private var lastUiUpdate = 0L

    private var userThreshold = 3f
    private var minIntervalMs = 120L

    private var autoThreshold = false
    private var soundEnabled = true
    private var vibrationEnabled = false

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    private val seekListener by lazy {
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                when (seekBar) {
                    binding.thresholdSeekBar -> updateThreshold()
                    binding.intervalSeekBar -> updateInterval()
                    binding.dividerSeekBar -> updateDivider()
                }

                if (fromUser) {
                    saveSettings()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        csvLogger = CsvLogger(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magneticSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        initVibrator()

        if (magneticSensor == null) {
            binding.statusText.text = "Датчик магнитного поля не найден"
            binding.startStopButton.isEnabled = false
        }

        loadSettings()
        setupUiListeners()

        updateCount()
        updateStartStopButton()
    }

    private fun loadSettings() {
        binding.thresholdSeekBar.progress = prefs.getInt("threshold_progress_v3", 6)
        binding.intervalSeekBar.progress = prefs.getInt("interval_progress_v3", 120)
        binding.dividerSeekBar.progress = prefs.getInt("divider_progress_v3", 0)

        binding.autoThresholdCheckBox.isChecked = prefs.getBoolean("auto_threshold_v3", false)
        binding.soundCheckBox.isChecked = prefs.getBoolean("sound_v3", true)
        binding.vibrationCheckBox.isChecked = prefs.getBoolean("vibration_v3", false)

        rawCount = prefs.getInt("raw_count_v3", 0)
        userWantsListening = prefs.getBoolean("listening_v3", true)

        updateThreshold()
        updateInterval()
        updateDivider()

        autoThreshold = binding.autoThresholdCheckBox.isChecked
        soundEnabled = binding.soundCheckBox.isChecked
        vibrationEnabled = binding.vibrationCheckBox.isChecked

        lastDisplayedCount = displayedCount()
    }

    private fun setupUiListeners() {
        binding.thresholdSeekBar.setOnSeekBarChangeListener(seekListener)
        binding.intervalSeekBar.setOnSeekBarChangeListener(seekListener)
        binding.dividerSeekBar.setOnSeekBarChangeListener(seekListener)

        binding.autoThresholdCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoThreshold = isChecked
            saveSettings()
        }

        binding.soundCheckBox.setOnCheckedChangeListener { _, isChecked ->
            soundEnabled = isChecked
            saveSettings()
        }

        binding.vibrationCheckBox.setOnCheckedChangeListener { _, isChecked ->
            vibrationEnabled = isChecked
            saveSettings()
        }

        binding.resetButton.setOnClickListener {
            resetCounter()
        }

        binding.startStopButton.setOnClickListener {
            toggleListening()
        }

        binding.exportButton.setOnClickListener {
            shareCsv()
        }
    }

    private fun updateThreshold() {
        userThreshold = binding.thresholdSeekBar.progress * 0.5f

        binding.thresholdValueText.text = String.format(
            Locale.US,
            "Порог: %.1f мкТл",
            userThreshold
        )
    }

    private fun updateInterval() {
        minIntervalMs = binding.intervalSeekBar.progress.toLong()

        binding.intervalValueText.text = String.format(
            Locale.US,
            "Интервал: %d мс",
            minIntervalMs
        )
    }

    private fun updateDivider() {
        eventsPerTurn = binding.dividerSeekBar.progress + 1

        binding.dividerValueText.text = String.format(
            Locale.US,
            "Событий на виток: %d",
            eventsPerTurn
        )

        lastDisplayedCount = displayedCount()
        updateCount()
    }

    private fun displayedCount(): Int {
        return if (eventsPerTurn <= 0) {
            0
        } else {
            rawCount / eventsPerTurn
        }
    }

    private fun updateCount() {
        binding.countText.text = String.format(Locale.US, "%d", displayedCount())
        binding.rawCountText.text = String.format(Locale.US, "Сырые события: %d", rawCount)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("threshold_progress_v3", binding.thresholdSeekBar.progress)
            .putInt("interval_progress_v3", binding.intervalSeekBar.progress)
            .putInt("divider_progress_v3", binding.dividerSeekBar.progress)
            .putBoolean("auto_threshold_v3", autoThreshold)
            .putBoolean("sound_v3", soundEnabled)
            .putBoolean("vibration_v3", vibrationEnabled)
            .putBoolean("listening_v3", userWantsListening)
            .putInt("raw_count_v3", rawCount)
            .apply()
    }

    private fun saveCount() {
        prefs.edit()
            .putInt("raw_count_v3", rawCount)
            .apply()
    }

    private fun resetCounter() {
        rawCount = 0
        lastDisplayedCount = 0

        baselineReady = false
        baselineX = 0f
        baselineY = 0f
        baselineZ = 0f

        signalMean = 0f
        noise = 0f
        smoothedSignal = 0f

        armed = true
        aboveThresholdCount = 0
        lastEventTime = 0L

        csvLogger.clearNow()
        binding.graphView.clear()

        updateCount()
        saveCount()

        binding.statusText.text = "Счётчик сброшен"
    }

    private fun toggleListening() {
        if (magneticSensor == null) {
            Toast.makeText(this, "Магнитометр недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        userWantsListening = !userWantsListening

        if (userWantsListening) {
            startListening()
        } else {
            stopListening()
        }

        saveSettings()
    }

    private fun startListening() {
        if (listening || magneticSensor == null) return

        try {
            sensorManager?.registerListener(
                this,
                magneticSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            listening = true
        } catch (_: Exception) {
            listening = false
        }

        updateStartStopButton()
    }

    private fun stopListening() {
        if (!listening) return

        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }

        listening = false
        updateStartStopButton()
    }

    private fun updateStartStopButton() {
        binding.startStopButton.text = if (listening) {
            "Остановить"
        } else {
            "Старт"
        }
    }

    private fun initVibrator() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            vibrator = null
        }
    }

    private fun beep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            }

            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vib = vibrator ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(
                    VibrationEffect.createOneShot(
                        35,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vib.vibrate(35)
            }
        } catch (_: Exception) {
        }
    }

    private fun shareCsv() {
        try {
            val intent = csvLogger.shareIntent(this)
            startActivity(Intent.createChooser(intent, "Экспорт CSV"))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Ошибка экспорта: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun currentThreshold(): Float {
        return if (autoThreshold) {
            max(userThreshold, 1.8f * noise + 0.15f)
        } else {
            max(userThreshold, 0.15f)
        }
    }

    private fun runUi(action: () -> Unit) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread(action)
        }
    }

    override fun onResume() {
        super.onResume()

        if (userWantsListening) {
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()

        stopListening()
        saveSettings()
        saveCount()
    }

    override fun onDestroy() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        if (isFinishing || isDestroyed) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val field = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()

        if (!baselineReady) {
            baselineX = x
            baselineY = y
            baselineZ = z
            baselineReady = true

            signalMean = 0f
            noise = 0f
            smoothedSignal = 0f
        } else {
            // Очень медленная адаптация базового вектора.
            // Если ложных срабатываний много, можно увеличить коэффициент,
            // например до 0.002f или 0.005f.
            val baselineAlpha = 0.0008f

            baselineX = (1f - baselineAlpha) * baselineX + baselineAlpha * x
            baselineY = (1f - baselineAlpha) * baselineY + baselineAlpha * y
            baselineZ = (1f - baselineAlpha) * baselineZ + baselineAlpha * z
        }

        val dx = x - baselineX
        val dy = y - baselineY
        val dz = z - baselineZ

        // Сумма абсолютных изменений по осям лучше реагирует на изменение
        // направления вектора магнитного поля, чем просто модуль поля.
        val vectorChange = abs(dx) + abs(dy) + abs(dz)

        // Убираем медленную постоянную составляющую.
        // Если сигнал слишком медленно затухает, можно увеличить meanAlpha,
        // например до 0.005f.
        val meanAlpha = 0.002f
        signalMean = (1f - meanAlpha) * signalMean + meanAlpha * vectorChange

        // Полезный сигнал: превышение над средним уровнем.
        val detectSignal = max(0f, vectorChange - signalMean)

        smoothedSignal = 0.85f * smoothedSignal + 0.15f * detectSignal
        noise = 0.98f * noise + 0.02f * detectSignal

        val threshold = currentThreshold()

        if (detectSignal > threshold) {
            if (aboveThresholdCount < 10) {
                aboveThresholdCount++
            } else {
                aboveThresholdCount = 10
            }
        } else {
            aboveThresholdCount = 0
        }

        if (armed && aboveThresholdCount >= 1 && now - lastEventTime >= minIntervalMs) {
            rawCount++
            lastEventTime = now
            armed = false
            aboveThresholdCount = 0

            val displayed = displayedCount()

            if (displayed > lastDisplayedCount) {
                lastDisplayedCount = displayed

                csvLogger.log(displayed, field, detectSignal, threshold)
                saveCount()

                if (soundEnabled) {
                    beep()
                }

                if (vibrationEnabled) {
                    vibrate()
                }
            }

            runUi {
                updateCount()
            }
        }

        if (!armed && detectSignal < 0.7f * threshold) {
            armed = true
            aboveThresholdCount = 0
        }

        if (now - lastUiUpdate >= 80L) {
            lastUiUpdate = now

            runUi {
                binding.infoText.text = String.format(
                    Locale.US,
                    "B=%.1f | Δ=%.2f | sig=%.2f | thr=%.2f",
                    field,
                    vectorChange,
                    detectSignal,
                    threshold
                )

                binding.statusText.text = String.format(
                    Locale.US,
                    "%s | armed=%b | above=%d",
                    if (armed) "Ожидание пика" else "Пик обнаружен",
                    armed,
                    aboveThresholdCount
                )

                binding.graphView.addPoint(detectSignal)
                binding.graphView.setThreshold(threshold)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                baselineReady = false
            }
        }
    }
}
