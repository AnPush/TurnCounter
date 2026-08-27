package com.example.turncounter

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CsvLogger(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "turn_log.csv")
    private val executor = Executors.newSingleThreadExecutor()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val header = "time,turn,field_ut,signal_ut,threshold_ut\n"

    init {
        ensureHeaderNow()
    }

    fun ensureHeaderNow() {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(header)
            }
        } catch (_: Exception) {
        }
    }

    fun clearNow() {
        try {
            file.writeText(header)
        } catch (_: Exception) {
        }
    }

    fun log(turn: Int, field: Float, signal: Float, threshold: Float) {
        executor.execute {
            try {
                rotateIfNeeded()

                if (!file.exists()) {
                    file.writeText(header)
                }

                val line = String.format(
                    Locale.US,
                    "%s,%d,%.2f,%.2f,%.2f\n",
                    dateFormat.format(Date()),
                    turn,
                    field,
                    signal,
                    threshold
                )

                file.appendText(line)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded() {
        try {
            if (file.length() > MAX_FILE_BYTES) {
                val old = File(appContext.filesDir, "turn_log_old.csv")
                old.delete()
                file.renameTo(old)
                file.writeText(header)
            }
        } catch (_: Exception) {
        }
    }

    fun shareIntent(context: Context): Intent {
        ensureHeaderNow()

        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 5_000_000L
    }
}
