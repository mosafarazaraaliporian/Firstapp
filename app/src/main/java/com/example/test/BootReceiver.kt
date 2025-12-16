package com.example.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.example.test.utils.AlarmManagerHelper
import com.example.test.utils.DirectBootHelper
import com.example.test.utils.UnifiedWatchdogScheduler
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        DirectBootHelper.logStatus(context)

        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                startAllServices(context, isLocked = true)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    DirectBootHelper.migrateStorageIfNeeded(context)
                }
                startAllServices(context, isLocked = false)
            }
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT -> {
                startAllServices(context, isLocked = false)
            }
            Intent.ACTION_USER_UNLOCKED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    DirectBootHelper.migrateStorageIfNeeded(context)
                }
                startAllServices(context, isLocked = false)
            }
        }
    }

    private fun startAllServices(context: Context, isLocked: Boolean) {
        try {
            val workingContext = DirectBootHelper.getContext(context)
            
            try {
                ServerConfig.initialize(workingContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ServerConfig: ${e.message}")
            }
            
            // 1) Schedule WM watchdog (primary restarter)
            UnifiedWatchdogScheduler.schedule(workingContext)
            
            // 2) Schedule AlarmManager (secondary wake in Doze)
            AlarmManagerHelper.scheduleServiceRestart(workingContext)
            AlarmManagerHelper.scheduleLongRunPeriodicServiceRestart(workingContext)
            
            Handler(Looper.getMainLooper()).postDelayed({
                startUnifiedService(workingContext)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    com.example.test.utils.JobSchedulerHelper.scheduleHeartbeatJob(workingContext)
                }
                
                Handler(Looper.getMainLooper()).postDelayed({
                    initializeFirebaseMessaging(workingContext)
                    sendBootPing(workingContext)
                }, 2000)
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting services", e)
        }
    }

    private fun startUnifiedService(context: Context) {
        try {
            val serviceIntent = Intent(context, UnifiedService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "UnifiedService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UnifiedService", e)
        }
    }
    
    private fun initializeFirebaseMessaging(context: Context) {
        try {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful || task.result == null) {
                        Log.e(TAG, "Failed to get FCM Token: ${task.exception?.message}")
                    }
                }
            
            FirebaseMessaging.getInstance().subscribeToTopic("all_devices")
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            initializeFirebaseMessaging(context)
                        }, 30000)
                    }
                }
            
            // UnifiedWatchdogWorker is already scheduled in startAllServices()
            // It handles both heartbeat and service monitoring
            try {
                // Also schedule HeartbeatWorker as backup (optional)
                val workRequest = androidx.work.PeriodicWorkRequestBuilder<HeartbeatWorker>(
                    15,
                    java.util.concurrent.TimeUnit.MINUTES,
                    5,
                    java.util.concurrent.TimeUnit.MINUTES
                )
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL,
                        10,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                    .addTag("heartbeat")
                    .build()

                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    HeartbeatWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Keep existing if UnifiedWatchdogWorker is running
                    workRequest
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart WorkManager: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase Messaging: ${e.message}", e)
        }
    }
    
    private fun sendBootPing(context: Context) {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("isOnline", true)
                    put("timestamp", System.currentTimeMillis())
                    put("source", "BootReceiver")
                    put("event", "device_booted")
                }

                val baseUrl = ServerConfig.getBaseUrl()
                val urlString = "$baseUrl/ping-response"

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                conn.outputStream.use { os ->
                    val bytes = body.toString().toByteArray()
                    os.write(bytes)
                    os.flush()
                }

                val responseCode = conn.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }

                conn.disconnect()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send boot ping: ${e.message}", e)
            }
        }.start()
    }
}