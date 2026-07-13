package command.plus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
// --- 状态与 SharedPreferences 扩展 ---
private const val PREFS_NAME = "ScriptPrefs"

@Composable
fun ScriptCommandScreen(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        HomeScreen(onBack = onBack)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 全局状态管理：悬浮窗真实状态
    var isWindowShown by remember { mutableStateOf(false) }

    // --- 同步悬浮窗状态 ---
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val service = MainAccessibilityService.getInstance()
                isWindowShown = service?.isWindowShown() == true
                
                // 绑定监听器：即使在 Service 内部或通过悬浮窗自带的按钮关闭，也能通知到 UI 更新文字
                try {
                    service?.setWindowStateListener { isShown ->
                        isWindowShown = isShown
                    }
                } catch (e: Exception) {
                    // 如果你的 Service 没有提供 setWindowStateListener，请忽略
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                MainAccessibilityService.getInstance()?.setWindowStateListener(null)
            } catch (e: Exception) {}
        }
    }

    // --- 卡片1：选择指令文件 状态 ---
    var sourceMode by remember { mutableIntStateOf(0) }
    val sourceOptions = listOf("无", "从已知中获取", "从链接中获取")
    
    val dir1 = remember {
    File(context.getExternalFilesDir(null), "MusicOutput").apply { mkdirs() }
}

val dir2 = remember {
    File(context.getExternalFilesDir(null), "PixelArtResult").apply { mkdirs() }
}

