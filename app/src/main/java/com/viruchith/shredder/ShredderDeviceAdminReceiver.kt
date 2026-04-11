package com.viruchith.shredder

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * ShredderDeviceAdminReceiver is a specialized broadcast receiver required 
 * to grant the app Device Administrator privileges.
 * This is a mandatory component for performing a factory reset (wipeData) 
 * via the DevicePolicyManager API.
 */
class ShredderDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Invoked when the user successfully enables the app as a Device Admin.
        Toast.makeText(context, "Device Admin: Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Invoked when the user revokes Device Admin privileges.
        Toast.makeText(context, "Device Admin: Disabled", Toast.LENGTH_SHORT).show()
    }
}
