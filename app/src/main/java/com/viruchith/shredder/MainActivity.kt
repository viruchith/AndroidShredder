package com.viruchith.shredder

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.viruchith.shredder.ui.theme.ShredderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity serves as the primary entry point and UI controller.
 * It uses Jetpack Compose for the UI and BiometricPrompt for security-critical actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : FragmentActivity() {

    // Enumerations for file sorting logic.
    enum class SortType { NAME, SIZE, DATE }
    enum class SortOrder { ASC, DESC }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge to utilize the full screen height behind status and navigation bars.
        enableEdgeToEdge()
        setContent {
            ShredderTheme {
                MainScreen()
            }
        }
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        // State management for navigation and file selection.
        var currentDir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
        val selectedFiles = remember { mutableStateListOf<File>() }
        var searchQuery by remember { mutableStateOf("") }
        var sortType by remember { mutableStateOf(SortType.NAME) }
        var sortOrder by remember { mutableStateOf(SortOrder.ASC) }
        
        // Collect flows from the ShredderEngine for reactive UI updates.
        val progress by ShredderEngine.progressFlow.collectAsState()
        val consoleLogs by ShredderEngine.consoleLogFlow.collectAsState()
        val refreshTrigger by ShredderEngine.refreshTrigger.collectAsState()

        // Key used to force recomposition of the FileList when a shredding session ends.
        var fileListKey by remember { mutableStateOf(0) }
        
        LaunchedEffect(refreshTrigger) {
            fileListKey++
        }

        // Permission handling for MANAGE_EXTERNAL_STORAGE (required for shredding on API 30+).
        val hasPermission = checkStoragePermission(context)
        var permissionGranted by remember { mutableStateOf(hasPermission) }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            permissionGranted = checkStoragePermission(context)
        }

        val deviceAdminLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Callback for Device Admin activation.
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Secure Shredder") },
                        actions = {
                            // Button to request Device Admin status (needed for factory reset).
                            IconButton(onClick = {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, ShredderDeviceAdminReceiver::class.java))
                                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for the Nuclear Option (Factory Reset).")
                                deviceAdminLauncher.launch(intent)
                            }) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = "Enable Admin")
                            }
                            
                            // Shredding action button, visible only when files are selected.
                            if (selectedFiles.isNotEmpty()) {
                                var showConfirmDialog by remember { mutableStateOf(false) }
                                
                                IconButton(onClick = { showConfirmDialog = true }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Shred", tint = Color.Red)
                                }

                                if (showConfirmDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showConfirmDialog = false },
                                        title = { Text("Confirm Shredding") },
                                        text = { 
                                            val totalSize = ShredderEngine.calculateTotalSize(selectedFiles)
                                            val fileCount = ShredderEngine.countFiles(selectedFiles)
                                            Text("Are you sure you want to securely shred $fileCount items (${formatSize(totalSize)})? This action is IRREVERSIBLE.") 
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                showConfirmDialog = false
                                                // Start the Foreground Service to perform shredding in the background.
                                                val intent = Intent(context, ShredderService::class.java).apply {
                                                    putExtra("files", ArrayList(selectedFiles))
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                                selectedFiles.clear()
                                            }) {
                                                Text("SHRED", color = Color.Red)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showConfirmDialog = false }) {
                                                Text("CANCEL")
                                            }
                                        }
                                    )
                                }
                            }
                            // The "Nuclear Option" for factory resetting the device.
                            IconButton(onClick = { showNuclearDialog(context) }) {
                                Icon(Icons.Default.Dangerous, contentDescription = "Nuclear Option", tint = Color.Red)
                            }
                        }
                    )
                    
                    // Navigation and Filtering UI components.
                    BreadcrumbUI(currentDir) { currentDir = it }
                    SearchBar(searchQuery) { searchQuery = it }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SortBar(sortType, sortOrder, { sortType = it }, { sortOrder = it })
                        }
                        
                        // Select/Deselect All functionality.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val currentFiles = remember(currentDir, searchQuery, fileListKey) {
                                var list = currentDir.listFiles()?.toList() ?: emptyList()
                                if (searchQuery.isNotEmpty()) {
                                    list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                }
                                list
                            }
                            val allSelected = currentFiles.isNotEmpty() && currentFiles.all { selectedFiles.contains(it) }
                            
                            Text("All", fontSize = 12.sp)
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        currentFiles.forEach { if (!selectedFiles.contains(it)) selectedFiles.add(it) }
                                    } else {
                                        currentFiles.forEach { selectedFiles.remove(it) }
                                    }
                                }
                            )
                        }
                    }
                }
            },
            bottomBar = {
                // Collapsible console view for progress tracking.
                ConsoleView(consoleLogs, progress)
            }
        ) { innerPadding ->
            if (!permissionGranted) {
                // UI to guide user to grant necessary storage permissions.
                PermissionRequestScreen(innerPadding) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        storagePermissionLauncher.launch(intent)
                    }
                }
            } else {
                // Main file explorer view.
                FileList(
                    modifier = Modifier.padding(innerPadding),
                    key = fileListKey,
                    dir = currentDir,
                    selectedFiles = selectedFiles,
                    searchQuery = searchQuery,
                    sortType = sortType,
                    sortOrder = sortOrder,
                    onDirClick = { currentDir = it },
                    onFileToggle = { file ->
                        if (selectedFiles.contains(file)) selectedFiles.remove(file)
                        else selectedFiles.add(file)
                    }
                )
            }
        }
    }

    /**
     * Renders a clickable path that allows users to jump back to parent directories.
     */
    @Composable
    fun BreadcrumbUI(currentDir: File, onNavigate: (File) -> Unit) {
        val parts = currentDir.absolutePath.split("/").filter { it.isNotEmpty() }
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Root", modifier = Modifier.clickable { onNavigate(Environment.getExternalStorageDirectory()) }.padding(4.dp))
            parts.forEachIndexed { index, part ->
                Text(" > ", color = Color.Gray)
                val path = "/" + parts.take(index + 1).joinToString("/")
                Text(part, modifier = Modifier.clickable { onNavigate(File(path)) }.padding(4.dp))
            }
        }
    }

    @Composable
    fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Search files...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }

    @Composable
    fun SortBar(
        type: SortType,
        order: SortOrder,
        onTypeChange: (SortType) -> Unit,
        onOrderChange: (SortOrder) -> Unit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SortButton("Name", type == SortType.NAME) { onTypeChange(SortType.NAME) }
            SortButton("Size", type == SortType.SIZE) { onTypeChange(SortType.SIZE) }
            SortButton("Date", type == SortType.DATE) { onTypeChange(SortType.DATE) }
            IconButton(onClick = { onOrderChange(if (order == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC) }) {
                Icon(
                    if (order == SortOrder.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = "Order",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    @Composable
    fun SortButton(label: String, selected: Boolean, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray, fontSize = 12.sp)
        }
    }

    /**
     * Displays real-time status and logs. Collapsible to save screen space.
     */
    @Composable
    fun ConsoleView(logs: List<String>, progress: Float) {
        var isExpanded by remember { mutableStateOf(false) }
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color.Green,
                        trackColor = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Progress: ${(progress * 100).toInt()}%", color = Color.Green, fontSize = 14.sp)
                }
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.height(240.dp)) {
                    LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                        items(logs.reversed()) { log ->
                            Text(log, color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }

    /**
     * Efficiently lists files with sorting and filtering applied via a 'remember' block.
     */
    @Composable
    fun FileList(
        modifier: Modifier,
        key: Int,
        dir: File,
        selectedFiles: List<File>,
        searchQuery: String,
        sortType: SortType,
        sortOrder: SortOrder,
        onDirClick: (File) -> Unit,
        onFileToggle: (File) -> Unit
    ) {
        val files = remember(dir, searchQuery, sortType, sortOrder, key) {
            var list = dir.listFiles()?.toList() ?: emptyList()
            if (searchQuery.isNotEmpty()) {
                list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            list = when (sortType) {
                SortType.NAME -> list.sortedBy { it.name.lowercase() }
                SortType.SIZE -> list.sortedBy { it.length() }
                SortType.DATE -> list.sortedBy { it.lastModified() }
            }
            if (sortOrder == SortOrder.DESC) list = list.reversed()
            // Directories always appear first in the list.
            list.sortedWith(compareBy { !it.isDirectory })
        }

        LazyColumn(modifier = modifier) {
            items(files) { file ->
                FileItem(file, selectedFiles.contains(file), onDirClick, onFileToggle)
            }
        }
    }

    @Composable
    fun FileItem(file: File, isSelected: Boolean, onDirClick: (File) -> Unit, onFileToggle: (File) -> Unit) {
        // State for storing file/folder size info calculated in a background thread.
        var recursiveInfo by remember { mutableStateOf<Pair<Int, Long>?>(null) }

        LaunchedEffect(file) {
            if (file.isDirectory) {
                withContext(Dispatchers.IO) {
                    val count = ShredderEngine.countFiles(listOf(file))
                    val size = ShredderEngine.calculateTotalSize(listOf(file))
                    recursiveInfo = Pair(count, size)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (file.isDirectory) onDirClick(file) else onFileToggle(file) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, maxLines = 1)
                val info = if (file.isDirectory) {
                    recursiveInfo?.let { "${it.first} files, ${formatSize(it.second)}" } ?: "Calculating..."
                } else {
                    "${formatSize(file.length())} | ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(file.lastModified()))}"
                }
                Text(info, fontSize = 12.sp, color = Color.Gray)
            }
            Checkbox(checked = isSelected, onCheckedChange = { onFileToggle(file) })
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    /**
     * Uses BiometricPrompt to authenticate the user before executing the high-risk "Nuclear Option".
     */
    private fun showNuclearDialog(context: Context) {
        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val intent = Intent(context, ShredderService::class.java).apply {
                    putExtra("fullWipe", true)
                }
                ContextCompat.startForegroundService(context, intent)
                // wipeData(0) would be called after the service finishes the secure wipe.
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Nuclear Option")
            .setSubtitle("Authenticate to wipe entire storage and reset device.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun checkStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Composable
    fun PermissionRequestScreen(innerPadding: PaddingValues, onGrant: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("App needs All Files Access to shred files.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onGrant) { Text("Grant Permission") }
            }
        }
    }
}
