package command.plus

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import command.plus.ColorSpace
import command.plus.PixelArtGenerator
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.util.DebugLogger

import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Delete
import android.content.SharedPreferences
import command.plus.DitherAlgorithm

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
// --- 数据模型 ---
data class BlockInfo(val id: String, val assetPath: String)
data class BlockCategory(val name: String, val blocks: List<BlockInfo>)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageConversionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    val prefs = remember { context.getSharedPreferences("ImageConvertPrefs", Context.MODE_PRIVATE) }
    val setprefs = remember { context.getSharedPreferences("feature_prefs", Context.MODE_PRIVATE) }
    val isAbsolute_path by rememberPreference("isAbsolute_path", true, setprefs)
    var showResetDialog by remember { mutableStateOf(false) }
    
    // 预览与进度
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableStateOf("") }
    var resultString by remember { mutableStateOf("") }
    val customImages = remember { mutableStateListOf<Uri>() }

    // --- 1. 基础状态管理 (自动保存) ---
    var inputPath by rememberPreference("inputPath", "/storage/emulated/0/input.png", prefs)
    var outputType by rememberPreference("outputType", "内部存储 (应用私有)", prefs)
    var outputPath by rememberPreference("outputPath", "/storage/emulated/0/BlockArt/", prefs)
    
    var width by rememberPreference("width", "128", prefs)
    var height by rememberPreference("height", "128", prefs)
    var colorSpace by rememberPreference("colorSpace", "RGB", prefs)
    var ditherAlgorithm by rememberPreference("ditherAlgorithm", "NONE", prefs)
    var emptyBlockId by rememberPreference("emptyBlockId", "air", prefs)
    var isAddTxt by rememberPreference("isAddTxt", false, prefs)
    var multiplier by rememberPreference("multiplier", "1", prefs)
    
    var Sobj by rememberPreference("Sobj", "n", prefs)
    var Ename by rememberPreference("Ename", "A", prefs)
    var StartOffset by rememberPreference("StartOffset", "0", prefs)
    var ForbiddenScores by rememberPreference("ForbiddenScores", "6489,8964", prefs)
    var enableExtraSensitiveOptimization by rememberPreference("enableExtraSensitiveOptimization", true, prefs)
    var c1 by rememberPreference("c1", false, prefs)
    var c2 by rememberPreference("c2", false, prefs)
    var cc1 by remember { mutableStateOf(false) } // 这个是生成结果的状态，不需要持久化
    var cc2 by remember { mutableStateOf(false) }

    val options = listOf("长", "宽", "深")
    var s1 by rememberPreference("s1", options[0], prefs)
    var s1Val by rememberPreference("s1Val", "1", prefs)
    var s2 by rememberPreference("s2", options[1], prefs)
    var s2Val by rememberPreference("s2Val", "1", prefs)

    // --- 2. 复杂状态管理 (List 和 Map 的特殊处理) ---
    // 整数数组通过逗号分隔拼接成字符串保存
    var intsString by rememberPreference("intsString", "0,0,0,0", prefs)
    val ints = remember(intsString) { mutableStateListOf(*intsString.split(",").toTypedArray()) }
    
   var savedBlocksIds by rememberPreference("savedBlocksIds", "", prefs)
    val allDefaultBlocks = remember { mutableStateListOf<BlockInfo>() }
    val selectedDefaultBlocks = remember { mutableStateMapOf<String, BlockInfo>() }
    
