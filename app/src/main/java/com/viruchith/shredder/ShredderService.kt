package com.viruchith.shredder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * ShredderService is a Foreground Service that executes file shredding operations.
 * Using a Foreground Service ensures that the Android system does not kill the process 
 * during long-running I/O intensive tasks, even if the user switches apps.
 */
class ShredderService : Service() {

    private val CHANNEL_ID = "ShredderServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val files = intent?.getSerializableExtra("files") as? ArrayList<File>
        val fullWipe = intent?.getBooleanExtra("fullWipe", false) ?: false

        // Promoting the service to Foreground immediately to satisfy OS requirements.
        val notification = createNotification("Starting shredding session...")
        startForeground(1, notification)

        if (fullWipe) {
            // High-risk path: Wipe entire storage and factory reset.
            Thread {
                ShredderEngine.wipeFreeSpace(Environment.getExternalStorageDirectory())
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                try {
                    // This call triggers a factory reset. Requires Device Admin permission.
                    dpm.wipeData(0)
                } catch (e: SecurityException) {
                    updateNotification("Failed: Device Admin not enabled.")
                }
                stopSelf()
            }.start()
        } else if (files != null) {
            // Standard path: Shred specific user-selected files.
            ShredderEngine.shredFiles(files) {
                stopSelf()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(content))
    }
}
