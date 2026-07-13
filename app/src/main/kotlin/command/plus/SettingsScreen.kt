package command.plus

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.AnnotatedString  // 修复 AnnotatedString 错误
import androidx.compose.ui.text.font.FontFamily  // 修复 FontFamily 错误
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlin.text.Regex
import kotlin.text.RegexOption
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel

import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("feature_prefs", Context.MODE_PRIVATE) }
    
    // 状态绑定：每行显示个数
    var spanCount by remember { mutableIntStateOf(prefs.getInt("span_count", 2)) }
    var isAbsolute_path by rememberPreference("isAbsolute_path", true, prefs)
   var iseplay by rememberPreference("iseplay", true, prefs)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // --- 分段标题 ---
            Text("布局设置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            // --- 设置项：调整每行个数 ---
            Column {
                Text("每行显示块数: $spanCount")
                Slider(
                    value = spanCount.toFloat(),
                    onValueChange = { 
                        spanCount = it.toInt() 
                        prefs.edit().putInt("span_count", it.toInt()).apply()
                    },
                    valueRange = 1f..3f,
                    steps = 1 // 允许 2, 3
                )
            }

            // --- 设置项：跳转调整位置 ---
            Button(
                onClick = {
                    // 核心逻辑：设置一个标记位，告诉主页“请立刻进入编辑模式”
                    prefs.edit().putBoolean("trigger_edit_mode", true).apply()
                    onBack() // 返回主页
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("进入主页调整位置")
            }

            // --- 你的需求：长线及下方文字 ---
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            Text("使用设置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
           Row(
            modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isAbsolute_path,
                onValueChange = {isAbsolute_path = it}
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 根据状态动态改变文字
        Text(
            text = if (isAbsolute_path) "使用绝对路径" else "使用Uri路径",
            style = MaterialTheme.typography.bodyLarge
        )
        
        Switch(
            checked = isAbsolute_path,
            onCheckedChange = null
        )
    }
    
    Row(
            modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = iseplay,
                onValueChange = {iseplay = it}
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 根据状态动态改变文字
        Text(
            text = if (iseplay) "允许后台播放" else "仅软件内播放",
            style = MaterialTheme.typography.bodyLarge
        )
        
        Switch(
            checked = iseplay,
            onCheckedChange = null
        )
    }
    
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            Text("导出及导入配置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            ConfigManagerScreen()
            
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            TutorialDisplay("something.txt")
        }
    }
}

@Composable
fun TutorialDisplay(assetName: String) {
    val context = LocalContext.current
    // 读取文件
    val rawText = remember(assetName) {
        context.assets.open(assetName).bufferedReader().use { it.readText() }
    }
    
    val styledText = parseEnhancedTutorial(rawText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        SelectionContainer {
            Text(text = styledText, lineHeight = 22.sp)
        }
    }
}

@Composable
fun loadTutorialFromAssets(fileName: String): String {
    val context = LocalContext.current
    return remember(fileName) {
        try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "读取教程失败: ${e.message}"
        }
    }
}

@Composable
fun parseEnhancedTutorial(rawText: String): AnnotatedString {
    return buildAnnotatedString {
        // 1. 修复：使用 Kotlin 正确的枚举名 DOT_MATCHES_ALL
        val pattern = Regex("\\[(\\w+)\\](.*?)\\[END\\]", RegexOption.DOT_MATCHES_ALL)
        var lastIndex = 0

        // 只要上面那行对了，底下的 findAll、groups 和 range 都会自动恢复正常
        val matches = pattern.findAll(rawText)

        matches.forEach { result ->
            val tag = result.groups[1]?.value ?: ""
            val content = result.groups[2]?.value?.trim() ?: ""
            
            // 之前的普通文本
            append(rawText.substring(lastIndex, result.range.first))

            when (tag) {
                "TITLE" -> withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))) {
                    append(content + "\n")
                }
                "SECTION" -> withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF455A64))) {
                    append("\n" + content + "\n")
                }
                "WARN" -> withStyle(SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) {
                    append("🔥 注意: $content\n")
                }
                "CODE" -> {
                    if (content.isEmpty()) {
                        withStyle(SpanStyle(color = Color.DarkGray, fontWeight = FontWeight.Bold)) {
                            append("💻 代码示例:\n")
                        }
                    } else {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.LightGray.copy(alpha = 0.3f),
                            color = Color(0xFF006064)
                        )) {
                            append("$content\n")
                        }
                    }
                }
                "MARK" -> withStyle(SpanStyle(color = Color.Blue, fontWeight = FontWeight.Bold)) {
                    append("$content")
                }
                "VAR" -> withStyle(SpanStyle(color = Color(0xFF8E24AA), fontWeight = FontWeight.Medium)) {
                    append("$content")
                }
                "DESC" -> withStyle(SpanStyle(color = Color.Gray, fontSize = 14.sp)) {
                    append("$content\n")
                }
                else -> append(content)
            }
            // 4. 继续使用 result.range.last，这是完全合法的 Kotlin 写法
            lastIndex = result.range.last + 1
        }
        
        if (lastIndex < rawText.length) {
            append(rawText.substring(lastIndex))
        }
    }
}

@Composable
fun ConfigManagerScreen(viewModel: ConfigViewModel = viewModel()) {
    val context = LocalContext.current // 获取当前 Context 用于显示 Toast
    var selectedFile by remember { mutableStateOf("") }

    // 监听 ViewModel 发送过来的提示事件
    LaunchedEffect(Unit) {
        viewModel.importResultEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        uri?.let { viewModel.exportConfig(it, selectedFile) }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importConfig(it, selectedFile) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) { 
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            viewModel.configFiles.forEach { config ->
                ConfigItemRow(
                    name = config.displayName,
                    onExport = {
                        selectedFile = config.prefsName
                        createDocLauncher.launch("${config.prefsName}.xml")
                    },
                    onImport = {
                        selectedFile = config.prefsName // 顺便修复了上一个问题的 Bug
                        openDocLauncher.launch(arrayOf("text/xml"))
                    }
                )
            }
        }
    }
}

@Composable
fun ConfigItemRow(name: String, onExport: () -> Unit, onImport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, modifier = Modifier.weight(1f))
            TextButton(onClick = onImport) { Text("导入") }
            Button(onClick = onExport) { Text("导出") }
        }
    }
}