var selectedFileName by rememberPreference("selectedFileName", "未选择任何文件", setprefs)
var selectedFileUri by rememberPreference("selectedFileUri", "", setprefs)
val bbb = """black_concrete=#151515
brown_concrete=#58412C
blue_concrete=#2C4199
cyan_concrete=#416D84
gray_concrete=#414141
green_concrete=#586D2C
lime_concrete=#6DB015
light_blue_concrete=#5884BA
light_gray_concrete=#848484
magenta_concrete=#9941BA
orange_concrete=#BA6D2C
pink_concrete=#D06D8E
red_concrete=#842C2C
white_concrete=#DCDCDC
purple_concrete=#6D3699
yellow_concrete=#C5C52C
copper_block=#BA6D2C
chiseled_copper=#BA6D2C
copper_bulb=#BA6D2C
cut_copper=#BA6D2C
exposed_chiseled_copper=#745C54
exposed_copper_bulb=#745C54
exposed_copper=#745C54
exposed_cut_copper=#745C54
oxidized_chiseled_copper=#126C73
oxidized_copper=#126C73
oxidized_cut_copper=#126C73
weathered_chiseled_copper=#327A78
oxidized_copper_bulb=#126C73
weathered_copper_bulb=#327A78
weathered_copper=#327A78
weathered_cut_copper=#327A78
glass=#00000000
blue_stained_glass=#2C4199
brown_stained_glass=#58412C
black_stained_glass=#151515
gray_stained_glass=#414141
cyan_stained_glass=#416D84
green_stained_glass=#586D2C
light_blue_stained_glass=#5884BA
light_gray_stained_glass=#848484
lime_stained_glass=#6DB015
magenta_stained_glass=#9941BA
orange_stained_glass=#BA6D2C
pink_stained_glass=#D06D8E
purple_stained_glass=#6D3699
red_stained_glass=#842C2C
tinted_glass=#414141
white_stained_glass=#DCDCDC
yellow_stained_glass=#C5C52C
blue_glazed_terracotta=#2C4199
brown_glazed_terracotta=#58412C
black_glazed_terracotta=#151515
cyan_glazed_terracotta=#416D84
gray_glazed_terracotta=#414141
green_glazed_terracotta=#586D2C
lime_glazed_terracotta=#6DB015
light_blue_glazed_terracotta=#5884BA
magenta_glazed_terracotta=#9941BA
orange_glazed_terracotta=#BA6D2C
pink_glazed_terracotta=#D06D8E
purple_glazed_terracotta=#6D3699
silver_glazed_terracotta=#848484
red_glazed_terracotta=#842C2C
white_glazed_terracotta=#DCDCDC
yellow_glazed_terracotta=#C5C52C
dark_oak_log=#58412C
mangrove_log=#842C2C
pale_oak_log=#DCD9D3
acacia_log=#BA6D2C
birch_log=#D5C98C
cherry_log=#B4988A
jungle_log=#825E42
oak_log=#7B663E
spruce_log=#6F4A2A
stripped_cherry_log=#B4988A
stripped_acacia_log=#BA6D2C
stripped_birch_log=#D5C98C
stripped_jungle_log=#825E42
stripped_oak_log=#7B663E
stripped_spruce_log=#6F4A2A
stripped_pale_oak_log=#DCD9D3
copper_ore=#606060
coal_ore=#606060
deepslate_coal_ore=#565656
deepslate_copper_ore=#565656
deepslate_diamond_ore=#565656
deepslate_emerald_ore=#565656
deepslate_gold_ore=#565656
deepslate_lapis_ore=#565656
deepslate_iron_ore=#565656
deepslate_redstone_ore=#565656
diamond_block=#4FBCB7
emerald_block=#00BB32
diamond_ore=#606060
emerald_ore=#606060
gold_block=#D7CD42
gold_ore=#606060
iron_block=#909090
iron_ore=#606060
lapis_ore=#606060
lapis_block=#3F6EDC
nether_gold_ore=#600100
netherite_block=#151515
quartz_block=#DCD9D3
quartz_ore=#600100
raw_copper_block=#BA6D2C
raw_gold_block=#D7CD42
raw_iron_block=#BA967E
redstone_ore=#606060
redstone_block=#DC0000
bedrock=#606060
blackstone=#151515
cobblestone=#606060
end_stone=#D5C98C
sandstone=#D5C98C
smooth_stone=#606060
stone=#606060
stone_bricks=#606060
tuff=#31231E
tuff_bricks=#31231E
black_terracotta=#1F120D
blue_terracotta=#41354F
brown_terracotta=#412B1E
gray_terracotta=#31231E
green_terracotta=#414624
cyan_terracotta=#4B4F4F
hardened_clay=#BA6D2C
light_gray_terracotta=#745C54
light_blue_terracotta=#6D5D77
lime_terracotta=#58642D
magenta_terracotta=#804B5D
orange_terracotta=#89461F
pink_terracotta=#8A4243
purple_terracotta=#693E4B
white_terracotta=#B4988A
yellow_terracotta=#A0721F
blue_wool=#2C4199
brown_wool=#58412C
black_wool=#151515
cyan_wool=#416D84
gray_wool=#414141
green_wool=#586D2C
light_blue_wool=#5884BA
lime_wool=#6DB015
light_gray_wool=#848484
magenta_wool=#9941BA
pink_wool=#D06D8E
orange_wool=#BA6D2C
purple_wool=#6D3699
red_wool=#842C2C
white_wool=#DCDCDC
yellow_wool=#C5C52C
coal_block=#151515
amethyst_block=#844DB0
honeycomb_block=#BA6D2C
pearlescent_froglight=#D06D8E
ochre_froglight=#D5C98C
purpur_block=#9941BA
sea_lantern=#DCD9D3
shroomlight=#842C2C
sculk=#0B0F13
verdant_froglight=#6D9081
chiseled_stone_bricks=#606060
end_bricks=#D5C98C
"""
var isMapModeEnabled by rememberPreference("isMapModeEnabled", false, prefs)
var mapMappingText by rememberPreference("mapMappingText", bbb, prefs)
var similarityThresholdStr by rememberPreference("similarityThreshold", "1.0", prefs)
var generatePureColorPreview by rememberPreference("generatePureColorPreview", false, prefs)



// 2. 定义 Launcher
val systemFilePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(), // 如果只需要选一个，用 OpenDocument
    onResult = { uri ->
        uri?.let {
            try {
                // 核心：申请持久化读权限
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                
                // 获取文件名用于显示
                var displayName = "未知文件"
                context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                }

                // 更新 UI 状态
                selectedFileName = displayName

                selectedFileUri = it.toString()
                
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }
)

    // --- 逻辑：加载并恢复 ---
    LaunchedEffect(Unit) {
        val folders = listOf("blocks/wool", "blocks/terracotta", "blocks/concrete", "blocks/glass", "blocks/glazed", "blocks/stone", "blocks/ore", "blocks/copper", "blocks/log", "blocks/other")
        val loaded = folders.flatMap { loadBlocksFromAssets(context, it) }
        
        allDefaultBlocks.clear()
        allDefaultBlocks.addAll(loaded)

        // 恢复选中逻辑
        val savedSet = savedBlocksIds.split(",").filter { it.isNotBlank() }.toSet()
        loaded.forEach { block ->
            if (savedSet.contains(block.id)) {
                selectedDefaultBlocks[block.id] = block
            }
        }
    }

    // --- 逻辑：实时保存 ---
    LaunchedEffect(selectedDefaultBlocks.size) {
        savedBlocksIds = selectedDefaultBlocks.keys.joinToString(",")
    }
    
    val blockPngDir = remember {
        File(context.getExternalFilesDir(null), "blockpng").apply {
            if (!exists()) mkdirs()
        }
    }
    // 1. 初始化时，从本地目录读取已有文件
    LaunchedEffect(Unit) {
        val existingFiles = blockPngDir.listFiles { _, name ->
            name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
        } ?: emptyArray()
        
        customImages.clear()
        customImages.addAll(existingFiles.map { Uri.fromFile(it) })
    }
    
