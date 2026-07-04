package com.viruchith.shredder

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.viruchith.shredder.security.SecurityGating

/**
 * SettingsScreen presents configuration choices for the application, such as
 * choosing the active secure erasure algorithm, and isolates highly destructive features
 * like the Device Admin and Nuclear Wipe options into a dedicated Danger Zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    securityGating: SecurityGating,
    deviceAdminLauncher: ActivityResultLauncher<Intent>,
    onBack: () -> Unit
) {
    var selectedAlgo by remember { mutableStateOf(ShredderEngine.currentAlgorithm) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Shredding Algorithm",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ShredAlgorithm.values().forEach { algo ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedAlgo = algo
                            ShredderEngine.setAlgorithm(algo)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedAlgo == algo) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedAlgo == algo),
                            onClick = {
                                selectedAlgo = algo
                                ShredderEngine.setAlgorithm(algo)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${algo.name} (${algo.passes.size} ${if (algo.passes.size == 1) "pass" else "passes"})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(algo.colorHex)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = algo.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• ${algo.meaning}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(algo.colorHex),
                                fontWeight = FontWeight.Bold
                            )
                            
                            if (algo == ShredAlgorithm.Gutmann) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = Color.Red,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Extremely slow — 35 passes",
                                        color = Color.Red,
                                        fontSize = 12.sp,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                color = Color.Red
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color(0xFFFFF8F8)
                ),
                border = BorderStroke(1.dp, Color.Red),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Nuclear Option",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Red
                    )
                    Text(
                        text = "Permanently wipe all storage and factory reset the device. This is IRREVERSIBLE.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Enable Device Admin Button
                    Button(
                        onClick = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, ShredderDeviceAdminReceiver::class.java))
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for the Nuclear Option (Factory Reset).")
                            }
                            deviceAdminLauncher.launch(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Device Admin")
                    }

                    // Initiate Nuclear Option Button
                    var showNuclearDialog by remember { mutableStateOf(false) }
                    var verificationText by remember { mutableStateOf("") }
                    val requiredVerification = "NUCLEAR WIPE"

                    Button(
                        onClick = { showNuclearDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wipe & Reset")
                    }

                    if (showNuclearDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                showNuclearDialog = false
                                verificationText = ""
                            },
                            title = { Text("WARNING: NUCLEAR OPTION", color = Color.Red) },
                            text = {
                                Column {
                                    Text(
                                        "This action will perform a secure overwrite of all free space, wipe all user storage, and execute a full FACTORY RESET.\n\nAll photos, files, and accounts will be permanently destroyed. This is IRREVERSIBLE.\n\nTo proceed, please type \"$requiredVerification\" below:",
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    TextField(
                                        value = verificationText,
                                        onValueChange = { verificationText = it },
                                        placeholder = { Text("Type phrase here") },
                                        singleLine = true,
                                        isError = verificationText.isNotEmpty() && verificationText != requiredVerification,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showNuclearDialog = false
                                        verificationText = ""
                                        securityGating.authenticate(
                                            activity = context as FragmentActivity,
                                            title = "Nuclear Option Authorization",
                                            subtitle = "Authenticate to execute a total wipe and factory reset.",
                                            onSuccess = {
                                                val intent = Intent(context, ShredderService::class.java).apply {
                                                    putExtra("fullWipe", true)
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                            },
                                            onError = { _, errString ->
                                                Toast.makeText(context, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    },
                                    enabled = verificationText == requiredVerification
                                ) {
                                    Text("INITIATE WIPE", color = Color.Red)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showNuclearDialog = false
                                    verificationText = ""
                                }) {
                                    Text("CANCEL")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
