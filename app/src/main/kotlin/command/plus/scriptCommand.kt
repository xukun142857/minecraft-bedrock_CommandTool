package command.plus

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

private const val PREFS_NAME = "ScriptPrefs"

@Composable
fun ScriptCommandScreen(onBack: () -> Unit) {
    var currentScreen by remember { mutableStateOf("home") }
    var scriptToEdit by remember { mutableStateOf("") }
    var targetSaveFile by remember { mutableStateOf<File?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (currentScreen) {
            "home" -> HomeScreen(
                onBack = onBack,
                onNavigateToEditor = { file, initialText ->
                    targetSaveFile = file
                    scriptToEdit = initialText
                    currentScreen = "editor"
                }
            )
            "editor" -> ScriptEditorScreen(
                initialScriptText = scriptToEdit,
                onBack = { currentScreen = "home" },
                onSaveComplete = { resultText ->
                    targetSaveFile?.let { file ->
                        try {
                            file.parentFile?.mkdirs()
                            file.writeText(resultText)
                        } catch (e: Exception) {
                            // 异常处理
                        }
                    }
                    currentScreen = "home"
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onBack: () -> Unit, onNavigateToEditor: (File, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    
    // --- 目录定义与初始化 ---
    val scriptDir = remember { File(context.getExternalFilesDir(null), "script").apply { mkdirs() } }
    val dir1 = remember { File(context.getExternalFilesDir(null), "MusicOutput").apply { mkdirs() } }
    val dir2 = remember { File(context.getExternalFilesDir(null), "PixelArtResult").apply { mkdirs() } }
    val dir3 = remember { File(context.getExternalFilesDir(null), "McStructure3D").apply { mkdirs() } }
    val knownDirectories = remember { listOf(dir1, dir2, dir3) }

    // 在脚本目录下，初始化一个内置默认脚本文件（若不存在）
    LaunchedEffect(Unit) {
        val defaultScript = File(scriptDir, "默认内置脚本.txt")
        if (!defaultScript.exists()) {
            val defaultContent = """
.if(${'$'}isBlockType == "") // 判断指令输入流程是否结束
.fToast("流程结束") // 发出流程结束提示
.end
.if(${'$'}isBlockType == true) // 判断当前指令区块是否为"聊天栏"
.exeAction(1300,35,1300,35,50) // 打开聊天框(点击"打开聊天框"按钮)
.sleep(300) // 延迟300毫秒
.exeAction(1535,1135,1535,1135,50) // 点击"输入框"
.sleep(50) // 延迟50毫秒
.setText(${'$'}ItemText) // 向输入框写入当前指令项内容
.nextItem() // 推进指令项索引
.exeAction(2500,1100,2505,1105,100) // 点击"发送键"
.sleep(300) // 延迟300毫秒
.exeAction(2500,1100,2505,1105,100) // 再次点击"发送键"(提升稳定性)
.if(${'$'}isNextLoop == true) // 判断当前指令项是否不中断继续自动操作
.reStart() // 将脚本重置,从头开始执行一遍
.end
.end
.if(${'$'}isBlockType == false) // 判断当前指令区块是否为"聊天栏"
.exeAction(1850,560,1850,560,50) // 打开命令方块(点击"使用键")
.sleep(500) // 延迟500毫秒
.if(${'$'}configA > 0) // 当前指令项的指令区块 "方块类型" 为连锁或循环时
.exeAction(840,540,840,540,50) // 点击 "方块类型"
.sleep(100) // 延迟100毫秒
.if(${'$'}configA == 1) // 当前指令项的指令区块 "方块类型" 为连锁时
.exeAction(585,625,585,625,50) // 点击 "连锁"
.else // 当前指令项的指令区块 "方块类型" 为循环时
.exeAction(585,715,585,715,50) // 点击 "循环"
.end
.sleep(100) // 延迟100毫秒
.end
.if(${'$'}configB > 0) // 当前指令项的指令区块 "条件" 为有条件时
.exeAction(840,795,840,795,50) // 点击 "条件"
.sleep(100) // 延迟100毫秒
.exeAction(840,765,840,765,50) // 点击 "有条件"
.sleep(100) // 延迟100毫秒
.end
.if(${'$'}configC > 0) // 当前指令项的指令区块 "红石" 为始终活动时
.exeAction(840,995,840,995,50) // 点击 "红石"
.sleep(100) // 延迟100毫秒
.exeAction(840,780,840,780,50) // 点击 "始终活动"
.sleep(100) // 延迟100毫秒
.end
.exeAction(1470,370,1470,370,50) // 点击 "命令输入框"
.sleep(100) // 延迟100毫秒
.setText(${'$'}ItemText) // 向命令输入框输入当前指令项内容
.nextItem() // 推进指令项索引
.exeAction(2250,160,2250,160,50) // 点击命令方块退出键
.end
            """.trimIndent()
            try { defaultScript.writeText(defaultContent) } catch (e: Exception) {}
        }
    }

    // --- 响应式文件列表监测 ---
    val knownMusicFiles = rememberDirectoryFiles(knownDirectories)
    val knownScriptFiles = rememberDirectoryFiles(listOf(scriptDir))

    // --- 使用扩展函数持久化状态 ---
    var sourceMode by rememberPreference("source_mode", 0, prefs)
    var selectedKnownMusicPath by rememberPreference("selected_known_music_path", "", prefs)
    var linkInput by rememberPreference("link_input", "", prefs)
    
    // --- 文件夹筛选状态 ---
    var selectedFolderFilter by remember { mutableStateOf("ALL") }

    var scriptSelectionMode by rememberPreference("script_mode", 0, prefs)
    var selectedDefaultScriptPath by rememberPreference("default_script", "", prefs)
    var specificScriptPath by rememberPreference("specific_path", "", prefs)

    // --- 新建脚本弹窗控制 ---
    var showCreateDialog by remember { mutableStateOf(false) }
    var newScriptNameInput by remember { mutableStateOf("") }

    if (showCreateDialog) {
        ModalBottomSheet(
            onDismissRequest = { 
                showCreateDialog = false 
                newScriptNameInput = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "新建脚本文件",
                    style = MaterialTheme.typography.titleLarge
                )
                
                OutlinedTextField(
                    value = newScriptNameInput,
                    onValueChange = { newScriptNameInput = it },
                    label = { Text("脚本文件名 (无需填写 .txt)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        if (newScriptNameInput.isBlank()) return@Button
                        val finalName = if (newScriptNameInput.lowercase().endsWith(".txt")) {
                            newScriptNameInput
                        } else {
                            "$newScriptNameInput.txt"
                        }
                        
                        val newScriptFile = File(scriptDir, finalName)
                        showCreateDialog = false
                        newScriptNameInput = ""
                        
                        // 直接打开编辑器编辑新脚本
                        onNavigateToEditor(newScriptFile, "// 在此配置您的自动化逻辑脚本\n")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确认并开始编辑")
                }
            }
        }
    }

    // --- 同步悬浮窗与无障碍服务状态 ---
    var isWindowShown by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val service = MainAccessibilityService.getInstance()
                isWindowShown = service?.isWindowShown() == true
                try {
                    service?.setWindowStateListener { isShown -> isWindowShown = isShown }
                } catch (e: Exception) {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { MainAccessibilityService.getInstance()?.setWindowStateListener(null) } catch (e: Exception) {}
        }
    }

    var isOk by remember { mutableStateOf((ShizukuManager.getCurrentState() == ShizukuManager.State.READY)) }
    val sourceOptions = listOf("无", "从已知中获取", "从链接中获取")
    val scriptSourceOptions = listOf("在默认中选择", "选择指定文件")

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
            // --- 顶部控制权限及常驻按钮 ---
            Button(onClick = { requestPermissions(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("获取权限 (无障碍 + 悬浮窗 + 存储管理)")
            }
            
            Button(
                onClick = {
                    ShizukuManager.init(context)
                    if (ShizukuManager.getCurrentState() == ShizukuManager.State.NO_BINDER) {
                        Toast.makeText(context, "未检测到 Shizuku 服务", Toast.LENGTH_SHORT).show()
                    }
                    isOk = (ShizukuManager.getCurrentState() == ShizukuManager.State.READY)
                    ShizukuManager.onStateChanged = { state ->
                        isOk = (ShizukuManager.getCurrentState() == ShizukuManager.State.READY)
                        if (state == ShizukuManager.State.DEAD) Toast.makeText(context, "连接超时，请检查授权", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Shizuku 授权 (可选)")
            }
            
            if (!isOk) {
                Text(text = "请注意: 当前无法使用 Shizuku 执行相关命令", color = Color.Red, fontSize = 12.sp)
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
                            if (sourceMode == 1) DataManager.loadFromFile(selectedKnownMusicPath)
                            else if (sourceMode == 2) DataManager.loadFromFile(linkInput)
                        } catch (e: Exception) {
                            Toast.makeText(context, "加载指令失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                        val fileToRun = if (scriptSelectionMode == 0) File(selectedDefaultScriptPath) else File(specificScriptPath)
                        val fileName = fileToRun.absolutePath

                        if (fileName.isBlank() || !fileToRun.exists() || !fileName.lowercase().endsWith(".txt")) {
                            Toast.makeText(context, "无效的脚本文件，请先新建或选择有效的脚本", Toast.LENGTH_SHORT).show()
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

            // --- 卡片 1: 选择指令文件 (改进后的多文件夹区分 UI) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("选择指令文件", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownMenuBox(options = sourceOptions, selectedIndex = sourceMode, onOptionSelected = { sourceMode = it })
                    
                    if (sourceMode == 1) {
                        Spacer(modifier = Modifier.height(12.dp))

                        if (knownMusicFiles.isEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "未在对应目录中找到任何 .txt 指令文件",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        } else {
                            val folderNames = remember(knownDirectories) { knownDirectories.map { it.name } }
                            val filteredFiles = remember(knownMusicFiles, selectedFolderFilter) {
                                if (selectedFolderFilter == "ALL") {
                                    knownMusicFiles
                                } else {
                                    knownMusicFiles.filter { File(it).parentFile?.name == selectedFolderFilter }
                                }
                            }

                            // 当筛选改变导致已选文件不在范围内时，自动校准为范围内第一个文件
                            LaunchedEffect(filteredFiles) {
                                if (filteredFiles.isNotEmpty() && !filteredFiles.contains(selectedKnownMusicPath)) {
                                    selectedKnownMusicPath = filteredFiles[0]
                                }
                            }

                            // 1. 文件夹 Filter Chips (支持滑动)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = selectedFolderFilter == "ALL",
                                    onClick = { selectedFolderFilter = "ALL" },
                                    label = { Text("全部 (${knownMusicFiles.size})") },
                                    leadingIcon = if (selectedFolderFilter == "ALL") {
                                        { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )

                                folderNames.forEach { folderName ->
                                    val count = knownMusicFiles.count { File(it).parentFile?.name == folderName }
                                    FilterChip(
                                        selected = selectedFolderFilter == folderName,
                                        onClick = { selectedFolderFilter = folderName },
                                        label = { Text("$folderName ($count)") },
                                        leadingIcon = if (selectedFolderFilter == folderName) {
                                            { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // 2. 带文件夹来源徽章的文件下拉选择器
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            val selectedFileObj = File(selectedKnownMusicPath)
                            val selectedFileName = if (selectedFileObj.exists() && selectedKnownMusicPath.isNotBlank()) selectedFileObj.name else "无选中文件"
                            val selectedFileFolder = if (selectedFileObj.exists()) selectedFileObj.parentFile?.name ?: "" else ""

                            ExposedDropdownMenuBox(
                                expanded = dropdownExpanded,
                                onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedFileName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("已选指令文件") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingIcon = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            if (selectedFileFolder.isNotBlank()) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = selectedFileFolder,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                                        }
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )

                                ExposedDropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    if (filteredFiles.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("该目录下暂无文件", color = MaterialTheme.colorScheme.outline) },
                                            onClick = { dropdownExpanded = false }
                                        )
                                    } else {
                                        filteredFiles.forEach { filePath ->
                                            val file = File(filePath)
                                            val parentFolder = file.parentFile?.name ?: ""
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.InsertDriveFile,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(18.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(
                                                                text = file.name,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                maxLines = 1
                                                            )
                                                        }

                                                        Surface(
                                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier.padding(start = 8.dp)
                                                        ) {
                                                            Text(
                                                                text = parentFolder,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    selectedKnownMusicPath = filePath
                                                    dropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (sourceMode == 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = linkInput,
                            onValueChange = { linkInput = it },
                            label = { Text("输入外部链接") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // --- 卡片 2: 运行脚本选择器 ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("选择当前执行脚本文件", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownMenuBox(options = scriptSourceOptions, selectedIndex = scriptSelectionMode, onOptionSelected = { scriptSelectionMode = it })
                    Spacer(modifier = Modifier.height(8.dp))
                    if (scriptSelectionMode == 0) {
                        val displayOptions = if (knownScriptFiles.isEmpty()) listOf("无脚本") else knownScriptFiles.map { File(it).name }
                        LaunchedEffect(knownScriptFiles) {
                            if (knownScriptFiles.isNotEmpty() && selectedDefaultScriptPath.isBlank()) selectedDefaultScriptPath = knownScriptFiles[0]
                        }
                        val selectedIndex = displayOptions.indexOf(File(selectedDefaultScriptPath).name).takeIf { it >= 0 } ?: 0
                        DropdownMenuBox(options = displayOptions, selectedIndex = selectedIndex, onOptionSelected = { selectedDefaultScriptPath = if (displayOptions[it] == "无脚本") "" else knownScriptFiles[it] })
                    } else {
                        OutlinedTextField(value = specificScriptPath, onValueChange = { specificScriptPath = it }, label = { Text("输入绝对路径 (.txt)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }

            // --- 卡片 3: 脚本管理器 (直接对 scriptDir 目录中的脚本文件进行操作) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("脚本文件管理器", style = MaterialTheme.typography.titleMedium)
                    
                    val scriptDisplayOptions = if (knownScriptFiles.isEmpty()) listOf("暂无脚本") else knownScriptFiles.map { File(it).name }
                    
                    // 当已知文件变化时校准持久化选择
                    LaunchedEffect(knownScriptFiles) {
                        if (knownScriptFiles.isNotEmpty() && selectedDefaultScriptPath.isBlank()) {
                            selectedDefaultScriptPath = knownScriptFiles[0]
                        }
                    }
                    val selectedScriptIndex = scriptDisplayOptions.indexOf(File(selectedDefaultScriptPath).name).takeIf { it >= 0 } ?: 0
                    
                    // 下拉选择脚本
                    DropdownMenuBox(
                        options = scriptDisplayOptions,
                        selectedIndex = selectedScriptIndex,
                        onOptionSelected = {
                            selectedDefaultScriptPath = if (scriptDisplayOptions[it] == "暂无脚本") "" else knownScriptFiles[it]
                        }
                    )

                    // 脚本修改与删除操作按钮
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val file = File(selectedDefaultScriptPath)
                                if (file.exists()) {
                                    onNavigateToEditor(file, file.readText())
                                } else {
                                    Toast.makeText(context, "请先选择一个有效脚本", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("修改选中脚本", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val file = File(selectedDefaultScriptPath)
                                if (file.exists()) {
                                    file.delete()
                                    selectedDefaultScriptPath = ""
                                    Toast.makeText(context, "脚本已删除", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "请先选择需要删除的脚本", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("删除脚本", fontSize = 12.sp)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                    // 新建脚本按钮
                    OutlinedButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("＋ 新建空白脚本文件")
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