val resetAll = {
    // 1. 清空 SharedPreferences 持久化数据
    prefs.edit().clear().apply()

    // 2. 删除物理图片文件
    val blockPngDir = File(context.getExternalFilesDir(null), "blockpng")
    if (blockPngDir.exists()) {
        blockPngDir.listFiles()?.forEach { it.delete() }
    }

    // 3. 重置 UI 状态变量 (恢复默认值)
    inputPath = "/storage/emulated/0/input.png"
    outputType = "内部存储 (应用私有)"
    outputPath = "/storage/emulated/0/BlockArt/"
    width = "128"
    height = "128"
    colorSpace = "RGB"
    ditherAlgorithm = "NONE"
    isAddTxt = false
    multiplier = "1"
    
    emptyBlockId = "air"
    Sobj = "n"
    Ename = "A"
    c1 = false
    c2 = false
    s1 = options[0]
    s1Val = "1"
    s2 = options[1]
    s2Val = "1"
    intsString = "0,0,0,0"
    StartOffset = "0"
    ForbiddenScores = "6489,8964"
    enableExtraSensitiveOptimization = true
    
    // 4. 清空列表和 Map
    customImages.clear()
    selectedDefaultBlocks.clear()
    savedBlocksIds = ""
    isMapModeEnabled=false
    
    mapMappingText=bbb
    
    similarityThresholdStr="1.0"
    generatePureColorPreview=false
    Toast.makeText(context, "重置成功", Toast.LENGTH_SHORT).show()
}

// 定义映射关系
val valueMap = mapOf(
    "长" to McCommandGenerator.Axis.长,
    "宽" to McCommandGenerator.Axis.宽,
    "深" to McCommandGenerator.Axis.深
)

