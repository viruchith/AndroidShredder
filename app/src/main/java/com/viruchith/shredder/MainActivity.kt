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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.viruchith.shredder.ui.theme.ShredderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

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
        var isAuthenticated by remember { mutableStateOf(false) }

        if (!isAuthenticated) {
            AuthenticationBarrier(context) { isAuthenticated = true }
            return
        }

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
        var fileListKey by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshTrigger) {
            fileListKey++
        }

        // Permission handling for MANAGE_EXTERNAL_STORAGE and POST_NOTIFICATIONS.
        val hasPermission = checkStoragePermission(context)
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        
        var permissionGranted by remember { mutableStateOf(hasPermission && hasNotificationPermission) }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            permissionGranted = checkStoragePermission(context) && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true)
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            permissionGranted = checkStoragePermission(context) && it
        }

        val deviceAdminLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Callback for Device Admin activation.
        }

        Scaffold(
            topBar = {
                MainTopBar(
                    context = context,
                    currentDir = currentDir,
                    selectedFiles = selectedFiles,
                    searchQuery = searchQuery,
                    sortType = sortType,
                    sortOrder = sortOrder,
                    fileListKey = fileListKey,
                    deviceAdminLauncher = deviceAdminLauncher,
                    onNavigate = { currentDir = it },
                    onSearchQueryChange = { searchQuery = it },
                    onSortTypeChange = { sortType = it },
                    onSortOrderChange = { sortOrder = it }
                )
            },
            bottomBar = {
                // Collapsible console view for progress tracking.
                ConsoleView(consoleLogs, progress)
            }
        ) { innerPadding ->
            if (!permissionGranted) {
                // UI to guide user to grant necessary storage permissions.
                PermissionRequestScreen(innerPadding) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = "package:${context.packageName}".toUri()
                        storagePermissionLauncher.launch(intent)
                    }
                }
            } else {
                // Main file explorer view.
                FileList(
                    params = FileListParams(
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
                )
            }
        }
    }

    @Composable
    private fun AuthenticationBarrier(context: Context, onSucceeded: () -> Unit) {
        LaunchedEffect(Unit) {
            authenticateUser(context, onSucceeded)
        }
        // Show a blank screen or a splash while authenticating
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }

    @Composable
    private fun MainTopBar(
        context: Context,
        currentDir: File,
        selectedFiles: MutableList<File>,
        searchQuery: String,
        sortType: SortType,
        sortOrder: SortOrder,
        fileListKey: Int,
        deviceAdminLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
        onNavigate: (File) -> Unit,
        onSearchQueryChange: (String) -> Unit,
        onSortTypeChange: (SortType) -> Unit,
        onSortOrderChange: (SortOrder) -> Unit
    ) {
        Column {
            TopAppBar(
                title = { Text("Secure Shredder") },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, ShredderDeviceAdminReceiver::class.java))
                        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for the Nuclear Option (Factory Reset).")
                        deviceAdminLauncher.launch(intent)
                    }) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = "Enable Admin")
                    }

                    if (selectedFiles.isNotEmpty()) {
                        ShredAction(context, selectedFiles)
                    }

                    IconButton(onClick = { showNuclearDialog(context) }) {
                        Icon(Icons.Default.Dangerous, contentDescription = "Nuclear Option", tint = Color.Red)
                    }
                }
            )

            BreadcrumbUI(currentDir, onNavigate)
            SearchBar(searchQuery, onSearchQueryChange)

            SelectionAndSortBar(
                currentDir = currentDir,
                searchQuery = searchQuery,
                fileListKey = fileListKey,
                selectedFiles = selectedFiles,
                sortType = sortType,
                sortOrder = sortOrder,
                onSortTypeChange = onSortTypeChange,
                onSortOrderChange = onSortOrderChange
            )
        }
    }

    @Composable
    private fun ShredAction(context: Context, selectedFiles: MutableList<File>) {
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

    @Composable
    private fun SelectionAndSortBar(
        currentDir: File,
        searchQuery: String,
        fileListKey: Int,
        selectedFiles: MutableList<File>,
        sortType: SortType,
        sortOrder: SortOrder,
        onSortTypeChange: (SortType) -> Unit,
        onSortOrderChange: (SortOrder) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SortBar(sortType, sortOrder, onSortTypeChange, onSortOrderChange)
            }

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

    data class FileListParams(
        val modifier: Modifier,
        val key: Int,
        val dir: File,
        val selectedFiles: List<File>,
        val searchQuery: String,
        val sortType: SortType,
        val sortOrder: SortOrder,
        val onDirClick: (File) -> Unit,
        val onFileToggle: (File) -> Unit
    )

    /**
     * Efficiently lists files with sorting and filtering applied via a 'remember' block.
     */
    @Composable
    fun FileList(params: FileListParams) {
        val files = remember(params.dir, params.searchQuery, params.sortType, params.sortOrder, params.key) {
            var list = params.dir.listFiles()?.toList() ?: emptyList()
            if (params.searchQuery.isNotEmpty()) {
                list = list.filter { it.name.contains(params.searchQuery, ignoreCase = true) }
            }
            list = when (params.sortType) {
                SortType.NAME -> list.sortedBy { it.name.lowercase() }
                SortType.SIZE -> list.sortedBy { it.length() }
                SortType.DATE -> list.sortedBy { it.lastModified() }
            }
            if (params.sortOrder == SortOrder.DESC) list = list.reversed()
            // Directories always appear first in the list.
            list.sortedWith(compareBy { !it.isDirectory })
        }

        LazyColumn(modifier = params.modifier) {
            items(files) { file ->
                FileItem(file, params.selectedFiles.contains(file), params.onDirClick, params.onFileToggle)
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
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
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

    private fun authenticateUser(context: Context, onSucceeded: () -> Unit) {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        
        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(context)
                val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onSucceeded()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            finish() // Close app if authentication is cancelled
                        } else {
                            Toast.makeText(context, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                        }
                    }
                })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Shredder Lock")
                    .setSubtitle("Authenticate to access your secure files")
                    .setAllowedAuthenticators(authenticators)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                // If no biometrics or screen lock is set up, allow access but show a warning
                Toast.makeText(context, "No device security found. Please set up a PIN or Biometrics.", Toast.LENGTH_LONG).show()
                onSucceeded()
            }
        }
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
