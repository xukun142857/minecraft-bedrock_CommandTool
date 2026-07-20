package command.plus

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import kotlin.coroutines.resume
import androidx.compose.animation.*
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.MusicNote
import android.content.Intent

import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
// --- 数据模型 ---
data class InstrumentDef(
    var channels: List<Int>,
    var events: List<Int>,
    var name: String,
    var basePitch: Int,
    var isDrum: Boolean
)

data class InstrumentItem(
    val id: Long = System.nanoTime(),
    val def: InstrumentDef
)

data class MidiFileItem(
    val id: Long = System.nanoTime(),
    val path: String,
    val name: String,
    val sizeStr: String,
    val isConverted: Boolean = false,
    val wavPath: String? = null,
    val txtPath: String? = null,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val generationDetails: String? = null
)

@Composable
fun musicCommand(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        MusicScreen(onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(onBack: () -> Unit) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val sp = remember { context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE) }
    val setprefs = remember { context.getSharedPreferences("feature_prefs", Context.MODE_PRIVATE) }
    var showResetDialog by remember { mutableStateOf(false) }
    val isAbsolute_path by rememberPreference("isAbsolute_path", true, setprefs)
    val iseplay by rememberPreference("iseplay", true, setprefs)
    
    // --- 状态管理 ---
    var fileList by remember { mutableStateOf(loadFileList(sp)) }
    var manualPaths by remember { mutableStateOf(sp.getString("manualPaths", "") ?: "") }
    var showFilePicker by remember { mutableStateOf(false) }
    var expandManualInput by remember { mutableStateOf(false) }
    
    var outType by remember { mutableStateOf(sp.getInt("outType", 0)) }
    var mode by remember { mutableStateOf(sp.getInt("mode", 0)) }
    var extPath by remember { mutableStateOf(sp.getString("extPath", "output") ?: "output") }
    var stackEnabled by remember { mutableStateOf(sp.getBoolean("stack", false)) }
    var enableExtraSensitiveOptimization by remember { mutableStateOf(sp.getBoolean("enableExtraSensitiveOptimization", true)) }
    var params by remember {
        mutableStateOf(List(9) { i -> sp.getString("p${i + 1}", getDefaultParam(i)) ?: getDefaultParam(i) })
    }
    
    val channelsTextMap = remember { mutableStateMapOf<Long, String>() }
    val eventsTextMap = remember { mutableStateMapOf<Long, String>() }
    var instrumentList by remember { mutableStateOf(loadInstrumentMap(sp)) }
    
    val systemFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val addedList = mutableListOf<MidiFileItem>()
                val contentResolver = context.contentResolver

                uris.forEach { uri ->
                    try {
                        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }
                    val pathString = uri.toString()

                    if (fileList.none { it.path == pathString }) {
                        var displayName = "未知文件"
                        var sizeLong = 0L
                        
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst()) {
                                if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                                if (sizeIndex != -1) sizeLong = cursor.getLong(sizeIndex)
                            }
                        }

                        if (displayName.endsWith(".mid", ignoreCase = true) || 
                            displayName.endsWith(".midi", ignoreCase = true)) {
                            
                            addedList.add(
                                MidiFileItem(
                                    path = pathString,
                                    name = displayName,
                                    sizeStr = formatFileSize(sizeLong)
                                )
                            )
                        }
                    }
                }

                if (addedList.isNotEmpty()) {
                    fileList = fileList + addedList
                }
            }
        }
    )

    LaunchedEffect(fileList, manualPaths, outType, mode, extPath, stackEnabled, params, instrumentList, enableExtraSensitiveOptimization) {
        delay(300) 
        sp.edit().apply {
            putString("manualPaths", manualPaths)
            putInt("outType", outType)
            putInt("mode", mode)
            putString("extPath", extPath)
            putBoolean("stack", stackEnabled)
            params.forEachIndexed { i, value -> putString("p${i + 1}", value) }
            putBoolean("enableExtraSensitiveOptimization", enableExtraSensitiveOptimization)
            apply()
        }
        saveInstrumentMap(sp, instrumentList)
        saveFileList(sp, fileList)
    }

    // ================== 🔴 后台播放服务状态同步 🔴 ==================
    var playbackService by remember { mutableStateOf<MusicPlaybackService?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var totalDuration by remember { mutableStateOf(0) }
    var wavName by remember { mutableStateOf("本地 WAV") }
    var isUserSeeking by remember { mutableStateOf(false) }
    
    var isGenerating by remember { mutableStateOf(false) }
    var batchProgressText by remember { mutableStateOf("") }
    var slashCount by remember { mutableStateOf(0) }
    var tsummary by remember { mutableStateOf("") }
    var selectedPreviewIndex by remember { mutableStateOf(-1) }

    // 监听 Service 的绑定与状态桥接
    DisposableEffect(lifecycleOwner) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MusicPlaybackService.LocalBinder
                val s = binder.getService()
                playbackService = s
                
                // 初步对齐状态
                isPlaying = s.isPlaying()
                currentPosition = s.getCurrentPosition()
                totalDuration = s.getDuration()
                val currentTitle = s.getCurrentTitle()
                if (currentTitle.isNotEmpty()) wavName = currentTitle

                s.onStateChange = { isPlaying = it }
                s.onPositionUpdate = { pos -> 
                    if (!isUserSeeking) currentPosition = pos 
                }
                s.onPrepared = { duration -> totalDuration = duration }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService = null
            }
        }

        val intent = Intent(context, MusicPlaybackService::class.java)
        context.startService(intent) // 保证服务在生命周期内常驻
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // 🟢 核心适配：当应用不可见（退到后台/锁屏）时，判断 iseplay 决定是否暂停
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!iseplay) {
                    playbackService?.pausePlay()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            context.unbindService(connection)
            lifecycleOwner.lifecycle.removeObserver(observer)
            playbackService?.apply {
                onStateChange = null
                onPositionUpdate = null
                onPrepared = null
            }
        }
    }

    val saveAllPrefs = {
        sp.edit().apply {
            putString("manualPaths", manualPaths)
            putInt("outType", outType)
            putInt("mode", mode)
            putString("extPath", extPath)
            putBoolean("stack", stackEnabled)
            putBoolean("enableExtraSensitiveOptimization", enableExtraSensitiveOptimization)
            params.forEachIndexed { i, value -> putString("p${i + 1}", value) }
            apply()
        }
        saveInstrumentMap(sp, instrumentList)
        saveFileList(sp, fileList)
    }

    val pickWavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val name = queryName(context, it) ?: "temp.wav"
                if (!name.endsWith(".wav", true)) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "仅限 WAV 文件", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val targetDir = File(context.getExternalFilesDir(null), "note")
                targetDir.mkdirs()
                val targetFile = File(targetDir, name)
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
    
    if (showFilePicker) {
        FilePickerDialog(
            allowedExtensions = listOf("midi", "mid"),
            isMultiSelect = true,
            onDismiss = { showFilePicker = false },
            onFilesSelected = { files ->
                val addedList = mutableListOf<MidiFileItem>()
                files.forEach { f ->
                    if (fileList.none { it.path == f.absolutePath }) {
                        addedList.add(
                            MidiFileItem(
                                path = f.absolutePath,
                                name = f.name,
                                sizeStr = formatFileSize(f.length())
                            )
                        )
                    }
                }
                if (addedList.isNotEmpty()) {
                    fileList = fileList + addedList
                }
                showFilePicker = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指令音符盒") },
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
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // 批量生成按钮
            Button(
                onClick = {
                    saveAllPrefs()
                    if (fileList.isEmpty()) {
                        Toast.makeText(context, "请先添加MIDI文件", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    isGenerating = true
                    coroutineScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        var failCount = 0
                        val outFolder = if (outType == 1) File(extPath) else File(context.getExternalFilesDir(null), "MusicOutput")
                        outFolder.mkdirs()

                        val newList = fileList.toMutableList()

                        for (i in newList.indices) {
                            val item = newList[i]
                            batchProgressText = "(${i + 1}/${newList.size})"
                            
                            var fileExistsAndAccessible = false
                            var inputMidi: File? = null
                            
                            if (item.path.startsWith("content://")) {
                                try {
                                    context.contentResolver.openInputStream(Uri.parse(item.path))?.use {
                                        fileExistsAndAccessible = true
                                    }
                                } catch (e: Exception) {
                                    fileExistsAndAccessible = false
                                }
                            } else {
                                inputMidi = File(item.path)
                                fileExistsAndAccessible = inputMidi.exists()
                            }
                            
                            if (!fileExistsAndAccessible) {
                                failCount++
                                newList[i] = item.copy(
                                    isFailed = true,
                                    errorMessage = "文件不存在或无访问权限"
                                )
                                continue
                            }

                            try {
                                val converter = McMusicConverter.Builder()
                                    .apply {
                                        if (item.path.startsWith("content://")) setMidiStream(context.contentResolver.openInputStream(Uri.parse(item.path))) else setMidiFile(inputMidi!!)
                                    }
                                    .setSampleFolder(File(context.getExternalFilesDir(null), "note"))
                                    .setOutputWav(File(outFolder, "${item.name.substringBeforeLast(".")}.wav"))
                                    .setOutputTxt(File(outFolder, "${item.name.substringBeforeLast(".")}.txt"))
                                    .setMcTick(params[0].toDoubleOrNull() ?: 0.05)
                                    .setMaxSimulNotes(params[1].toIntOrNull() ?: 5)
                                    .setPitchPrecision(params[2].toIntOrNull() ?: 4)
                                    .setGlobalPitchFactor(params[3].toDoubleOrNull() ?: 1.0)
                                    .setSimplificationLevel(params[4].toIntOrNull() ?: 0)
                                    .setVolumeMode(mode)
                                    .setInstrumentMappings(
                                        instrumentList.map {
                                            McMusicConverter.InstrumentDef(
                                                it.def.channels, it.def.events, it.def.name,
                                                it.def.basePitch, it.def.isDrum
                                            )
                                        }.toMutableList()
                                    )
                                    .setEnableStacking(stackEnabled)
                                    .setScoreboardName(params[5] ?: "t")
                                    .setStartOffset(params[7].toIntOrNull() ?: 0)
                                    .setMaxChars(params[6].toIntOrNull() ?: 10000)
                                    .setForBiddenScores(java.util.HashSet(parseIntList(params[8])))
                                    .build()

                                val result = suspendCancellableCoroutine<Triple<File?, File?, String?>> { cont ->
    converter.convertAndRender(object : McMusicConverter.Callback {
        override fun onProgress(msg: String?, p: Int) {}
        override fun onComplete(wav: File, txt: File, summary: String) {
            if (cont.isActive) cont.resume(Triple(wav, txt, summary))
        }
        override fun onError(e: Exception) {
            if (cont.isActive) cont.resume(Triple(null, null, null))
        }
    })
}

if (result.first != null && result.second != null) {
    successCount++
    newList[i] = item.copy(
        isConverted = true,
        isFailed = false,
        errorMessage = null,
        wavPath = result.first!!.absolutePath,
        txtPath = result.second!!.absolutePath,
        generationDetails = result.third ?: "未统计"
    )
} else {
    failCount++
    newList[i] = item.copy(
        isFailed = true,
        errorMessage = "转换失败"
    )
}
                            } catch (e: Exception) {
                                failCount++
                                newList[i] = item.copy(
                                    isFailed = true,
                                    errorMessage = "转换异常: ${e.message}"
                                )
                            }
                        }

                        withContext(Dispatchers.Main) {
                            fileList = newList
                            isGenerating = false
                            batchProgressText = ""
                            Toast.makeText(context, "完成! 成功: $successCount, 失败: $failCount", Toast.LENGTH_LONG).show()

                            val firstConvertedIndex = newList.indexOfFirst { it.isConverted }
                            if (firstConvertedIndex != -1) {
                                selectedPreviewIndex = firstConvertedIndex
                                val wavFile = File(newList[firstConvertedIndex].wavPath!!)
                                wavName = wavFile.name
                                slashCount = countSlashesInFile(File(newList[firstConvertedIndex].txtPath!!))
                                tsummary = newList[firstConvertedIndex].generationDetails ?: "未统计"

                                playbackService?.loadFile(wavFile.absolutePath, wavName)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating
            ) {
                Text(if (isGenerating) "处理中... $batchProgressText" else "开始生成", fontSize = 16.sp)
            }

            // 试听卡片
            ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.noteblock),
                            contentDescription = "Album Art",
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray)
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text("Wav音频试听", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(wavName, fontSize = 14.sp, color = Color.Gray)
                            if (slashCount > 0) {
                                Text("指令数量: $slashCount", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        FilledIconButton(
                            onClick = { playbackService?.togglePlayPause() },
                            enabled = playbackService != null
                        ) {
                            Icon(
                                painter = painterResource(id = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                                contentDescription = "Play/Pause"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(currentPosition), fontSize = 12.sp)
                        Slider(
                            value = if (totalDuration > 0) currentPosition.toFloat() else 0f,
                            onValueChange = {
                                isUserSeeking = true
                                currentPosition = it.toInt()
                            },
                            onValueChangeFinished = {
                                isUserSeeking = false
                                playbackService?.seekTo(currentPosition)
                            },
                            valueRange = 0f..(totalDuration.toFloat().takeIf { it > 0f } ?: 1f),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(formatTime(totalDuration), fontSize = 12.sp)
                    }
                }
            }

            // 输入文件列表
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("输入文件列表", style = MaterialTheme.typography.titleMedium)
                    
                    Button(
                        onClick = { 
                            if (isAbsolute_path) {
                                showFilePicker = true 
                            } else {
                                systemFilePickerLauncher.launch(
                                    arrayOf(
                                        "audio/midi", "audio/mid", "audio/x-midi",
                                        "application/mid", "application/midi"
                                    )
                                )
                            }
                        }, 
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开文件选择器 (仅支持 .mid/.midi)")
                    }
                    
                    if (fileList.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            itemsIndexed(
                                items = fileList,
                                key = { _, item -> item.id } 
                            ) { index, item ->
                                val isSelected = selectedPreviewIndex == index
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = tween(durationMillis = 300),
                                            placementSpec = spring(stiffness = Spring.StiffnessLow)
                                        )
                                        .clickable {
                                            if (item.isConverted && item.wavPath != null) {
                                                selectedPreviewIndex = index
                                                wavName = File(item.wavPath).name
                                                slashCount = countSlashesInFile(File(item.txtPath!!))
                                                tsummary = item.generationDetails ?: "未统计"
                                                // 🟢 彻底废除本地 mediaPlayer，一律改由统一服务进行换歌播放
                                                playbackService?.loadFile(item.wavPath, wavName)
                                                
                                            } else if (item.isFailed) {
                                                Toast.makeText(context, "转换失败: ${item.errorMessage}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "该文件尚未转换", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            item.isFailed -> Color(0xFFFFCDD2)
                                            item.isConverted -> MaterialTheme.colorScheme.surfaceContainerHigh
                                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null, 
                                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                                            tint = if (item.isFailed) Color.Red else LocalContentColor.current
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.name, 
                                                fontSize = 14.sp, 
                                                fontWeight = FontWeight.Bold, 
                                                maxLines = 1,
                                                color = if (item.isFailed) Color.Red else Color.Unspecified
                                            )
                                            Text(
                                                when {
                                                    item.isFailed -> "失败: ${item.errorMessage}"
                                                    item.isConverted -> "${item.sizeStr} | 已转换"
                                                    else -> "${item.sizeStr} | 未转换"
                                                },
                                                fontSize = 12.sp,
                                                color = when {
                                                    item.isFailed -> Color.Red
                                                    item.isConverted -> Color(0xFF4CAF50)
                                                    else -> Color.Gray
                                                }
                                            )
                                        }
                                        IconButton(onClick = {
                                            fileList = fileList.filterIndexed { i, _ -> i != index }
                                            if (selectedPreviewIndex == index) {
                                                selectedPreviewIndex = -1
                                            } else if (selectedPreviewIndex > index) {
                                                selectedPreviewIndex--
                                            }
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("列表为空，请选择或输入 MIDI 文件", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                    }

                    if (isAbsolute_path) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandManualInput = !expandManualInput }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("手动输入文件路径", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Icon(
                                imageVector = if (expandManualInput) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expandManualInput) "收起" else "展开",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(
                            visible = expandManualInput,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = manualPaths,
                                    onValueChange = { manualPaths = it },
                                    label = { Text("每行输入一个绝对路径") },
                                    modifier = Modifier.fillMaxWidth().height(150.dp),
                                    maxLines = 10
                                )
                                Button(
                                    onClick = {
                                        val newPaths = manualPaths.split("\n")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() && (it.endsWith(".mid", true) || it.endsWith(".midi", true)) }
                                        
                                        val addedList = mutableListOf<MidiFileItem>()
                                        newPaths.forEach { path ->
                                            if (fileList.none { it.path == path }) {
                                                val f = File(path)
                                                addedList.add(
                                                    MidiFileItem(
                                                        path = path,
                                                        name = f.name,
                                                        sizeStr = formatFileSize(f.length())
                                                    )
                                                )
                                            }
                                        }
                                        if (addedList.isNotEmpty()) {
                                            fileList = fileList + addedList
                                            Toast.makeText(context, "成功添加了 ${addedList.size} 个新文件", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "没有找到符合要求且未重复的新文件", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("解析并添加到列表")
                                }
                            }
                        }
                    }
                }
            }
            
            TerminalCard(
                title = "生成结果",
                content = tsummary
            )

            // 音频替换工具卡片
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加或覆盖wav音效", style = MaterialTheme.typography.titleMedium)
                    OutlinedCard(onClick = { pickWavLauncher.launch(arrayOf("audio/*")) }) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("(也可直接在 /Android/data/command.plus/files/note/ 下修改)", fontSize = 12.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { pickWavLauncher.launch(arrayOf("audio/*")) }) {
                                Text("选择外部音频文件")
                            }
                        }
                    }
                }
            }

            // 文件输出位置
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("文件输出位置", style = MaterialTheme.typography.titleMedium)
                    SimpleDropdown(
                        label = "输出类型",
                        items = listOf("内部路径", "外部路径"),
                        selectedIndex = outType,
                        onItemSelected = { outType = it }
                    )
                    if (outType == 1) {
                        OutlinedTextField(
                            value = extPath,
                            onValueChange = { extPath = it },
                            label = { Text("外部路径 (请输入一个输出的目录)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 参数配置
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("参数配置", style = MaterialTheme.typography.titleMedium)
                    
                    val paramLabels = listOf(
                        "游戏中一刻代表多少秒 (默认0.05)", "同刻最大连续量 (默认5)", 
                        "精度位数 (默认4)", "音调系数 (默认1,推荐0.5)", 
                        "音乐简化等级 (默认0,不超过5)"
                    )
                    
                    paramLabels.forEachIndexed { index, label ->
                        OutlinedTextField(
                            value = params[index],
                            onValueChange = { newText -> 
                                params = params.toMutableList().apply { this[index] = newText }
                            },
                            label = { Text(label) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SimpleDropdown(
                        label = "音量模式",
                        items = listOf("模拟音量", "固定音量"),
                        selectedIndex = mode,
                        onItemSelected = { mode = it }
                    )
                }
            }

            // 指令配置
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("指令配置", style = MaterialTheme.typography.titleMedium)
                    
                    val cmdLabels = listOf("计分板名称 (默认t)", "单条字符上限 (默认10000)", "音乐相对起始点 (默认0)", "违禁数值 (用逗号分隔)")
                    cmdLabels.forEachIndexed { i, label ->
                        val actualIndex = i + 5
                        OutlinedTextField(
                            value = params[actualIndex],
                            onValueChange = { newText ->
                                params = params.toMutableList().apply { this[actualIndex] = newText }
                            },
                            label = { Text(label) },
                            keyboardOptions = if (i == 0 || i == 3) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Number),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("是否保留同刻重复音", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = stackEnabled, onCheckedChange = { stackEnabled = it })
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("是否启用网易违禁词规避", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = enableExtraSensitiveOptimization, onCheckedChange = { enableExtraSensitiveOptimization = it })
                    }
                }
            }

            // 乐器及其参数配置
            ElevatedCard(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("乐器及其参数", style = MaterialTheme.typography.titleMedium)

                    LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                        items(
                            items = instrumentList,
                            key = { it.id }
                        ) { item ->
                            Box(modifier = Modifier.animateItem()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = item.def.name,
                                                onValueChange = { newName ->
                                                    instrumentList = instrumentList.map {
                                                        if (it.id == item.id) it.copy(
                                                            def = it.def.copy(name = newName)
                                                        ) else it
                                                    }
                                                },
                                                label = { Text("name") },
                                                modifier = Modifier.weight(1f)
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            OutlinedTextField(
                                                value = item.def.basePitch.toString(),
                                                onValueChange = { newV ->
                                                    val vInt = newV.toIntOrNull() ?: 0
                                                    instrumentList = instrumentList.map {
                                                        if (it.id == item.id) it.copy(
                                                            def = it.def.copy(basePitch = vInt)
                                                        ) else it
                                                    }
                                                },
                                                label = { Text("basePitch") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.width(110.dp)
                                            )

                                            IconButton(onClick = {
                                                instrumentList = instrumentList.filter { it.id != item.id }
                                            }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("isDrum", modifier = Modifier.width(72.dp))
                                            Switch(
                                                checked = item.def.isDrum,
                                                onCheckedChange = { checked ->
                                                    instrumentList = instrumentList.map {
                                                        if (it.id == item.id) it.copy(
                                                            def = it.def.copy(isDrum = checked)
                                                        ) else it
                                                    }
                                                }
                                            )
                                        }

                                        OutlinedTextField(
                                            value = channelsTextMap[item.id] ?: intListToText(item.def.channels),
                                            onValueChange = { text ->
                                                channelsTextMap[item.id] = text
                                                val newList = parseIntList(text)
                                                instrumentList = instrumentList.map {
                                                    if (it.id == item.id) it.copy(
                                                        def = it.def.copy(channels = newList)
                                                    ) else it
                                                }
                                            },
                                            label = { Text("channels（用逗号分隔）") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = eventsTextMap[item.id] ?: intListToText(item.def.events),
                                            onValueChange = { text ->
                                                eventsTextMap[item.id] = text
                                                val newList = parseIntList(text)
                                                instrumentList = instrumentList.map {
                                                    if (it.id == item.id) it.copy(
                                                        def = it.def.copy(events = newList)
                                                    ) else it
                                                }
                                            },
                                            label = { Text("events（用逗号分隔）") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(onClick = {
                        instrumentList = instrumentList + InstrumentItem(
                            def = InstrumentDef(
                                channels = emptyList(),
                                events = emptyList(),
                                name = "new_inst",
                                basePitch = 0,
                                isDrum = false
                            )
                        )
                    }) {
                        Text("添加映射")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重置所有配置")
                    }
                }
            }
        }
        
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("重置所有设置？") },
                text = { Text("这将重置所有文本框及映射。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            fileList = emptyList()
                            manualPaths = ""
                            params = List(9) { i -> getDefaultParam(i) }
                            instrumentList = defaultInstrumentMap().map { InstrumentItem(def = it) }
                            stackEnabled = false
                            enableExtraSensitiveOptimization = true
                            outType = 0
                            mode = 0
                            extPath = "output"
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
    }
}

fun countSlashesInFile(file: File): Int {
    return try {
        file.readText().count { it == '/' }
    } catch (e: Exception) {
        0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleDropdown(label: String, items: List<String>, selectedIndex: Int, onItemSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = items.getOrElse(selectedIndex) { "" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEachIndexed { index, text ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onItemSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun getDefaultParam(i: Int) = when(i) {
    0 -> "0.05"; 1 -> "5"; 2 -> "4"; 3 -> "1"; 4 -> "0"; 5 -> "t"; 6 -> "10000"; 7 -> "0"; 8 -> "6489,8964" ; else -> ""
}

private fun queryName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
}

private fun formatTime(ms: Int): String {
    val s = ms / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)
}

private fun loadFileList(sp: SharedPreferences): List<MidiFileItem> {
    val json = sp.getString("file_list_info", null) ?: return emptyList()
    val list = mutableListOf<MidiFileItem>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                MidiFileItem(
                    id = obj.optLong("id", System.nanoTime()),
                    path = obj.optString("path", ""),
                    name = obj.optString("name", ""),
                    sizeStr = obj.optString("sizeStr", ""),
                    isConverted = obj.optBoolean("isConverted", false),
                    wavPath = obj.optString("wavPath", "").takeIf { it.isNotEmpty() },
                    txtPath = obj.optString("txtPath", "").takeIf { it.isNotEmpty() },
                    isFailed = obj.optBoolean("isFailed", false),
                    errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotEmpty() },
                    generationDetails = obj.optString("generationDetails", "").takeIf { it.isNotEmpty() }
                )
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

private fun saveFileList(sp: SharedPreferences, list: List<MidiFileItem>) {
    try {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("path", item.path)
                put("name", item.name)
                put("sizeStr", item.sizeStr)
                put("isConverted", item.isConverted)
                put("wavPath", item.wavPath ?: "")
                put("txtPath", item.txtPath ?: "")
                put("isFailed", item.isFailed)
                put("errorMessage", item.errorMessage ?: null)
                put("generationDetails", item.generationDetails ?: "")
            }
            arr.put(obj)
        }
        sp.edit().putString("file_list_info", arr.toString()).apply()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun loadInstrumentMap(sp: SharedPreferences): List<InstrumentItem> {
    val json = sp.getString("map_sinfo", null) ?: return defaultInstrumentMap().map { InstrumentItem(def = it) }
    val list = mutableListOf<InstrumentItem>()

    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)

            val channels = mutableListOf<Int>()
            val chArr = obj.optJSONArray("channels")
            if (chArr != null) {
                for (j in 0 until chArr.length()) channels.add(chArr.optInt(j))
            }

            val events = mutableListOf<Int>()
            val evArr = obj.optJSONArray("events")
            if (evArr != null) {
                for (j in 0 until evArr.length()) events.add(evArr.optInt(j))
            }

            list.add(
                InstrumentItem(
                    def = InstrumentDef(
                        channels = channels,
                        events = events,
                        name = obj.optString("name", ""),
                        basePitch = obj.optInt("basePitch", 0),
                        isDrum = obj.optBoolean("isDrum", false)
                    )
                )
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return if (list.isEmpty()) defaultInstrumentMap().map { InstrumentItem(def = it) } else list
}

private fun saveInstrumentMap(sp: SharedPreferences, list: List<InstrumentItem>) {
    try {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("name", item.def.name)
                put("basePitch", item.def.basePitch)
                put("isDrum", item.def.isDrum)

                val chArr = JSONArray()
                item.def.channels.forEach { chArr.put(it) }
                put("channels", chArr)

                val evArr = JSONArray()
                item.def.events.forEach { evArr.put(it) }
                put("events", evArr)
            }
            arr.put(obj)
        }
        sp.edit().putString("map_sinfo", arr.toString()).apply()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun parseIntList(text: String): List<Int> {
    return text
        .split(',', '，', ' ', ';', '|')
        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
}

private fun intListToText(list: List<Int>): String = list.joinToString(",")

private fun defaultInstrumentMap(): List<InstrumentDef> {
    return listOf(
    // ==========================================
    // 1. 旋律乐器映射 (Melody Channels)
    // ==========================================
    // 钢琴 (0-7), 弦乐合奏 (40-47), 兜底音效 (120-127) -> harp
    InstrumentDef(listOf(), (0..7).toList() + (40..47).toList() + (120..127).toList(), "note.harp", 54, false),
    // 敲击乐器 (8-15), 效果音 (96-103) -> pling (清脆八音盒效果)
    InstrumentDef(listOf(), (8..15).toList() + (96..103).toList(), "note.pling", 78, false),
    // 风琴 (16-23), 铜管与木管乐器 (64-79) -> flute
    InstrumentDef(listOf(), (16..23).toList() + (64..79).toList(), "note.flute", 66, false),
    // 各类吉他 (24-31) -> guitar
    InstrumentDef(listOf(), (24..31).toList(), "note.guitar", 42, false),
    // 贝斯 (32-34), 拨弦贝斯 (36-37), 合成贝斯 (39) -> bass
    InstrumentDef(listOf(), (32..34).toList() + listOf(36, 37, 39), "note.bass", 30, false),
    // 独奏弦乐/高音域 (48-55) -> chime
    InstrumentDef(listOf(), (48..55).toList(), "note.chime", 78, false),
    // 铜管乐器/小号 (56-63) -> bell
    InstrumentDef(listOf(), (56..63).toList(), "note.bell", 78, false),
    // 合成主音 Lead (80-87) -> bit (8Bit电子音色，表现力强)
    InstrumentDef(listOf(), (80..87).toList(), "note.bit", 54, false),
    // 合成垫音 Pad (88-95) -> iron_xylophone
    InstrumentDef(listOf(), (88..95).toList(), "note.iron_xylophone", 54, false),
    // 班卓琴 (105) -> banjo
    InstrumentDef(listOf(), listOf(105), "note.banjo", 54, false),
    // 低音氛围/民族低音 (35, 38, 104-111除105) -> didgeridoo
    InstrumentDef(listOf(), listOf(35, 38) + (104..111).filter { it != 105 }, "note.didgeridoo", 30, false),
    // 打击乐器特效音 (112-119) -> xylophone
    InstrumentDef(listOf(), (112..119).toList(), "note.xylophone", 78, false),

    // ==========================================
    // 2. 打击乐器映射 (Drum Channel: Channel 9 / 第10轨)
    // ==========================================
    // 大鼓 (Bass Drum)
    InstrumentDef(listOf(10), listOf(35, 36), "note.bd", 0, true),
    // 军鼓 (Snare)
    InstrumentDef(listOf(10), listOf(37, 38, 40), "note.snare", 0, true),
    // 电子击打/镲片 (Bit/Hat)
    InstrumentDef(listOf(10), listOf(42, 44), "note.bit", 0, true),
    // 强音/叮当声 (Pling)
    InstrumentDef(listOf(10), listOf(46), "note.pling", 0, true),
    // 汤姆鼓/低音打击 (Tom/Low Bass)
    InstrumentDef(listOf(10), listOf(41, 43, 45, 47, 48, 50), "note.bass", 0, true),
    // 碰铃/响板 (Chime)
    InstrumentDef(listOf(10), listOf(49, 51, 52, 55, 57), "note.chime", 0, true),
    // 牛铃 (Cowbell)
    InstrumentDef(listOf(10), listOf(56), "note.cow_bell", 0, true),
    // 木鱼/边缘打击 (Xylophone)
    InstrumentDef(listOf(10), listOf(53, 54), "note.xylophone", 0, true)
)
}