val valueMap2 = mapOf(
    "NONE" to DitherAlgorithm.NONE,
    "FLOYD_STEINBERG" to DitherAlgorithm.FLOYD_STEINBERG,
    "BAYER_2x2" to DitherAlgorithm.BAYER_2x2,
    "BAYER_4x4" to DitherAlgorithm.BAYER_4x4,
    "BAYER_8x8" to DitherAlgorithm.BAYER_8x8,
    "ORDERED_3x3" to DitherAlgorithm.ORDERED_3x3,
    "ATKINSON" to DitherAlgorithm.ATKINSON,
    "BURKES" to DitherAlgorithm.BURKES
)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片转换") },
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 生成按钮
            Button(
                onClick = {
                    if (isGenerating) return@Button
                    isGenerating = true
                    generationProgress = "准备文件中..."
                    
                    coroutineScope.launch {
                        try {
                            // 1. 准备目标输出目录
                            val finalOutputDir = if (outputType.contains("内部存储")) {
                                File(context.getExternalFilesDir(null), "PixelArtResult")
                            } else {
                                File(outputPath)
                            }
                            
                            // 2. 将选中的素材整理为实打实的 File 列表
                            val textureFiles = prepareTextureFiles(context, selectedDefaultBlocks.values.toList(), customImages)
                            
                            if (textureFiles.isEmpty()) {
                                Toast.makeText(context, "请至少选择一个方块贴图", Toast.LENGTH_SHORT).show()
                                isGenerating = false
                                return@launch
                            }

                            // 3. 构建并执行生成器
                            val generator = PixelArtGenerator.Builder()
                                .apply {
        if (isAbsolute_path) {
            setInputImageFile(File(inputPath)!!)
        } else {
            setInputImageStream(context.contentResolver.openInputStream(Uri.parse(selectedFileUri))!!)
        }
        
        // 【关键改动】：判断并写入地图模式或传统贴图配置
        if (isMapModeEnabled) {
            setMapModeConfig(
                similarity = similarityThresholdStr.toFloatOrNull() ?: 1.0f
            )
        } else {
            setGeneratorMode(GeneratorMode.TEXTURE_PATCH)
        }
    }
    
                                .setGeneratePureColorPreview(generatePureColorPreview)
                                .setOutputDir(finalOutputDir)
                                .setTextureFiles(textureFiles)
                                .setDimensions(width.toIntOrNull() ?: 128, height.toIntOrNull() ?: 128)
                                .setColorSpace(ColorSpace.valueOf(colorSpace))
                                .setDitherAlgorithm(valueMap2[ditherAlgorithm] ?: DitherAlgorithm.NONE)
                                .setEmptyBlockId(emptyBlockId)
                                .setThex(ints[0].toIntOrNull() ?: 0)
                                .setThey(ints[1].toIntOrNull() ?: 0)
                                .setThez(ints[2].toIntOrNull() ?: 0)
                                .setTheLimit(ints[3].toIntOrNull() ?: 0)
                                .setTheObj(Sobj)
                                .setTheName(Ename)
                                .setTheMirrorh(c1)
                                .setTheMirrorv(c2)
                                .setTheinnerStep(s1Val.toIntOrNull() ?: 1)
                                .setTheouterStep(s2Val.toIntOrNull() ?: 1)
                                .setTheinnerAxis(valueMap[s1] ?: McCommandGenerator.Axis.长)
                                .setTheouterAxis(valueMap[s2] ?: McCommandGenerator.Axis.宽)
                                .setStartOffset(StartOffset.toIntOrNull() ?: 0)
                                .setForbiddenScores(parseIntList(ForbiddenScores) ?: emptySet<Int>())
                                .setMultiplier(multiplier.toIntOrNull() ?: 1)
                                .setisAddTxt(isAddTxt)
                                .setEnableExtraSensitiveOptimization(enableExtraSensitiveOptimization)
                                .setMapMappingText(mapMappingText)
                                .setCallback(object : PixelArtGenerator.Callback {
                                    override fun onProgress(message: String) {
                                        generationProgress = message
                                    }

                                    override fun onSuccess(resultImage: File, resultTxt: File, resultTotal: String) {
                                        resultString = resultTotal
                                        previewUri = Uri.fromFile(resultImage).buildUpon()
    .appendQueryParameter("t", System.currentTimeMillis().toString())
    .build()
                                        isGenerating = false
                                        Toast.makeText(context, "生成成功！", Toast.LENGTH_LONG).show()
                                        cc1 = c1
                                        cc2 = c2
                                    }

                                    override fun onError(e: Exception) {
                                        isGenerating = false
                                        Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_LONG).show()
                                        
                                    }
                                })
                                .build()

                            generator.generate()
                            
                        } catch (e: Exception) {
                            Toast.makeText(context, "准备失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            isGenerating = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isGenerating
            ) {
                Text(if (isGenerating) generationProgress else "开始生成", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            ImagePreviewCard(previewUri,cc1,cc2)
            
            TerminalCard(
                title = "生成结果",
                content = resultString
            )
            if (isAbsolute_path) {
            InputLocationCard(inputPath) { inputPath = it }
            } else {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("添加或覆盖图片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedCard(
            onClick = { systemFilePickerLauncher.launch(arrayOf("image/*")) }
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 实时显示当前选择的文件名
                Text(
                    text = "当前选择: $selectedFileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                
               
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(onClick = { 
                    // 启动选择器
                    systemFilePickerLauncher.launch(arrayOf("image/*")) 
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择外部片图片文件")
                }
            }
        }
    }
}
            }
            
            OutputLocationCard(outputType, outputPath, { outputType = it }, { outputPath = it })
            
            ConfigurationCard(
                width, { width = it }, height, { height = it },
                colorSpace, { colorSpace = it }, ditherAlgorithm, { ditherAlgorithm = it },
                emptyBlockId, { emptyBlockId = it },
                isAddTxt, { isAddTxt = it }
            )

            // 方块选择器传入状态
            BlockSelectorCard(selectedDefaultBlocks, customImages)
            
            // 地图模式设置卡片
MapModeConfigCard(
    isMapModeEnabled = isMapModeEnabled,
    onMapModeEnabledChange = { isMapModeEnabled = it },
    mapMappingText = mapMappingText,
    onMappingTextChange = { mapMappingText = it },
    similarityThresholdStr = similarityThresholdStr,
    onSimilarityChange = { similarityThresholdStr = it },
    generatePureColorPreview = generatePureColorPreview,
    onPurePreviewChange = { generatePureColorPreview = it }
)

            ConfigDashboardCard(
    Sobj = Sobj, onSobjChange = { Sobj = it },
    multiplier = multiplier, onMultiplierChange = { multiplier = it },
    Ename = Ename, onEnameChange = { Ename = it },
    StartOffset = StartOffset, onStartOffsetChange = { StartOffset = it },
    ForbiddenScores = ForbiddenScores, onForbiddenScoresChange = { ForbiddenScores = it },
    intValues = ints,
        onIntChange = { idx, value -> 
            ints[idx] = value
            intsString = ints.joinToString(",") // 触发保存
        },
    check1 = c1, onCheck1Change = { c1 = it },
    check2 = c2, onCheck2Change = { c2 = it },
    logicOptions = options,
    sel1 = s1, onSel1Change = { newValue ->
    if (newValue == s2) {
        // 发现冲突，直接交换两个框的值
        s2 = s1 
    }
    s1 = newValue
},
    sel1Input = s1Val, onSel1InputChange = { s1Val = it },
    sel2 = s2, onSel2Change = { newValue ->
    if (newValue == s1) {
        // 发现冲突，直接交换两个框的值
        s1 = s2 
    }
    s2 = newValue
},
    sel2Input = s2Val, onSel2InputChange = { s2Val = it },
    enableExtraSensitiveOptimization = enableExtraSensitiveOptimization, onEnableExtraSensitiveOptimizationChange = { enableExtraSensitiveOptimization = it },
)

        Button(
        onClick = {
            showResetDialog = true
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("重置所有配置")
    }
        }
    }
    
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有设置？") },
            text = { Text("这将重置所有文本框、取消选中的方块，并永久删除所有导入的自定义图片。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetAll() // 调用上面写好的函数
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

// ---------------------------------------------------------
// UI 组件
// ---------------------------------------------------------

@Composable
fun TerminalCard(
    title: String,
    content: String?,
    modifier: Modifier = Modifier,
    defaultText: String = "等待生成中..."
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
           
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121212))
                ) {
                    SelectionContainer {
                        Text(
                            text = if (content.isNullOrBlank()) defaultText else content,
                            color = Color(0xFF00FF41),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }
           
        }
    }
}

@Composable
fun InputLocationCard(inputPat: String, onPathChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("输入设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = inputPat, onValueChange = onPathChange,
                label = { Text("图片物理路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputLocationCard(outputType: String, outputPath: String, onTypeChange: (String) -> Unit, onPathChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("内部存储 (应用私有)", "外部存储 (公共目录)")

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("输出设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = outputType, onValueChange = {}, readOnly = true,
                    label = { Text("输出类型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption) },
                            onClick = { onTypeChange(selectionOption); expanded = false }
                        )
                    }
                }
            }
            
            // 需求1: 根据类型隐藏或显示外部路径输入框
            AnimatedVisibility(visible = outputType.contains("外部存储")) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = outputPath, onValueChange = onPathChange,
                        label = { Text("输出目录路径") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ConfigurationCard 省略，与上一版相同即可（受限于字数，这里只放有修改的部分）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationCard(
    width: String, onWidthChange: (String) -> Unit,
    height: String, onHeightChange: (String) -> Unit,
    colorSpace: String, onColorSpaceChange: (String) -> Unit,
    ditherAlgorithm: String, onDitherAlgorithmChange: (String) -> Unit,
    emptyBlockId: String, onEmptyBlockIdChange: (String) -> Unit,
    isAddTxt: Boolean, onisAddTxtChange: (Boolean) -> Unit
) {
    var expandedSpace by remember { mutableStateOf(false) }
    val spaceOptions = listOf("RGB", "HSV", "LAB")
    
    var expandedSpace2 by remember { mutableStateOf(false) }
    val spaceOptions2 = listOf("NONE", "FLOYD_STEINBERG", "BAYER_2x2", "BAYER_4x4", "BAYER_8x8", "ORDERED_3x3", "ATKINSON", "BURKES")

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("参数配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width, onValueChange = onWidthChange,
                    label = { Text("宽度(格)") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = height, onValueChange = onHeightChange,
                    label = { Text("高度(格)") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = expandedSpace,
                    onExpandedChange = { expandedSpace = !expandedSpace },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = colorSpace, onValueChange = {}, readOnly = true,
                        label = { Text("色彩空间") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSpace) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expandedSpace, onDismissRequest = { expandedSpace = false }) {
                        spaceOptions.forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = { onColorSpaceChange(opt); expandedSpace = false })
                        }
                    }
                }
                
                ExposedDropdownMenuBox(
                    expanded = expandedSpace2,
                    onExpandedChange = { expandedSpace2 = !expandedSpace2 },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = ditherAlgorithm, onValueChange = {}, readOnly = true,
                        label = { Text("抖动算法") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSpace2) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expandedSpace2, onDismissRequest = { expandedSpace2 = false }) {
                        spaceOptions2.forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = { onDitherAlgorithmChange(opt); expandedSpace2 = false })
                        }
                    }
                }
               
               
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = emptyBlockId, onValueChange = onEmptyBlockIdChange,
                label = { Text("透明填充方块 (如 air, glass)") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("是否生成方块阵列文件", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = isAddTxt, onCheckedChange = onisAddTxtChange)
                    }
        }
    }
}

