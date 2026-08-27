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

    private var baseline = 0f
    private var baselineReady = false

    private var noise = 0f
    private var smoothedSignal = 0f

    private var armed = true
    private var aboveThresholdCount = 0

    private var lastEventTime = 0L
    private var lastUiUpdate = 0L

    private var userThreshold = 8f
    private var minIntervalMs = 250L

    private var autoThreshold = true
    private var soundEnabled = true
    private var vibrationEnabled = false

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    private val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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
        binding.thresholdSeekBar.progress = prefs.getInt("threshold_progress", 16)
        binding.intervalSeekBar.progress = prefs.getInt("interval_progress", 250)
        binding.dividerSeekBar.progress = prefs.getInt("divider_progress", 0)

        binding.autoThresholdCheckBox.isChecked = prefs.getBoolean("auto_threshold", true)
        binding.soundCheckBox.isChecked = prefs.getBoolean("sound", true)
        binding.vibrationCheckBox.isChecked = prefs.getBoolean("vibration", false)

        rawCount = prefs.getInt("raw_count", 0)
        userWantsListening = prefs.getBoolean("listening", true)

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
        binding.thresholdValueText.text =
            String.format(Locale.US, "Порог: %.1f мкТл", userThreshold)
    }

    private fun updateInterval() {
        minIntervalMs = binding.intervalSeekBar.progress.toLong()
        binding.intervalValueText.text =
            String.format(Locale.US, "Интервал: %d мс", minIntervalMs)
    }

    private fun updateDivider() {
        eventsPerTurn = binding.dividerSeekBar.progress + 1
        binding.dividerValueText.text =
            String.format(Locale.US, "Событий на виток: %d", eventsPerTurn)

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
        binding.rawCountText.text =
            String.format(Locale.US, "Сырые события: %d", rawCount)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("threshold_progress", binding.thresholdSeekBar.progress)
            .putInt("interval_progress", binding.intervalSeekBar.progress)
            .putInt("divider_progress", binding.dividerSeekBar.progress)
            .putBoolean("auto_threshold", autoThreshold)
            .putBoolean("sound", soundEnabled)
            .putBoolean("vibration", vibrationEnabled)
            .putBoolean("listening", userWantsListening)
            .putInt("raw_count", rawCount)
            .apply()
    }

    private fun saveCount() {
        prefs.edit()
            .putInt("raw_count", rawCount)
            .apply()
    }

    private fun resetCounter() {
        rawCount = 0
        lastDisplayedCount = 0

        baselineReady = false
        baseline = 0f
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
            max(userThreshold, 2.5f * noise + 0.5f)
        } else {
            max(userThreshold, 0.1f)
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
            baseline = field
            baselineReady = true
            noise = 0f
            smoothedSignal = 0f
        } else {
            baseline = 0.98f * baseline + 0.02f * field
        }

        val rawSignal = abs(field - baseline)

        smoothedSignal = 0.7f * smoothedSignal + 0.3f * rawSignal
        noise = 0.95f * noise + 0.05f * rawSignal

        val threshold = currentThreshold()
        val detectSignal = smoothedSignal

        if (detectSignal > threshold) {
            aboveThresholdCount++
        } else {
            aboveThresholdCount = 0
        }

        if (armed && aboveThresholdCount >= 2 && now - lastEventTime >= minIntervalMs) {
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

        if (!armed && detectSignal < 0.55f * threshold) {
            armed = true
            aboveThresholdCount = 0
        }

        if (now - lastUiUpdate >= 100L) {
            lastUiUpdate = now

            runUi {
                binding.infoText.text = String.format(
                    Locale.US,
                    "B=%.1f мкТл | сигнал=%.1f | порог=%.1f",
                    field,
                    detectSignal,
                    threshold
                )

                binding.statusText.text = if (armed) {
                    "Ожидание пика"
                } else {
                    "Пик обнаружен"
                }

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
