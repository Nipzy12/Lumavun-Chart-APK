package com.example.notifications

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Requirement #7: Android-compatible Foreground Service for continuous market & bot simulation monitoring.
 */
class MarketMonitorForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE
        if (action == ACTION_STOP) {
            isRunning = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val symbol = intent?.getStringExtra(EXTRA_SYMBOL) ?: "BTCUSDT"
        val timeframe = intent?.getStringExtra(EXTRA_TIMEFRAME) ?: "15m"
        val botStatus = intent?.getStringExtra(EXTRA_BOT_STATUS) ?: "Monitoring"
        val summary = intent?.getStringExtra(EXTRA_SUMMARY)
            ?: "Auto Signal & Bot Simulasi ($botStatus) · $symbol $timeframe"

        TerminalNotificationHelper.ensureChannels(this)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TerminalNotificationHelper.CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_stat_nexsync)
            .setContentTitle("LUMAVUN Chart · Live Monitor")
            .setContentText(summary)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            isRunning = true
            START_STICKY
        } catch (_: Throwable) {
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1101
        const val ACTION_START_OR_UPDATE = "com.example.notifications.START_OR_UPDATE"
        const val ACTION_STOP = "com.example.notifications.STOP"
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_TIMEFRAME = "extra_timeframe"
        const val EXTRA_BOT_STATUS = "extra_bot_status"
        const val EXTRA_SUMMARY = "extra_summary"

        @Volatile
        private var isRunning: Boolean = false

        fun startOrUpdate(
            context: Context,
            symbol: String,
            timeframe: String,
            botStatus: String,
            summary: String
        ) {
            try {
                val intent = Intent(context, MarketMonitorForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_SYMBOL, symbol)
                    putExtra(EXTRA_TIMEFRAME, timeframe)
                    putExtra(EXTRA_BOT_STATUS, botStatus)
                    putExtra(EXTRA_SUMMARY, summary)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // Ignore in test/restricted context
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                val intent = Intent(context, MarketMonitorForegroundService::class.java)
                context.stopService(intent)
                isRunning = false
            } catch (_: Throwable) {
                // Ignore in test/restricted context
            }
        }
    }
}