val knownMusicFiles = rememberDirectoryFiles(listOf(dir1, dir2))
    var selectedKnownMusicPath by remember { mutableStateOf("") }
    var linkInput by remember { mutableStateOf("") }

    // --- 卡片2：自动操作脚本 状态 ---
    var scriptSelectionMode by remember { mutableIntStateOf(prefs.getInt("script_mode", 0)) }
    val scriptSourceOptions = listOf("在默认中选择", "选择指定文件")
    
    var selectedDefaultScriptPath by remember { mutableStateOf(prefs.getString("default_script", "") ?: "") }
    var specificScriptPath by remember { mutableStateOf(prefs.getString("specific_path", "") ?: "") }

    val scriptDir = remember { File(context.getExternalFilesDir(null), "script").apply { mkdirs() } }
    val knownScriptFiles = rememberDirectoryFiles(listOf(scriptDir))

    // SharedPreferences 保存闭包
    val saveScriptMode = { mode: Int ->
        scriptSelectionMode = mode
        prefs.edit().putInt("script_mode", mode).apply()
    }
    val saveDefaultScript = { path: String ->
        selectedDefaultScriptPath = path
        prefs.edit().putString("default_script", path).apply()
    }
    val saveSpecificPath = { path: String ->
        specificScriptPath = path
        prefs.edit().putString("specific_path", path).apply()
    }
    
    var isOk by remember { mutableStateOf((ShizukuManager.getCurrentState() == ShizukuManager.State.READY)) }

    Scaffold(
    topBar = {
            TopAppBar(
                title = { Text("自动操作") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(25.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // --- 按钮区 ---
            Button(
                onClick = { requestPermissions(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("获取权限 (无障碍 + 悬浮窗 + 管理所有文件) (必选)")
            }
            
            Button(
                onClick = {
                    ShizukuManager.init(context)
                    
                        if (ShizukuManager.getCurrentState() == ShizukuManager.State.NO_BINDER) Toast.makeText(context, "未检测到 Shizuku 服务，请先启动应用。", Toast.LENGTH_SHORT).show()
                        
                        if (ShizukuManager.getCurrentState() == ShizukuManager.State.READY) Toast.makeText(context, "已连接", Toast.LENGTH_SHORT).show()
                        
                        isOk = (ShizukuManager.getCurrentState() == ShizukuManager.State.READY)
                        
                    
                        ShizukuManager.onStateChanged = { state ->
                        isOk = (ShizukuManager.getCurrentState() == ShizukuManager.State.READY)
                         //if (state == ShizukuManager.State.READY) Toast.makeText(context, "连接成功！", Toast.LENGTH_SHORT).show()
                         if (state == ShizukuManager.State.DEAD) Toast.makeText(context, "连接超时，请尝试重置应用Shizuku授权", Toast.LENGTH_SHORT).show()
                        
                        }
                    
                        
                        
                 
                  
                    },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Shizuku授权 (用于执行adb命令) (可选)")
            }
            
            if (!isOk) {
        Text(
            text = "请注意: 当前无法使用Shizuku执行相关命令",
            color = Color.Red,
            fontSize = 12.sp
        )
            }

            Button(
                onClick = {
                  

                    val service = MainAccessibilityService.getInstance()
                    if (service == null) {
                        Toast.makeText(context, "服务未开启，请先开启无障碍", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (service.isWindowShown()) {
                        service.hideFloatingWindow()
                        isWindowShown = false
                    } else {
                        DataManager.resetAll()
                        try {
                            if (sourceMode == 1) {
                                DataManager.loadFromFile(selectedKnownMusicPath)
                            } else if (sourceMode == 2) {
                                DataManager.loadFromFile(linkInput)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                        val fileToRun = if (scriptSelectionMode == 0) {
                            File(selectedDefaultScriptPath)
                        } else {
                            File(specificScriptPath)
                        }

                        val fileName = fileToRun.absolutePath

                        if (fileName.isBlank() || fileName == "无") {
                            Toast.makeText(context, "未选择有效脚本文件", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!fileName.lowercase().endsWith(".txt")) {
                            Toast.makeText(context, "脚本文件必须是 .txt 格式", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!fileToRun.exists() && scriptSelectionMode == 1) {
                            Toast.makeText(context, "指定的文件不存在", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        service.showFloatingWindow(fileName)
                        isWindowShown = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isWindowShown) "关闭悬浮窗" else "开启悬浮窗")
            }

            // --- 卡片 1: 选择指令文件 ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("选择指令文件", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    DropdownMenuBox(
                        options = sourceOptions,
                        selectedIndex = sourceMode,
                        onOptionSelected = { sourceMode = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (sourceMode == 1) {
                        Text("已知项：")
                        val displayOptions = if (knownMusicFiles.isEmpty()) listOf("无") else knownMusicFiles.map { File(it).name }
                        
                        // 修复点 1：切换到“已知项”时，若列表不为空且尚未选择任何项，自动选中第 1 项
                        LaunchedEffect(knownMusicFiles, sourceMode) {
                            if (sourceMode == 1 && knownMusicFiles.isNotEmpty() && selectedKnownMusicPath.isBlank()) {
                                selectedKnownMusicPath = knownMusicFiles[0]
                            }
                        }

                        val selectedIndex = displayOptions.indexOf(File(selectedKnownMusicPath).name).takeIf { it >= 0 } ?: 0
                        
                        DropdownMenuBox(
                            options = displayOptions,
                            selectedIndex = selectedIndex,
                            onOptionSelected = { 
                                selectedKnownMusicPath = if (displayOptions[it] == "无") "" else knownMusicFiles[it] 
                            }
                        )
                    } else if (sourceMode == 2) {
                        OutlinedTextField(
                            value = linkInput,
                            onValueChange = { linkInput = it },
                            label = { Text("在此输入链接") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // --- 卡片 2: 自动操作脚本 ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("自动操作脚本", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    // 修复点 2：改为下拉菜单样式
                    DropdownMenuBox(
                        options = scriptSourceOptions,
                        selectedIndex = scriptSelectionMode,
                        onOptionSelected = { saveScriptMode(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (scriptSelectionMode == 0) {
                        val displayOptions = if (knownScriptFiles.isEmpty()) listOf("无") else knownScriptFiles.map { File(it).name }
                        
                        // 初始化默认选中第 1 项
                        LaunchedEffect(knownScriptFiles, scriptSelectionMode) {
                            if (scriptSelectionMode == 0 && knownScriptFiles.isNotEmpty() && selectedDefaultScriptPath.isBlank()) {
                                saveDefaultScript(knownScriptFiles[0])
                            }
                        }

                        val selectedIndex = displayOptions.indexOf(File(selectedDefaultScriptPath).name).takeIf { it >= 0 } ?: 0
                        
                        DropdownMenuBox(
                            options = displayOptions,
                            selectedIndex = selectedIndex,
                            onOptionSelected = { 
                                val path = if (displayOptions[it] == "无") "" else knownScriptFiles[it]
                                saveDefaultScript(path)
                            }
                        )
                    } else {
                        OutlinedTextField(
                            value = specificScriptPath,
                            onValueChange = { saveSpecificPath(it) },
                            label = { Text("在此输入绝对路径 (需含 .txt)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

// --- 辅助：Material 3 下拉菜单封装 ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownMenuBox(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = options.getOrNull(selectedIndex) ?: "无",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption) },
                    onClick = {
                        onOptionSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

// --- 辅助：文件夹变动监听 ---
@Composable
fun rememberDirectoryFiles(directories: List<File>): List<String> {
    var files by remember { mutableStateOf(listOf<String>()) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(directories) {
        val updateFiles = {
            val allFiles = directories.flatMap { dir ->
                dir.listFiles { _, name ->
                    name.lowercase().endsWith(".txt")
                }?.map { it.absolutePath } ?: emptyList()
            }

            coroutineScope.launch(Dispatchers.Main) {
                files = allFiles
            }
        }

        updateFiles()

        val observers = directories.map { dir ->
            @Suppress("DEPRECATION")
            object : FileObserver(
                dir.absolutePath,
                CREATE or DELETE or MOVED_TO or MOVED_FROM
            ) {
                override fun onEvent(event: Int, path: String?) {
                    updateFiles()
                }
            }.apply { startWatching() }
        }

        onDispose {
            observers.forEach { it.stopWatching() }
        }
    }

    return files
}

// --- 辅助：获取权限逻辑 ---
fun requestPermissions(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "存储权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    context.startActivity(accessibilityIntent)
}
