package command.plus

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import android.util.Log
// --- 辅助工具函数 ---

/**
 * 兼容性读取文本：支持绝对路径和 Content URI
 */
suspend fun readTextFromUriOrPath(context: Context, input: String): String = withContext(Dispatchers.IO) {
    if (input.startsWith("content://")) {
        context.contentResolver.openInputStream(Uri.parse(input))?.use { it.bufferedReader().readText() }
            ?: throw Exception("无法打开 URI 内容")
    } else {
        val file = File(input)
        if (!file.exists()) throw Exception("文件不存在: $input")
        file.readText()
    }
}

/**
 * 获取友好的文件名（去除后缀）
 */
fun getSafeFileName(context: Context, pathOrUri: String, defaultName: String): String {
    return try {
        if (pathOrUri.startsWith("content://")) {
            val uri = Uri.parse(pathOrUri)
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }?.substringBeforeLast(".") ?: defaultName
        } else {
            File(pathOrUri).nameWithoutExtension.ifEmpty { defaultName }
        }
    } catch (e: Exception) {
        defaultName
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureGeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    var isGenerating by remember { mutableStateOf(false) }

    // 持久化存储
    val prefs = remember { context.getSharedPreferences("StructureGeneratorPrefs", Context.MODE_PRIVATE) }
    val featurePrefs = remember { context.getSharedPreferences("feature_prefs", Context.MODE_PRIVATE) }

    // 配置项映射
    val isAbsolute_path by rememberPreference("isAbsolute_path", true, featurePrefs)
    var cmdFileName by rememberPreference("cmd_file_name", "未选择命令文件", featurePrefs)
    var gridFileName by rememberPreference("grid_file_name", "未选择阵列文件", featurePrefs)
    var cmdUri by rememberPreference("cmd_uri", "", featurePrefs)
    var gridUri by rememberPreference("grid_uri", "", featurePrefs)

    var configMode by rememberPreference("config_mode", "COMMAND", prefs)
    var outputPath by rememberPreference("output_path", "/sdcard/Download", prefs)
    var outputType by rememberPreference("output_type", "内部存储 (应用私有)", prefs)

    var cmdFilePath by rememberPreference("cmd_file_path", "", prefs)
    var gridFilePath by rememberPreference("grid_file_path", "", prefs)
    
    // 蛇形往复重构开关，默认开启
    var isSerpentine by rememberPreference("is_serpentine", true, prefs)
    var maxHeight by rememberPreference("max_height", "50", prefs) // 默认配合新逻辑限高50
    var zSpacing by rememberPreference("z_spacing", "2", prefs)
    
    var limX by rememberPreference("limX", "64", prefs)
    var limZ by rememberPreference("limZ", "64", prefs)
    var isExportAsPack by rememberPreference("isExportAsPack", false, prefs)
    var rulesJson by rememberPreference("rules_json", "up_:up,down_:down,north_:north,south_:south,west_:west,east_:east", prefs)

    var showResetDialog by remember { mutableStateOf(false) }

    // 文件选择器
    val systemFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { e.printStackTrace() }

            val displayName = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex) else "unknown"
            } ?: "unknown"

            if (configMode == "COMMAND") {
                cmdUri = it.toString()
                cmdFileName = displayName
            } else {
                gridUri = it.toString()
                gridFileName = displayName
            }
        }
    }

    // --- 核心逻辑 ---
    val startGeneration = {
        val currentInput = when {
            isAbsolute_path -> if (configMode == "COMMAND") cmdFilePath else gridFilePath
            else -> if (configMode == "COMMAND") cmdUri else gridUri
        }

        if (currentInput.isBlank()) {
            Toast.makeText(context, "请先选择或输入文件路径", Toast.LENGTH_SHORT).show()
        } else {
            isGenerating = true
            lifecycleOwner.lifecycleScope.launch {
                try {
                    // 1. 决定最基础的存储目标扇区
                    val baseOutputDir = if (outputType.contains("外部")) {
                        File(outputPath).apply { if (!exists()) mkdirs() }
                    } else {
                        File(context.getExternalFilesDir(null), "Structure").apply { if (!exists()) mkdirs() }
                    }

                    val rawFileName = getSafeFileName(context, currentInput, "generated")
                    
                    // 读取文本内容
                    var content = readTextFromUriOrPath(context, currentInput)
                    
                    // 【新拦截逻辑】如果开启了蛇形往复开关，在此对文本内容进行重构注入
                    if (configMode == "COMMAND" && isSerpentine) {
                        val parsedMaxHeight = maxHeight.toIntOrNull() ?: 50
                        val parsedSpacing = zSpacing.toIntOrNull() ?: 2
                        content = enforceVerticalSerpentinePattern(content, parsedMaxHeight, parsedSpacing)
                    }

                    // 2. 为了支持多文件，建立一个独立的临时中转夹
                    val taskTempDir = File(context.cacheDir, "task_${System.currentTimeMillis()}_$rawFileName").apply { mkdirs() }

                    if (configMode == "COMMAND") {
                        // 命令模式：生成单结构文件
                        val singleFile = File(taskTempDir, "$rawFileName.mcstructure")
                        val params = BuildParams(
                            content = content,
                            outputFile = singleFile,
                            limitX = limX.toIntOrNull() ?: 64,
                            limitZ = limZ.toIntOrNull() ?: 64          
                        )
                        withContext(Dispatchers.Default) {
                            CommandStructureBuilder.build(params) { result ->
                                lifecycleOwner.lifecycleScope.launch {
                                    if (result is BuildResult.Success) {
                                        // 交付处理整合流
                                        handleFinalProcessing(context, taskTempDir, baseOutputDir, rawFileName, isExportAsPack)
                                    } else if (result is BuildResult.Error) {
                                        Toast.makeText(context, "❌ ${result.throwable.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isGenerating = false
                                }
                            }
                        }
                    } else {
                        // 文本阵列模式：调用重构后的多文件切片密铺函数
                        withContext(Dispatchers.IO) {
                            val ruleList: List<McStructureExporter.PrefixRule> = rulesJson.split(",")
                                .filter { s -> s.contains(":") }
                                .map { s ->
                                    val parts = s.split(":")
                                    McStructureExporter.PrefixRule(
                                        parts[0], 
                                        mapOf("facing" to parts.getOrElse(1) { "north" })
                                    )
                                }
                                
                            McStructureExporter.importFromGridString(
                                content, 
                                taskTempDir, 
                                ruleList, 
                                limitX = limX.toIntOrNull() ?: 64, 
                                limitZ = limZ.toIntOrNull() ?: 64
                            )
                        }

                        // 交付合并输出流水线
                        handleFinalProcessing(context, taskTempDir, baseOutputDir, rawFileName, isExportAsPack)
                        isGenerating = false
                    }
                } catch (e: Exception) {
                    isGenerating = false
                    Toast.makeText(context, "❌ 运行失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生成结构文件") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { startGeneration() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isGenerating
            ) {
                if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else Text("立即开始生成", fontWeight = FontWeight.Bold)
            }

            InputConfigCard(
                mode = configMode,
                onModeChange = { configMode = it },
                cmdPath = cmdFilePath,
                onCmdPathChange = { cmdFilePath = it },
                gridFilePath = gridFilePath,
                onGridPathChange = { gridFilePath = it },
                cmdFileName = cmdFileName,
                gridFileName = gridFileName,
                cmdUri = cmdUri,
                gridUri = gridUri,
                systemFilePickerLauncher = systemFilePickerLauncher,
                isAbsolute_path = isAbsolute_path,
                isSerpentine = isSerpentine,
                onSerpentineChange = { isSerpentine = it },
                maxHeight = maxHeight,
                onMaxHeightChange = { maxHeight = it },
                zSpacing = zSpacing,
                onZSpacingChange = { zSpacing = it },
                rulesString = rulesJson,
                onRulesChange = { rulesJson = it }
            )
            
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("通用配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = limX, onValueChange = { limX = it },
                            label = { Text("限制长度(x轴)") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = limZ, onValueChange = { limZ = it },
                            label = { Text("限制宽度(z轴)") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }

            OutputConfigCard(
                outputType = outputType,
                outputPath = outputPath,
                onTypeChange = { outputType = it },
                onPathChange = { outputPath = it },
                isExportAsPack = isExportAsPack,
                onIsExportAsPackChange = { isExportAsPack = it }
            )

            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("重置所有配置")
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置配置") },
            text = { Text("确定要清除所有设置吗？这不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().clear().apply()
                    featurePrefs.edit().clear().apply()
                    showResetDialog = false
                    onBack()
                }) { Text("确定", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 集中接管和精简最后的文件输出和压缩外壳分流
 */
suspend fun handleFinalProcessing(
    context: Context,
    taskTempDir: File,
    baseOutputDir: File,
    packName: String,
    shouldPack: Boolean
) {
    withContext(Dispatchers.IO) {
        val structures = taskTempDir.listFiles { _, name -> name.endsWith(".mcstructure") } ?: emptyArray()
        if (structures.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "❌ 未能识别到生成的结构体组件", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        if (shouldPack) {
            try {
                val iconStream = context.resources.openRawResource(R.drawable.noteblock)
                val packGenerator = MCPackGenerator(taskTempDir, iconStream, limitX = 64, limitZ = 64)
                val finalPackFile = packGenerator.generate(context.cacheDir)
                
                if (finalPackFile != null && finalPackFile.exists()) {
                    val finalDestination = File(baseOutputDir, "$packName.mcpack")
                    finalPackFile.copyTo(finalDestination, overwrite = true)
                    finalPackFile.delete()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "📦 密铺行为包一键生成成功: ${finalDestination.name}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    throw Exception("压缩映射流故障")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ 模组打包失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val targetFolder = File(baseOutputDir, packName).apply { mkdirs() }
            structures.forEach { file ->
                file.copyTo(File(targetFolder, file.name), overwrite = true)
            }
            taskTempDir.deleteRecursively()

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "🎉 多切片裸文件密铺导出至子目录: /${packName}/", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun InputConfigCard(
    mode: String, onModeChange: (String) -> Unit,
    cmdPath: String, onCmdPathChange: (String) -> Unit,
    gridFilePath: String, onGridPathChange: (String) -> Unit,
    cmdFileName: String, gridFileName: String,
    cmdUri: String, gridUri: String,
    systemFilePickerLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    isAbsolute_path: Boolean,
    isSerpentine: Boolean, onSerpentineChange: (Boolean) -> Unit,
    maxHeight: String, onMaxHeightChange: (String) -> Unit,
    zSpacing: String, onZSpacingChange: (String) -> Unit,
    rulesString: String, onRulesChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输入配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // 模式切换
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == "COMMAND",
                    onClick = { onModeChange("COMMAND") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("命令文件") }
                SegmentedButton(
                    selected = mode == "GRID",
                    onClick = { onModeChange("GRID") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("文本阵列") }
            }

            // 路径输入/选择
            val labelText = if (mode == "COMMAND") "命令文件 (.txt)" else "文本阵列 (.txt)"
            if (isAbsolute_path) {
                OutlinedTextField(
                    value = if (mode == "COMMAND") cmdPath else gridFilePath,
                    onValueChange = if (mode == "COMMAND") onCmdPathChange else onGridPathChange,
                    label = { Text("输入$labelText 绝对路径") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                )
            } else {
                val currentUri = if (mode == "COMMAND") cmdUri else gridUri
                val currentName = if (mode == "COMMAND") cmdFileName else gridFileName
                
                OutlinedCard(
                    onClick = { systemFilePickerLauncher.launch(arrayOf("text/plain", "application/octet-stream")) },
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已选文件", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = if (currentUri.isEmpty()) "点击选择文件" else currentName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (currentUri.isEmpty()) Color.Gray else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                    }
                }
            }

            // 命令文件模式下的动态配置项（含 Switch 开关控制）
            AnimatedVisibility(visible = mode == "COMMAND") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 新增的上下蛇形往复重构开关 UI
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("优化为上下蛇形往复模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("强制重构编译脚本位置控制指令", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(checked = isSerpentine, onCheckedChange = onSerpentineChange)
                    }

                    // 开关开启时，在下方动态展开原有输入框
                    AnimatedVisibility(visible = isSerpentine) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = maxHeight,
                                onValueChange = onMaxHeightChange,
                                label = { Text("换列高度") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = zSpacing,
                                onValueChange = onZSpacingChange,
                                label = { Text("组别间隔") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = mode == "GRID") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("朝向映射规则", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    
                    val rules = rulesString.split(",").filter { it.isNotEmpty() }
                    rules.forEachIndexed { index, rule ->
                        val parts = rule.split(":")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(parts[0], modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp))
                            Text(parts.getOrElse(1){"north"}, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                val newList = rules.toMutableList().apply { removeAt(index) }
                                onRulesChange(newList.joinToString(","))
                            }) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
                        }
                    }

                    var newPrefix by remember { mutableStateOf("") }
                    var newFace by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextField(value = newPrefix, onValueChange = {newPrefix = it}, placeholder = {Text("前缀")}, modifier = Modifier.weight(1f))
                        TextField(value = newFace, onValueChange = {newFace = it}, placeholder = {Text("朝向")}, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if(newPrefix.isNotBlank()){
                                onRulesChange(if(rulesString.isEmpty()) "$newPrefix:$newFace" else "$rulesString,$newPrefix:$newFace")
                                newPrefix = ""; newFace = ""
                            }
                        }) { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputConfigCard(
    outputType: String,
    outputPath: String,
    onTypeChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    isExportAsPack: Boolean,
    onIsExportAsPackChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("内部存储 (应用私有)", "外部存储 (公共目录)")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输出设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = outputType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("导出目的地") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onTypeChange(option); expanded = false }
                        )
                    }
                }
            }

            if (outputType.contains("外部存储")) {
                OutlinedTextField(
                    value = outputPath,
                    onValueChange = onPathChange,
                    label = { Text("自定义输出目录路径") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("包装为 .mcpack", style = MaterialTheme.typography.bodyLarge)
                    Text("自动生成清单文件并压缩", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Switch(checked = isExportAsPack, onCheckedChange = onIsExportAsPackChange)
            }
        }
    }
}

// --- 动态蛇形重构算法函数 ---

/**
 * 将原始编译器脚本文本强制重构为“上下蛇形往复”模式。
 * 修复支持动态传入高度边界上限以及 X/Z 轴方向隔离移位。
 */
fun enforceVerticalSerpentinePattern(rawContent: String, maxHeight: Int, zSpacing: Int): String {
    val lines = rawContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val sb = StringBuilder()

    val topY = (maxHeight - 1).coerceAtLeast(0)

    sb.append("// ───────────────────────────────────────────────────────\n")
    sb.append("//  由自动重构器生成的上下蛇形往复代码（动态限高 $maxHeight 格，组隔离 $zSpacing 格）\n")
    sb.append("// ───────────────────────────────────────────────────────\n\n")

    sb.append("#state default\n")
    sb.append("#rule Y >= $topY -> face:EAST step:1,0,0 next:0,-1,0\n")
    sb.append("#rule Y <= 0  -> face:EAST step:1,0,0 next:0,1,0\n\n")

    sb.append("#step 0 1 0\n")
    sb.append("#face AUTO\n\n")

    var currentConfigStr = ""
    var hasBlocksInCurrentGroup = false
    var isFirstGroup = true

    for (line in lines) {
        when {
            line.startsWith("$") -> {
                val content = line.substring(1).trim()
                if (content.isEmpty()) {
                    currentConfigStr = ""
                } else {
                    val parts = content.split(",").map { it.trim() }
                    if (parts.size == 3) {
                        val type = parts[0].toIntOrNull() ?: 1
                        
                        if (type == 0 || type == 2) {
                            if (!isFirstGroup && hasBlocksInCurrentGroup) {
                                sb.append("$\n") 
                                sb.append("#step 0 1 0\n")
                                sb.append("#align Z $zSpacing\n\n") // 动态拼装组隔离格数
                                hasBlocksInCurrentGroup = false 
                            }
                            isFirstGroup = false
                        }
                        currentConfigStr = line
                    }
                }
            }

            line.startsWith("/") -> {
                if (currentConfigStr.isNotEmpty()) {
                    sb.append("$currentConfigStr\n")
                    currentConfigStr = "" 
                }
                
                sb.append("$line\n")
                hasBlocksInCurrentGroup = true
            }
            
            else -> {
                // 彻底剥离旧的 #rule, #state, #step, #goto, #shift
            }
        }
    }

    if (hasBlocksInCurrentGroup || currentConfigStr.isEmpty()) {
        sb.append("$\n")
    }
   
    return sb.toString()
}