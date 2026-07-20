package command.plus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

import android.content.ClipData
import android.content.ClipboardManager
// ==========================================
// 核心 Compose 界面
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureConverterScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    val prefs = remember { context.getSharedPreferences("StructureConverterPrefs", Context.MODE_PRIVATE) }

    // --- 状态持久化 (通过 rememberPreference 或者是直接从 SharedPreferences 读写) ---
    var inputFilePath by rememberPreference("input_file_path", "", prefs)
    var outputFilePath by rememberPreference("output_file_path", "", prefs)
    
    // 转换参数配置
    var targetFormatStr by rememberPreference("target_format", StructureFormat.MCSTRUCTURE.name, prefs)
    // 版本号：合并为一个输入
    var targetVersionStr by rememberPreference("target_version", "1.26.30", prefs)
    
    // 映射翻译配置
    var mappingMode by rememberPreference("mapping_mode", "内置资源", prefs)
    var customMappingPath by rememberPreference("custom_mapping_path", "", prefs)

    // UI 交互状态
    var isConverting by remember { mutableStateOf(false) }
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // ==========================================
    // 文件选择器 Launcher 注册
    // ==========================================
    
    // 1. 选择输入结构文件
    val pickInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
        inputFilePath = uri.toString()
    }

    // 2. 选择输出保存文件
    val pickOutputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
        outputFilePath = uri.toString()
    }

    // 3. 选择自定义映射文件
    val pickMappingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
        customMappingPath = uri.toString()
    }

    // 重置所有配置的本地函数
    val resetAll = {
        prefs.edit().clear().apply()
        inputFilePath = ""
        outputFilePath = ""
        targetFormatStr = StructureFormat.MCSTRUCTURE.name
        targetVersionStr = "1.26.30"
        mappingMode = "内置资源"
        customMappingPath = ""
        Toast.makeText(context, "所有设置已被重置", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // 弹窗逻辑
    // ==========================================
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有设置？") },
            text = { Text("这将重置已经设置过的配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetAll()
                        showResetDialog = false
                    }
                ) {
                    Text("确定重置", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("结构文件多端通用转换") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // ==========================================
            // 2. 转换执行按钮区
            // ==========================================
            Button(
                onClick = {
                    if (inputFilePath.isBlank() || outputFilePath.isBlank()) {
                        Toast.makeText(context, "请先配置输入与输出文件路径", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    isConverting = true
                    coroutineScope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                // 解析版本号： 格式如 "1.20.0"
                                val versionParts = targetVersionStr.split(".")
                                val versionMajor = versionParts.getOrNull(0)?.toIntOrNull() ?: 1
                                val versionMinor = versionParts.getOrNull(1)?.toIntOrNull() ?: 20
                                val versionPatch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
                                val gameVersion = Triple(versionMajor, versionMinor, versionPatch)

                                // 1. 根据模式解析加载对应的映射规则 JSON 内容
                                val mappingJson = if (mappingMode == "内置资源") {
                                    context.assets.open("minecraft/block_state_mappings.json").bufferedReader().use { it.readText() }
                                } else {
                                    // 从自定义的 Uri 读取文本内容
                                    val mappingUri = Uri.parse(customMappingPath.trim())
                                    context.contentResolver.openInputStream(mappingUri)?.use { inputStream ->
                                        inputStream.bufferedReader().use { it.readText() }
                                    } ?: error("无法打开自定义映射文件")
                                }

                                if (mappingJson.isBlank()) {
                                    error("映射配置解析为空，请检查文件是否存在或损坏")
                                }

                                val mappingDb = MappingDatabase.fromString(mappingJson)
                                val converter = UniversalStructureConverter(mappingDb)
                                
                                // 2. 处理可能为 Uri 形式的输入/输出路径并转换为底层 File 对象
                                val inputUri = Uri.parse(inputFilePath.trim())
                                val outputUri = Uri.parse(outputFilePath.trim())

                                // 将输入 Uri 安全拷贝到临时沙盒文件
                                val tempInputFile = File(context.cacheDir, "temp_input_structure")
    if (tempInputFile.exists()) tempInputFile.delete()

    val pathOrUri = inputFilePath.trim()

    // 🌟 核心兼容性判断
    if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
        // 方案 A: 如果是标准 URI 格式，用 ContentResolver 读取
        val inputUri = Uri.parse(pathOrUri)
        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            FileOutputStream(tempInputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("无法通过 URI 读取输入文件")
    } else if (pathOrUri.startsWith("/")) {
        // 方案 B: 如果是绝对路径以 / 开头，直接作为物理文件读取
        val sourceFile = File(pathOrUri)
        if (!sourceFile.exists()) {
            error("找不到物理文件，请检查路径是否正确:\n$pathOrUri")
        }
        if (!sourceFile.canRead()) {
            error("没有权限读取该路径，请确保已授予外部存储读取权限或使用文件选择器")
        }
        // 拷贝到沙盒临时文件
        sourceFile.inputStream().use { inputStream ->
            FileOutputStream(tempInputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    } else {
        error("输入的路径格式不正确，既不是绝对路径也不是标准的 content:// 协议")
    }

    // --- 输出路径也需要做同样的兼容处理 ---
    val outputPathOrUri = outputFilePath.trim()
    val tempOutputFile = File(context.cacheDir, "temp_output_structure")
    if (tempOutputFile.exists()) tempOutputFile.delete()

    // 执行底层转换
    val targetFormat = StructureFormat.valueOf(targetFormatStr)
    converter.convert(
        inputFile = tempInputFile,
        outputFile = tempOutputFile,
        targetFormat = targetFormat,
        gameVersion = gameVersion
    )

    // 转换成功后写回目标位置
    if (tempOutputFile.exists()) {
        if (outputPathOrUri.startsWith("content://") || outputPathOrUri.startsWith("file://")) {
            val outputUri = Uri.parse(outputPathOrUri)
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                tempOutputFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: error("无法写入目标输出 URI")
        } else if (outputPathOrUri.startsWith("/")) {
            val targetFile = File(outputPathOrUri)
            // 确保父目录存在
            targetFile.parentFile?.mkdirs()
            tempOutputFile.inputStream().use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } else {
            error("保存路径格式不正确")
        }
        
        // 清理临时文件
        tempInputFile.delete()
        tempOutputFile.delete()
    } else {
        error("转换未生成输出文件")
    }
                            }
                        }
                        
                        isConverting = false
                        result.onSuccess {
                            Toast.makeText(context, "转换成功！", Toast.LENGTH_LONG).show()
                        }.onFailure { e ->
                            Toast.makeText(context, "错误: ${e.localizedMessage ?: "转换失败"}", Toast.LENGTH_LONG).show()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("", "错误: ${e.localizedMessage ?: "转换失败"}")
    clipboard.setPrimaryClip(clip)
                        }
                    }
                },
                enabled = !isConverting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isConverting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在转换中...")
                } else {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始转换结构")
                }
            }

            // ==========================================
            // 3. 输入与输出文件路径配置卡片
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("输入输出配置", style = MaterialTheme.typography.titleMedium)
                    
                    OutlinedTextField(
                        value = inputFilePath,
                        onValueChange = { inputFilePath = it },
                        label = { Text("输入文件 (支持选择 .mcstructure/.litematic/.nbt)") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { 
                                // 限制拉起对应的文件格式或任意文件
                                pickInputLauncher.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Filled.Folder, contentDescription = "选择输入文件")
                            }
                        }
                    )

                    OutlinedTextField(
                        value = outputFilePath,
                        onValueChange = { outputFilePath = it },
                        label = { Text("保存目标路径") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { 
                                pickOutputLauncher.launch("new_structure")
                            }) {
                                Icon(Icons.Filled.Folder, contentDescription = "选择保存路径")
                            }
                        }
                    )
                }
            }

            // ==========================================
            // 4. 目标参数配置卡片
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("参数配置", style = MaterialTheme.typography.titleMedium)

                    // 目标格式选择下拉列表
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { formatDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("目标格式: $targetFormatStr")
                        }
                        DropdownMenu(
                            expanded = formatDropdownExpanded,
                            onDismissRequest = { formatDropdownExpanded = false }
                        ) {
                            StructureFormat.values().forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.name) },
                                    onClick = {
                                        targetFormatStr = format.name
                                        formatDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 合并后的版本号配置输入框
                    OutlinedTextField(
                        value = targetVersionStr,
                        onValueChange = { targetVersionStr = it },
                        label = { Text("目标游戏版本 (例如 1.26.30)") },
                        placeholder = { Text("1.26.30") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ==========================================
            // 5. JAVA/基岩映射翻译配置卡片
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("JAVA-基岩映射配置", style = MaterialTheme.typography.titleMedium)
                    
                    // 单选框组选择内置/外置模式
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (mappingMode == "内置资源"),
                                onClick = { mappingMode = "内置资源" }
                            )
                            Text("内置资源")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (mappingMode == "自定义路径"),
                                onClick = { mappingMode = "自定义路径" }
                            )
                            Text("自定义外部文件")
                        }
                    }

                    if (mappingMode == "自定义路径") {
                        OutlinedTextField(
                            value = customMappingPath,
                            onValueChange = { customMappingPath = it },
                            label = { Text("自定义 JSON 映射文件") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { 
                                    pickMappingLauncher.launch(arrayOf("application/json", "*/*"))
                                }) {
                                    Icon(Icons.Filled.Folder, contentDescription = "选择映射文件")
                                }
                            }
                        )
                    } else {
                        Text(
                            text = "将使用内置 assets 目录中的 block_state_mappings.json 字典翻译映射关系。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ==========================================
            // 6. 底部工具链：触发重置弹窗
            // ==========================================
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("重置所有配置")
            }
        }
    }
}