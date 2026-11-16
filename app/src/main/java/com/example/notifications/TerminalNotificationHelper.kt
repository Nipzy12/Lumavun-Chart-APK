package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requirement #7: Local Android Notification Manager with deduplication and granular category filtering.
 */
object TerminalNotificationHelper {

    const val CHANNEL_ALERTS_ID = "nexsync_signal_alerts"
    const val CHANNEL_MONITOR_ID = "nexsync_monitor_service"
    private const val DEDUP_WINDOW_MS = 10 * 60_000L // 10 minutes deduplication per unique event key

    private val sentEventTimestamps = ConcurrentHashMap<String, Long>()
    private val notificationIdCounter = AtomicInteger(2000)

    fun ensureChannels(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

                val alertsChannel = NotificationChannel(
                    CHANNEL_ALERTS_ID,
                    "AI Signals & Bot Execution Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts for confirmed AI signals, virtual bot entries, TP/SL hits, and risk limits"
                }

                val monitorChannel = NotificationChannel(
                    CHANNEL_MONITOR_ID,
                    "Continuous Market & Bot Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background market & simulation monitoring status"
                    setShowBadge(false)
                }

                manager.createNotificationChannel(alertsChannel)
                manager.createNotificationChannel(monitorChannel)
            }
        } catch (_: Throwable) {
            // Safe no-op in restricted environments
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun notifyConfirmedSignal(
        context: Context,
        symbol: String,
        timeframe: String,
        decision: String,
        setupGrade: String,
        confidence: Int,
        entryRange: String,
        stopLoss: String,
        tp1: String,
        setupSignature: String,
        pendingOrderLabel: String? = null,
        isTechnicalFallback: Boolean = false
    ) {
        val dedupKey = "SIG_${symbol}_${timeframe}_${setupSignature.ifBlank { "${decision}_$entryRange" }}"
        if (!shouldSend(dedupKey)) return

        val label = pendingOrderLabel?.takeIf { it.isNotBlank() } ?: decision
        val title = if (pendingOrderLabel != null) {
            "Pending setup: $symbol $timeframe · $label (Grade $setupGrade)"
        } else {
            "AI Signal: $symbol $timeframe · $decision (Grade $setupGrade)"
        }
        val scoreLabel = if (isTechnicalFallback) "Technical score" else "Conf"
        val footer = if (pendingOrderLabel != null) " · NOT FILLED / ANALYTICAL ONLY" else ""
        val body = "Entry $entryRange | SL $stopLoss | TP1 $tp1 · $scoreLabel $confidence%$footer"
        postAlertNotification(context, title, body)
    }

    fun notifyBotEntry(
        context: Context,
        symbol: String,
        timeframe: String,
        side: String,
        setupGrade: String,
        entryPrice: Double,
        notionalUsd: Double,
        positionId: String
    ) {
        val dedupKey = "BOT_ENTRY_$positionId"
        if (!shouldSend(dedupKey)) return

        val title = "Bot Simulasi Entry: $side $symbol ($timeframe)"
        val body = String.format(
            Locale.US,
            "Opened %s (Grade %s) @ %.4f · Size $%.2f",
            side,
            setupGrade,
            entryPrice,
            notionalUsd
        )
        postAlertNotification(context, title, body)
    }

    fun notifyTpOrSlHit(
        context: Context,
        symbol: String,
        timeframe: String,
        side: String,
        eventLabel: String, // e.g., "TP1 HIT", "TP2 HIT", "TP3 HIT", "SL HIT"
        price: Double,
        pnlText: String,
        uniqueEventId: String
    ) {
        val dedupKey = "TPSL_${uniqueEventId}_$eventLabel"
        if (!shouldSend(dedupKey)) return

        val title = "$symbol ($timeframe) · $eventLabel ($side)"
        val body = String.format(Locale.US, "Triggered @ %.4f · %s", price, pnlText)
        postAlertNotification(context, title, body)
    }

    fun notifySignalInvalidatedOrExpired(
        context: Context,
        symbol: String,
        timeframe: String,
        decision: String,
        status: String, // "INVALIDATED" or "EXPIRED"
        signalId: Long
    ) {
        val dedupKey = "INV_EXP_${signalId}_$status"
        if (!shouldSend(dedupKey)) return

        val title = "Signal $status: $symbol $timeframe ($decision)"
        val body = if (status == "INVALIDATED") {
            "Setup invalidated before entry trigger."
        } else {
            "Signal setup expired without triggering entry."
        }
        postAlertNotification(context, title, body)
    }

    fun notifyRiskOrEventWarning(
        context: Context,
        title: String,
        message: String,
        dedupKey: String
    ) {
        if (!shouldSend("RISK_$dedupKey")) return
        postAlertNotification(context, title, message)
    }

    private fun shouldSend(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prev = sentEventTimestamps[key]
        if (prev != null && (nowMs - prev) < DEDUP_WINDOW_MS) {
            return false
        }
        sentEventTimestamps[key] = nowMs
        return true
    }

    private fun postAlertNotification(
        context: Context,
        title: String,
        body: String
    ) {
        try {
            if (!hasNotificationPermission(context)) return
            ensureChannels(context)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
                .setSmallIcon(R.drawable.ic_stat_nexsync)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context)
                .notify(notificationIdCounter.incrementAndGet(), notification)
        } catch (_: Throwable) {
            // Safe no-op in unit test or restricted notification environments
        }
    }
}
