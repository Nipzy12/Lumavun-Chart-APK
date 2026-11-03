package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NexSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}

object CrashLogger {
    const val CRASH_LOG_FILE_NAME = "crash_log.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler is CrashUncaughtExceptionHandler) {
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(
            CrashUncaughtExceptionHandler(
                appContext = appContext,
                previousHandler = currentHandler
            )
        )
    }

    fun getCrashFile(context: Context): File {
        return File(context.filesDir, CRASH_LOG_FILE_NAME)
    }

    fun writeCrashTrace(context: Context, thread: Thread, throwable: Throwable) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val stringWriter = StringWriter()
            PrintWriter(stringWriter).use { printWriter ->
                throwable.printStackTrace(printWriter)
            }
            val content = buildString {
                appendLine("Timestamp: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
                appendLine()
                append(stringWriter.toString())
            }
            getCrashFile(context).writeText(content)
        }
    }

    fun readCrashLog(context: Context): String? {
        return runCatching {
            val file = getCrashFile(context)
            if (file.exists() && file.isFile) {
                file.readText().takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    fun clearCrashLog(context: Context) {
        runCatching {
            val file = getCrashFile(context)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Crash Log", text))
            Toast.makeText(context, "Log crash berhasil disalin", Toast.LENGTH_SHORT).show()
        }
    }

    private class CrashUncaughtExceptionHandler(
        private val appContext: Context,
        private val previousHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                writeCrashTrace(appContext, thread, throwable)
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