@Composable
fun BlockSelectorCard(
    selectedDefaultBlocks: MutableMap<String, BlockInfo>,
    customImages: MutableList<Uri>
) {
    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("说明区", "默认方块区", "自定义方块区")

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, fontSize = 13.sp) })
                }
            }
            
            Box(modifier = Modifier.fillMaxWidth().height(300.dp).padding(8.dp)) {
                when (selectedTab) {
                    0 -> InstructionTabContent()
                    1 -> DefaultBlocksTabContent(selectedDefaultBlocks)
                    2 -> CustomBlocksTabContent(customImages)
                }
                
                
            }
        }
        if (!customImages.isEmpty() && selectedTab == 2) {
        Text(
                    text = "双击删除 | 长按全删",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                )
                }
    }
}

@Composable
fun ConfigDashboardCard(
    // --- 基础字符串输入 (2个) ---
    Sobj: String, onSobjChange: (String) -> Unit,
    multiplier: String, onMultiplierChange: (String) -> Unit,
    Ename: String, onEnameChange: (String) -> Unit,
    StartOffset: String, onStartOffsetChange: (String) -> Unit,
    ForbiddenScores: String, onForbiddenScoresChange: (String) -> Unit,

    // --- 6个整数输入 (用List传递更整洁) ---
    intValues: List<String>, // 建议传入String以便处理输入过程中的空值
    onIntChange: (Int, String) -> Unit,

    // --- 2个复选框 ---
    check1: Boolean, onCheck1Change: (Boolean) -> Unit,
    check2: Boolean, onCheck2Change: (Boolean) -> Unit,

    // --- 逻辑UI参数 (3选2) ---
    logicOptions: List<String> = listOf("参数A", "参数B", "参数C"),
    sel1: String, onSel1Change: (String) -> Unit,
    sel1Input: String, onSel1InputChange: (String) -> Unit,
    sel2: String, onSel2Change: (String) -> Unit,
    sel2Input: String, onSel2InputChange: (String) -> Unit,
    enableExtraSensitiveOptimization: Boolean, onEnableExtraSensitiveOptimizationChange: (Boolean) -> Unit,
) {
    // 逻辑计算：自动算出被排除的那个
    val excludedParam = logicOptions.find { it != sel1 && it != sel2 } ?: "无"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("指令配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // 1. 字符串区域 (一行两个)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(Modifier.weight(1f), "计分板名称 (默认'n')", Sobj, onSobjChange)
                CompactTextField(Modifier.weight(1f), "盔甲架名称 (默认'A')", Ename, onEnameChange)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 6个整数区域 (一行两个，共三行)
            val intLabels = listOf("起点x (默认0)", "起点y (默认0)", "起点z (默认0)", "单条字符上限 (默认0,0表示无上限)")
for (i in intLabels.indices) {
    IntTextField(
        modifier = Modifier.fillMaxWidth(),
        label = intLabels[i],
        value = intValues[i],
        onValueChange = { onIntChange(i, it) }
    )
    if (i < intLabels.size - 1) {
        Spacer(modifier = Modifier.height(8.dp))
    }
}
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(Modifier.weight(1f), "生成倍数 (默认1)", multiplier, onMultiplierChange)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(Modifier.weight(1f), "记分板相对起点 (默认0)", StartOffset, onStartOffsetChange)
                CompactTextField(Modifier.weight(1f), "违禁数值 (用逗号分隔)", ForbiddenScores, onForbiddenScoresChange)
            }
            
            Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("是否启用网易违禁词规避", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = enableExtraSensitiveOptimization, onCheckedChange = onEnableExtraSensitiveOptimizationChange)
                    }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

            // 3. 复选框部分
            Row(Modifier.fillMaxWidth()) {
                CheckboxWithLabel(Modifier.weight(1f), check1, onCheck1Change, "竖向镜像", "以给出图片为基准")
                CheckboxWithLabel(Modifier.weight(1f), check2, onCheck2Change, "横向镜像", "以给出的图片为基准")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
    
            // 4. 逻辑UI (3选2)
            Text("移动方向", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            // 第一个展开栏 + 输入框
            LogicSelectionRow(
                label = "行进轴",
                options = logicOptions,
                selected = sel1,
                onSelected = onSel1Change,
                inputValue = sel1Input,
                onValueChange = onSel1InputChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第二个展开栏 + 输入框 (候选项需排除第一个已选的)
            LogicSelectionRow(
                label = "换行轴",
                options = logicOptions.filter { it != sel1 },
                selected = sel2,
                onSelected = onSel2Change,
                inputValue = sel2Input,
                onValueChange = onSel2InputChange
            )

            // 排除结果展示
            Surface(
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = "系统提示：当前 $excludedParam 已被自动排除，生成不会影响此轴。",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LogicSelectionRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    inputValue: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 下拉选择框 (占4成宽度)
        Box(modifier = Modifier.weight(0.4f)) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text(label, fontSize = 10.sp) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.clickable { expanded = true }) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        onSelected(option)
                        expanded = false
                    })
                }
            }
        }

        // 对应的输入框 (占6成宽度)
        OutlinedTextField(
            value = inputValue,
            onValueChange = onValueChange,
            label = { Text("移动值 (默认1,表示往前一格)", fontSize = 10.sp) },
            modifier = Modifier.weight(0.6f),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
}

