package com.viruchith.shredder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * ShredderService is a Foreground Service that executes file shredding operations.
 * Using a Foreground Service ensures that the Android system does not kill the process 
 * during long-running I/O intensive tasks, even if the user switches apps.
 */
class ShredderService : Service() {

    private val CHANNEL_ID = "ShredderServiceChannel"
    private val NOTIFICATION_ID = 1
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var freeSpaceNotificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FreeSpaceWipeContract.ACTION_CANCEL_FREE_SPACE_WIPE) {
            val sessionId = intent.getStringExtra(FreeSpaceWipeContract.EXTRA_SESSION_ID)
            if (!sessionId.isNullOrBlank()) {
                ShredderEngine.requestCancelFreeSpaceWipe(sessionId)
                updateNotification(
                    createFreeSpaceNotification(
                        title = "Free Space Wipe cancellation requested",
                        text = null,
                        sessionId = sessionId,
                        includeCancelAction = false
                    )
                )
            }
            return START_NOT_STICKY
        }

        val files = intent?.getSerializableExtra("files") as? ArrayList<File>
        val fullWipe = intent?.getBooleanExtra("fullWipe", false) ?: false
        val mode = intent?.getStringExtra(FreeSpaceWipeContract.EXTRA_MODE)
        val sessionId = intent?.getStringExtra(FreeSpaceWipeContract.EXTRA_SESSION_ID)

        // Promoting the service to Foreground immediately to satisfy OS requirements.
        val notification = createNotification("Starting shredding session...")
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock("active_operation")

        if (mode == FreeSpaceWipeContract.MODE_FREE_SPACE_WIPE_ONLY) {
            if (fullWipe) {
                // Hard guard: free-space-only mode must never invoke factory reset.
                updateNotification(createNotification("Rejected invalid request: Free Space Wipe cannot factory reset."))
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }

            val wipeSessionId = sessionId ?: ShredderEngine.newSessionId()
            startForeground(
                NOTIFICATION_ID,
                createFreeSpaceNotification(
                    title = "Free Space Wipe in progress",
                    text = null,
                    sessionId = wipeSessionId,
                    includeCancelAction = true
                )
            )

            observeFreeSpaceProgress(wipeSessionId)
            val wipeBaseDir = externalCacheDir
                ?: File(
                    Environment.getExternalStorageDirectory(),
                    "Android/data/$packageName/cache"
                )
            ShredderEngine.wipeFreeSpaceSafely(wipeBaseDir, wipeSessionId) { result ->
                freeSpaceNotificationJob?.cancel()
                when (result) {
                    is FreeSpaceWipeResult.Completed -> {
                        updateNotification(
                            createFreeSpaceNotification(
                                "Free Space Wipe completed",
                                result,
                                wipeSessionId,
                                false
                            )
                        )
                    }

                    is FreeSpaceWipeResult.Cancelled -> {
                        updateNotification(
                            createFreeSpaceNotification(
                                "Free Space Wipe cancelled",
                                result,
                                wipeSessionId,
                                false
                            )
                        )
                    }

                    is FreeSpaceWipeResult.Failed -> {
                        updateNotification(
                            createFreeSpaceNotification(
                                "Free Space Wipe failed",
                                result,
                                wipeSessionId,
                                false
                            )
                        )
                    }
                }
                releaseWakeLock()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (fullWipe) {
            // High-risk path: Wipe entire storage and factory reset.
            Thread {
                ShredderEngine.wipeFreeSpace(Environment.getExternalStorageDirectory())
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                try {
                    // This call triggers a factory reset. Requires Device Admin permission.
                    dpm.wipeData(0)
                } catch (e: SecurityException) {
                    updateNotification(createNotification("Failed: Device Admin not enabled."))
                }
                releaseWakeLock()
                stopSelf()
            }.start()
        } else if (files != null) {
            // Standard path: Shred specific user-selected files.
            ShredderEngine.shredFiles(files) {
                releaseWakeLock()
                stopSelf()
            }
        } else {
            releaseWakeLock()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        freeSpaceNotificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock(tag: String) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShredderService::$tag").also {
            it.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
            // Ignore already-released lock edge cases.
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Shredder Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance to avoid intrusive sounds during long tasks.
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Shredder")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun observeFreeSpaceProgress(sessionId: String) {
        freeSpaceNotificationJob?.cancel()
        freeSpaceNotificationJob = serviceScope.launch {
            ShredderEngine.freeSpaceWipeStateFlow.collect { state ->
                if (state.sessionId != sessionId) return@collect

                if (state.status == FreeSpaceWipeStatus.WRITING || state.status == FreeSpaceWipeStatus.CLEANING_UP) {
                    val writtenText = formatBytes(state.writtenBytes)
                    val percent = (state.progress * 100).toInt()
                    val title = if (state.status == FreeSpaceWipeStatus.CLEANING_UP) {
                        "Free Space Wipe cleaning up"
                    } else {
                        "Free Space Wipe in progress"
                    }
                    updateNotification(
                        createFreeSpaceNotification(
                            title = title,
                            text = "$percent% • Written $writtenText",
                            sessionId = sessionId,
                            includeCancelAction = state.status == FreeSpaceWipeStatus.WRITING
                        )
                    )
                }
            }
        }
    }

    private fun createFreeSpaceNotification(
        title: String,
        result: FreeSpaceWipeResult?,
        sessionId: String,
        includeCancelAction: Boolean
    ): Notification {
        val text = when (result) {
            is FreeSpaceWipeResult.Completed -> "100% • Written ${formatBytes(result.writtenBytes)}"
            is FreeSpaceWipeResult.Cancelled -> "Written ${formatBytes(result.writtenBytes)}"
            is FreeSpaceWipeResult.Failed -> result.errorMessage
            null -> "0% • Written 0 B"
        }
        return createFreeSpaceNotification(title, text, sessionId, includeCancelAction)
    }

    private fun createFreeSpaceNotification(
        title: String,
        text: String?,
        sessionId: String,
        includeCancelAction: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text ?: "")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOnlyAlertOnce(true)

        if (includeCancelAction) {
            val cancelIntent = Intent(this, ShredderService::class.java).apply {
                action = FreeSpaceWipeContract.ACTION_CANCEL_FREE_SPACE_WIPE
                putExtra(FreeSpaceWipeContract.EXTRA_SESSION_ID, sessionId)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val cancelPendingIntent =
                PendingIntent.getService(this, 701, cancelIntent, pendingFlags)
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
        }

        return builder.build()
    }

    private fun formatBytes(size: Long): String {
        if (size <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.2f %s", value, units[unitIndex])
    }
}
