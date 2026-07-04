package com.viruchith.shredder

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import com.viruchith.shredder.browser.FileBrowserModel
import com.viruchith.shredder.browser.FileSelectionLogic
import com.viruchith.shredder.destructive.DestructiveOrchestrator
import com.viruchith.shredder.permissions.PermissionOrchestrator
import com.viruchith.shredder.security.SecurityGating
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
        // Load the stored configuration on startup
        ShredderEngine.init(applicationContext)

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
        val securityGating = remember { SecurityGating() }

        // Enforce strong device lock screen security posture.
        var isDeviceSecure by remember { mutableStateOf(securityGating.isDeviceSecure(context)) }

        if (!isDeviceSecure) {
            UnsecuredDeviceScreen(onRetry = {
                isDeviceSecure = securityGating.isDeviceSecure(context)
            })
            return
        }

        var isAuthenticated by remember { mutableStateOf(false) }

        if (!isAuthenticated) {
            AuthenticationBarrier(context, securityGating) { isAuthenticated = true }
            return
        }

        var showSettingsScreen by remember { mutableStateOf(false) }
        var showAboutScreen by remember { mutableStateOf(false) }

        if (showSettingsScreen) {
            SettingsScreen(onBack = { showSettingsScreen = false })
            return
        }

        if (showAboutScreen) {
            AboutScreen(onBack = { showAboutScreen = false })
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
        val currentPass by ShredderEngine.currentPassFlow.collectAsState()
        val consoleLogs by ShredderEngine.consoleLogFlow.collectAsState()
        val refreshTrigger by ShredderEngine.refreshTrigger.collectAsState()

        // Key used to force recomposition of the FileList when a shredding session ends.
        var fileListKey by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshTrigger) {
            fileListKey++
        }

        // Permission handling orchestrator.
        val orchestrator = remember { PermissionOrchestrator(context) }
        var permissionGranted by remember { mutableStateOf(orchestrator.hasAllPermissions()) }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            permissionGranted = orchestrator.hasAllPermissions()
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            permissionGranted = orchestrator.hasAllPermissions()
        }

        val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            permissionGranted = orchestrator.hasAllPermissions()
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
                    securityGating = securityGating,
                    onNavigate = { currentDir = it },
                    onSearchQueryChange = { searchQuery = it },
                    onSortTypeChange = { sortType = it },
                    onSortOrderChange = { sortOrder = it },
                    onAboutClick = { showAboutScreen = true },
                    onSettingsClick = { showSettingsScreen = true }
                )
            },
            bottomBar = {
                // Collapsible console view for progress tracking.
                ConsoleView(consoleLogs, progress, currentPass)
            }
        ) { innerPadding ->
            if (!permissionGranted) {
                // UI to guide user to grant necessary storage permissions.
                PermissionRequestScreen(innerPadding) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !orchestrator.hasNotificationPermission()) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = "package:${context.packageName}".toUri()
                        storagePermissionLauncher.launch(intent)
                    } else {
                        legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
                            val updated = FileSelectionLogic.toggleSelection(selectedFiles, file)
                            selectedFiles.clear()
                            selectedFiles.addAll(updated)
                        }
                    )
                )
            }
        }
    }

    @Composable
    private fun AuthenticationBarrier(
        context: Context,
        securityGating: SecurityGating,
        onSucceeded: () -> Unit
    ) {
        LaunchedEffect(Unit) {
            securityGating.authenticate(
                activity = context as FragmentActivity,
                title = "Shredder Lock",
                subtitle = "Authenticate to access your secure files",
                onSuccess = onSucceeded,
                onError = { errorCode, errString ->
                    if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        finish() // Close app if authentication is cancelled
                    } else {
                        Toast.makeText(context, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        // Show a blank screen or a splash while authenticating
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }

    @Composable
    private fun UnsecuredDeviceScreen(onRetry: () -> Unit) {
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dangerous,
                    contentDescription = "Unsecured Device",
                    tint = Color.Red,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Security Lock Required",
                    fontSize = 22.sp,
                    color = Color.Red,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "For your security, Secure Shredder requires a lock screen (PIN, Pattern, Password, or Biometrics) to safeguard your files. Please configure device lock security in your system settings.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text("Open Device Settings")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRetry) {
                    Text("Retry Check")
                }
            }
        }
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
        securityGating: SecurityGating,
        onNavigate: (File) -> Unit,
        onSearchQueryChange: (String) -> Unit,
        onSortTypeChange: (SortType) -> Unit,
        onSortOrderChange: (SortOrder) -> Unit,
        onAboutClick: () -> Unit,
        onSettingsClick: () -> Unit
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

                    NuclearAction(context, securityGating)

                    IconButton(onClick = onAboutClick) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }

                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
        var showHighRiskWarningDialog by remember { mutableStateOf(false) }

        IconButton(onClick = {
            if (DestructiveOrchestrator.hasHighRiskPaths(selectedFiles)) {
                showHighRiskWarningDialog = true
            } else {
                showConfirmDialog = true
            }
        }) {
            Icon(Icons.Default.DeleteForever, contentDescription = "Shred", tint = Color.Red)
        }

        if (showHighRiskWarningDialog) {
            AlertDialog(
                onDismissRequest = { showHighRiskWarningDialog = false },
                title = { Text("High-Risk Paths Selected") },
                text = {
                    Text("The current selection includes system-critical directories or the root folder of your external storage.\n\nTo safeguard your device against accidental OS bricking or severe storage corruption, Secure Shredder automatically filters out these locations from standard shredding.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showHighRiskWarningDialog = false
                        val safeFiles = DestructiveOrchestrator.filterSafelyShreddable(selectedFiles)
                        selectedFiles.clear()
                        selectedFiles.addAll(safeFiles)
                        if (selectedFiles.isNotEmpty()) {
                            showConfirmDialog = true
                        } else {
                            Toast.makeText(context, "No safely shreddable files remaining.", Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Text("FILTER & PROCEED", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHighRiskWarningDialog = false }) {
                        Text("CANCEL")
                    }
                }
            )
        }

        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text("Confirm Shredding") },
                text = {
                    val totalSize = ShredderEngine.calculateTotalSize(selectedFiles)
                    val fileCount = ShredderEngine.countFiles(selectedFiles)
                    Text("Are you sure you want to securely shred $fileCount items (${formatSize(totalSize)}) using the ${ShredderEngine.currentAlgorithm.name} algorithm? This action is IRREVERSIBLE.")
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
    private fun NuclearAction(context: Context, securityGating: SecurityGating) {
        var showNuclearDialog by remember { mutableStateOf(false) }
        var verificationText by remember { mutableStateOf("") }
        val requiredVerification = "NUCLEAR WIPE"

        IconButton(onClick = { showNuclearDialog = true }) {
            Icon(Icons.Default.Dangerous, contentDescription = "Nuclear Option", tint = Color.Red)
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
                    val list = FileBrowserModel.listDirContent(currentDir)
                    FileBrowserModel.processFiles(list, searchQuery, sortType, sortOrder)
                }
                val allSelected = FileSelectionLogic.isAllSelected(selectedFiles, currentFiles)

                Text("All", fontSize = 12.sp)
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val updated = FileSelectionLogic.selectAll(selectedFiles, currentFiles)
                            selectedFiles.clear()
                            selectedFiles.addAll(updated)
                        } else {
                            val updated = FileSelectionLogic.deselectAll(selectedFiles, currentFiles)
                            selectedFiles.clear()
                            selectedFiles.addAll(updated)
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
    fun ConsoleView(logs: List<String>, progress: Float, currentPass: String) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val progressLabel = if (currentPass.isNotEmpty()) currentPass else "Progress: ${(progress * 100).toInt()}%"
                        Text(progressLabel, color = Color.Green, fontSize = 14.sp)
                        Text("Algo: ${ShredderEngine.currentAlgorithm.name}", color = Color.Green, fontSize = 12.sp)
                    }
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
            val list = FileBrowserModel.listDirContent(params.dir)
            FileBrowserModel.processFiles(list, params.searchQuery, params.sortType, params.sortOrder)
        }

        if (files.isEmpty()) {
            Box(
                modifier = params.modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files found or directory unreadable.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(modifier = params.modifier) {
                items(files) { file ->
                    FileItem(file, params.selectedFiles.contains(file), params.onDirClick, params.onFileToggle)
                }
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

    /**
     * Renders a highly professional About screen showing authorship, project repository links,
     * copyright details, and a concise security legal notice disclaimer.
     */
    @Composable
    fun AboutScreen(onBack: () -> Unit) {
        val context = LocalContext.current
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("About") },
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Secure Shredder",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Version 1.2",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Author Section
                Text(
                    text = "Author",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Viruchith Ganesan",
                    style = MaterialTheme.typography.bodyLarge
                )

                // Links Section
                Text(
                    text = "Links",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                val websiteUri = "https://viruchith.com"
                val repoUri = "https://github.com/viruchith/AndroidShredder"

                Text(
                    text = "Website: $websiteUri",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, websiteUri.toUri())
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Text(
                    text = "GitHub: $repoUri",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, repoUri.toUri())
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Copyright Section
                val currentYear = java.util.Calendar.getInstance()[java.util.Calendar.YEAR]
                Text(
                    text = "© $currentYear Viruchith Ganesan. All rights reserved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Legal Disclaimer
                Text(
                    text = "Disclaimer & Legal Terms",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Red
                )
                Text(
                    text = "Secure Shredder is a high-security tool that performs permanent, IRREVERSIBLE file destruction. " +
                           "By using this application, you agree that you are solely responsible for its utilization and any resulting loss of data. " +
                           "The author and contributors provide this software 'as-is' and shall not be held liable for accidental files deletion, " +
                           "bricked systems, or disk formatting issues caused by shredding operations or the Nuclear option. Please use with caution.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            }
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