@Composable
fun CompactTextField(modifier: Modifier, label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        modifier = modifier,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
    )
}

@Composable
fun IntTextField(modifier: Modifier, label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
    )
}

@Composable
fun CheckboxWithLabel(modifier: Modifier, checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, desc: String) {
    Row(modifier = modifier.clickable { onCheckedChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp)
        }
    }
}

// ---------------------------------------------------------
// 默认方块区 (带展开、全选)
// ---------------------------------------------------------

@Composable
fun DefaultBlocksTabContent(selectedDefaultBlocks: MutableMap<String, BlockInfo>) {
    // 模拟的默认数据字典 (真实情况应从 assets 目录动态遍历或硬编码配置)
    val context = LocalContext.current
    val mockCategories = remember {
        listOf(
            BlockCategory("羊毛 (Wool)",
                loadBlocksFromAssets(context, "blocks/wool")
            ),
            
            BlockCategory("陶瓦 (Terracotta)",
                loadBlocksFromAssets(context, "blocks/terracotta")
            ),
            
            BlockCategory("混凝土 (Concrete)",
                loadBlocksFromAssets(context, "blocks/concrete")
            ),
            
            BlockCategory("玻璃 (Glass)",
                loadBlocksFromAssets(context, "blocks/glass")
            ),
            
           BlockCategory("釉陶 (Glazed)",
                loadBlocksFromAssets(context, "blocks/glazed")
            ),
            
            BlockCategory("石头 (Stone)",
                loadBlocksFromAssets(context, "blocks/stone")
            ),
            
            BlockCategory("矿物 (Ore)",
                loadBlocksFromAssets(context, "blocks/ore")
            ),
            
            BlockCategory("铜 (Copper)",
                loadBlocksFromAssets(context, "blocks/copper")
            ),
            
            BlockCategory("木头 (Log)",
                loadBlocksFromAssets(context, "blocks/log")
            ),
            
            BlockCategory("其他 (Other)",
                loadBlocksFromAssets(context, "blocks/other")
            )
            
            )
            
        
    }

    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        mockCategories.forEach { category ->
            val isExpanded = expandedCategories[category.name] == true
            // 判断当前分类下是否所有方块都已选中
            val allSelected = category.blocks.all { selectedDefaultBlocks.containsKey(it.id) }
            val someSelected = category.blocks.any { selectedDefaultBlocks.containsKey(it.id) }

            // 分类标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedCategories[category.name] = !isExpanded }
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                // 全选/全不选 复选框
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        if (checked) {
                            category.blocks.forEach { selectedDefaultBlocks[it.id] = it }
                        } else {
                            category.blocks.forEach { selectedDefaultBlocks.remove(it.id) }
                        }
                    }
                )
                Text(text = category.name, modifier = Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold)
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Expand"
                )
            }

            // 展开后的具体方块明细
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(start = 32.dp)) {
                    category.blocks.forEach { block ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedDefaultBlocks.containsKey(block.id)) {
                                        selectedDefaultBlocks.remove(block.id)
                                    } else {
                                        selectedDefaultBlocks[block.id] = block
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = selectedDefaultBlocks.containsKey(block.id),
                                onCheckedChange = { checked ->
                                    if (checked) selectedDefaultBlocks[block.id] = block
                                    else selectedDefaultBlocks.remove(block.id)
                                }
                            )
                            // 显示Assets中的方块图标 (需加载Assets图的逻辑，这里简化使用纯色占位或Coil从Assets读取 "file:///android_asset/${block.assetPath}")
                            AsyncImage(
                                model = "file:///android_asset/${block.assetPath}",
                                contentDescription = block.id,
                                modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(24.dp),
                                contentScale = ContentScale.Crop
                            )
                            Text(text = block.id, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------
// 自定义方块区 (完善名称和图标显示)
// ---------------------------------------------------------

@Composable
fun CustomBlocksTabContent(customImages: MutableList<Uri>) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // 定义目标私有文件夹: Android/data/.../files/blockpng
    val blockPngDir = remember {
        File(context.getExternalFilesDir(null), "blockpng").apply {
            if (!exists()) mkdirs()
        }
    }

    // 2. 选择器：选中后拷贝文件到物理目录
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        coroutineScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val fileName = getFileName(context, uri)
                val destFile = File(blockPngDir, fileName)
                
                try {
                    // 将外部 Uri 的流拷贝到应用私有目录
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output -> 
                            input.copyTo(output) 
                        }
                    }
                    // 更新 UI 状态
                    val newUri = Uri.fromFile(destFile)
                    withContext(Dispatchers.Main) {
                        if (!customImages.contains(newUri)) {
                            customImages.add(newUri)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { launcher.launch(arrayOf("image/png", "image/jpeg")) },
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add")
            Spacer(modifier = Modifier.width(4.dp))
            Text("导入 PNG")
        }

        if (customImages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无自定义方块", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp)
            ) {
                items(items = customImages, key = { it.toString() }) { uri ->
                    CustomBlockItem(
                        uri = uri,
                        modifier = Modifier.animateItem(),
                        onDoubleTap = {
                            // 3. 双击删除：不仅从列表移除，还要删除物理文件
                            File(uri.path ?: "").delete()
                            customImages.remove(uri)
                        },
                        onLongPress = {
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(text = "清空图标？") },
            text = { Text(text = "确定要删除全部图标吗？此操作将删除本地文件，不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 4. 全部清空：删除目录下所有文件并清空列表
                        blockPngDir.listFiles()?.forEach { it.delete() }
                        customImages.clear()
                        showDeleteDialog = false
                    }
                ) {
                    Text("确定删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun CustomBlockItem(
    uri: Uri, 
    modifier: Modifier = Modifier, 
    onDoubleTap: (Uri) -> Unit,
    onLongPress: (Uri) -> Unit
) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("未知") }
    
    LaunchedEffect(uri) {
        fileName = getFileName(context, uri).substringBeforeLast(".")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier // 继承来自 animateItem 的 modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp)) // 让水波纹限制在圆角矩形内
            .combinedClickable(
                onClick = { /* 如果需要单击选中的话可以在这里写逻辑 */ },
                onDoubleClick = { onDoubleTap(uri) },
                onLongClick = { onLongPress(uri) }
            )
            .padding(8.dp) // 点击范围内的内边距
    ) {
        AsyncImage(
            model = uri,
            contentDescription = fileName,
            modifier = Modifier
                .size(48.dp)
                .background(Color.LightGray, RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
        )
        Text(
            text = fileName, 
            fontSize = 10.sp, 
            maxLines = 1, 
            textAlign = TextAlign.Center, 
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun InstructionTabContent() {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("方块选择说明：", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "1. 默认方块区提供自带的贴图分类组合。\n" +
            "2. 自定义方块区允许你导入外部的 .png 贴图(也可直接在 /Android/data/command.plus/files/blockpng/ 下修改)。\n" +
            "3. 如果自定义方块名称与默认重名，系统将优先使用默认方块。\n" +
            "4. 请注意:生成指令时的方块id将会以他的图标文件名称为准。\n" +
            "5. 如果你想弄其他面(可能要设置特殊值)的图标，你可以选择修改生成的指令文件(默认特殊值都是0)。",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 带有缩放和拖拽功能的图片预览卡片
 * @param previewUri 图片的 Uri，为 null 时显示占位提示
 */
@Composable
fun ImagePreviewCard(previewUri: Uri?,c1: Boolean,c2: Boolean) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(previewUri) {
        scale = 1f
        offset = Offset.Zero
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "生成预览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))  // 使用 clip 而不是 clipToBounds
                    .background(Color.DarkGray)
                    .onGloballyPositioned { coordinates ->
                        containerSize = coordinates.size
                    }
                    .pointerInput(containerSize) {
                        // 直接使用 detectTransformGestures，不需要额外的 awaitPointerEventScope
                        detectTransformGestures { _, pan, zoom, _ ->
                            val maxWidth = containerSize.width.toFloat()
                            val maxHeight = containerSize.height.toFloat()
                            
                            if (maxWidth > 0 && maxHeight > 0) {
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                
                                val maxX = (maxWidth * (newScale - 1)) / 2
                                val maxY = (maxHeight * (newScale - 1)) / 2
                                
                                scale = newScale
                                
                                if (newScale > 1f) {
                                    val newOffset = offset + pan
                                    offset = Offset(
                                        x = newOffset.x.coerceIn(-maxX, maxX),
                                        y = newOffset.y.coerceIn(-maxY, maxY)
                                    )
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (previewUri == null) {
                    Text(
                        text = "等待生成结果...",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    

// 使用自定义的 ImageLoader
AsyncImage(
    model = previewUri,
    contentDescription = "Generated Image",
    imageLoader = ImageLoader.Builder(LocalContext.current)
    .crossfade(true)
    .bitmapConfig(Bitmap.Config.RGB_565)
    .respectCacheHeaders(false)
    .build(),
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer(
            scaleX = scale * (if(c1) -1 else 1),
            scaleY = scale * (if(c2) -1 else 1),
            translationX = offset.x,
            translationY = offset.y,
        ),
    filterQuality = FilterQuality.High,
    contentScale = ContentScale.Fit
)
                }
            }

            if (previewUri != null) {
                Text(
                    text = "双指缩放 | 单指拖拽",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapModeConfigCard(
    isMapModeEnabled: Boolean,
    onMapModeEnabledChange: (Boolean) -> Unit,
    mapMappingText: String,
    onMappingTextChange: (String) -> Unit,
    similarityThresholdStr: String,
    onSimilarityChange: (String) -> Unit,
    generatePureColorPreview: Boolean,
    onPurePreviewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 卡片头部：标题与核心开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "地图优化",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isMapModeEnabled) "当前状态：已启用" else "当前状态：未启用(传统贴图)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMapModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 核心开关：修复了颜色融为一体导致圆块“消失”的问题
                Switch(
                    checked = isMapModeEnabled,
                    onCheckedChange = { onMapModeEnabledChange(it) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 2. 映射文本输入框
            OutlinedTextField(
                value = mapMappingText,
                onValueChange = onMappingTextChange,
                label = { Text("地图映射文本 (格式: id=#HEX)") },
                placeholder = { Text("stone=#7F7F7F\ngrass_block=#557A33") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 10,
                supportingText = { Text("每行一条映射，用等号分隔方块ID与HEX颜色") }
            )

            // 3. 相似度筛选阈值输入框（替代了原本的滑块）
            OutlinedTextField(
                value = similarityThresholdStr,
                onValueChange = { onSimilarityChange(it) },
                label = { Text("相似度筛选 (0~1,-1表示纯随机)") },
                placeholder = { Text("例如: 0.5") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 4. 是否输出纯色拼接预览开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "纯色拼接预览", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "开启后将输出纯色HEX拼接图，关闭则输出物理贴图拼接图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = generatePureColorPreview,
                    onCheckedChange = onPurePreviewChange
                )
            }
        }
    }
}
// ---------------------------------------------------------
// 后台文件与名字解析助手
// ---------------------------------------------------------

/** 从 Uri 获取真实的 Display Name */
fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        }
    }
    return result ?: uri.lastPathSegment ?: "unknown"
}

private fun parseIntList(text: String): Set<Int> {
    return text
        .split(',', '，', ' ', ';', '|')
        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
        .toSet()
}

/** * 在后台线程，将选中的 Assets 和外部 Uris 统一拷贝到应用的私有 Cache 目录，
 * 生成 File 列表供生成器调用。
 */
suspend fun prepareTextureFiles(
    context: Context, 
    defaultBlocks: List<BlockInfo>, 
    customUris: List<Uri>
): List<File> = withContext(Dispatchers.IO) {
    val resultFiles = mutableListOf<File>()
    val tempDir = File(context.cacheDir, "block_textures_temp")
    if (!tempDir.exists()) tempDir.mkdirs()

    // 1. 提取资产文件
    for (block in defaultBlocks) {
        try {
            val outFile = File(tempDir, "${block.id}.png")
            context.assets.open(block.assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            resultFiles.add(outFile)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 2. 提取用户自定义图片
    for (uri in customUris) {
        try {
            val name = getFileName(context, uri)
            val outFile = File(tempDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            // 根据要求，名字重复时以默认方块优先。此处如果同名直接覆盖，或判断已存在跳过。
            // 因为之前Assets先写，此处我们可以选择跳过(不覆盖)
            if (!resultFiles.any { it.nameWithoutExtension == outFile.nameWithoutExtension }) {
                resultFiles.add(outFile)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    resultFiles
}

fun loadBlocksFromAssets(context: Context, folderPath: String): List<BlockInfo> {
    val assetManager = context.assets
    return try {
        // 列出文件夹下所有文件 (例如: "blocks/wool")
        val files = assetManager.list(folderPath) ?: emptyArray()
        
        files.filter { it.endsWith(".png") }.map { fileName ->
            // 去掉后缀名作为 ID，例如 "white_wool.png" -> "white_wool"
            val id = fileName.substringBeforeLast(".")
            BlockInfo(id, "$folderPath/$fileName")
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

@Composable
fun <T> rememberPreference(
    key: String,
    defaultValue: T,
    prefs: SharedPreferences
): MutableState<T> {
    val state = remember(key) {
        mutableStateOf(
            when (defaultValue) {
                is String -> prefs.getString(key, defaultValue) as T
                is Boolean -> prefs.getBoolean(key, defaultValue) as T
                is Int -> prefs.getInt(key, defaultValue) as T
                else -> defaultValue
            }
        )
    }
    
    // 监听状态变化并自动保存
    LaunchedEffect(key, state.value) {
        prefs.edit().apply {
            when (val value = state.value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            }
            apply()
        }
    }
    return state
}