package command.plus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import java.util.UUID
import androidx.compose.material.icons.filled.Delete

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 在文件顶部添加缺失的导入
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton

enum class AxisDirection { POSITIVE, NEGATIVE }

object AssistRuleParser {
    fun parse(
        text: String,
        innerAxisStr: String,
        innerStepStr: String,
        middleAxisStr: String,
        middleStepStr: String,
        outerAxisStr: String,
        outerStepStr: String
    ): List<AssistRuleData> {
        val rules = mutableListOf<AssistRuleData>()
        val lines = text.lines()
        
        // 获取当前轴的方向矢量
        val innerAxis = runCatching { Axis3D.valueOf(innerAxisStr) }.getOrDefault(Axis3D.X)
        val middleAxis = runCatching { Axis3D.valueOf(middleAxisStr) }.getOrDefault(Axis3D.Z)
        val outerAxis = runCatching { Axis3D.valueOf(outerAxisStr) }.getOrDefault(Axis3D.Y)
        
        val innerSign = if ((innerStepStr.toIntOrNull() ?: 1) >= 0) 1 else -1
        val middleSign = if ((middleStepStr.toIntOrNull() ?: 1) >= 0) 1 else -1
        val outerSign = if ((outerStepStr.toIntOrNull() ?: 1) >= 0) 1 else -1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            try {
                // 1. 先剥离头部匹配模式 [EXACT] 或 [CONTAINS]
val matchType = when {
    trimmed.startsWith("[EXACT]") -> MatchType.EXACT
    trimmed.startsWith("[CONTAINS]") -> MatchType.CONTAINS
    else -> MatchType.EXACT
}
val withoutHead = if (trimmed.startsWith("[")) trimmed.substringAfter("]").trim() else trimmed

// 2. 核心：优先使用 "->" 切割三大块，避免受括号内逗号的影响
val parts = withoutHead.split("->").map { it.trim() }
if (parts.size != 3) continue

var targetPart = parts[0]
val offsetPart = parts[1]
var assistPart = parts[2]

// 3. 此时从最后的 assistPart 剥离分区条件（因为它一定在全行最末尾）
val condition = when {
    assistPart.endsWith("[SAME]") -> PartitionCondition.SAME_PARTITION
    assistPart.endsWith("[DIFFERENT]") -> PartitionCondition.DIFFERENT_PARTITION
    else -> PartitionCondition.SAME_PARTITION // 默认值
}
val assistBlockStr = if (assistPart.endsWith("]")) {
    // 如果带了条件，去掉最后的 [SAME] 或 [DIFFERENT]
    if (assistPart.endsWith("[SAME]") || assistPart.endsWith("[DIFFERENT]")) {
        assistPart.substringBeforeLast("[").trim()
    } else assistPart
} else assistPart

// 4. 解析目标方块ID与状态（此时 targetPart 绝对纯净，支持逗号分隔）
val targetBlockId = targetPart.substringBefore("(").trim()
val targetStates = if (targetPart.contains("(") && targetPart.contains(")")) {
    val stateStr = targetPart.substringAfter("(").substringBeforeLast(")")
    stateStr.split(",")
        .map { it.trim() }
        .filter { it.contains("=") }
        .associate {
            val kv = it.split("=")
            val k = kv[0].trim()
            val vStr = kv[1].trim()
            val v: Any = vStr.toBooleanStrictOrNull() 
                ?: vStr.toIntOrNull() 
                ?: vStr
            k to v
        }
} else null

                // 5. 解析偏移 (支持纯数值坐标或内部变量)
                var offsetX = 0
                var offsetY = 0
                var offsetZ = 0

                if (offsetPart.startsWith("(") && offsetPart.endsWith(")")) {
                    // 纯数值模式 (X,Y,Z)
                    val coords = offsetPart.drop(1).dropLast(1).split(",").map { it.trim().toInt() }
                    offsetX = coords[0]
                    offsetY = coords[1]
                    offsetZ = coords[2]
                } else {
                    // 内部变量模式: innerAxis 1, middleAxis -1 等
                    val vParts = offsetPart.split("\\s+".toRegex())
                    val axisVar = vParts[0]
                    val multiplier = vParts.getOrNull(1)?.toIntOrNull() ?: 1

                    val (targetAxis, sign) = when (axisVar) {
                        "innerAxis" -> innerAxis to innerSign
                        "middleAxis" -> middleAxis to middleSign
                        "outerAxis" -> outerAxis to outerSign
                        else -> Axis3D.X to 1
                    }
                    val totalMove = multiplier * sign
                    when (targetAxis) {
                        Axis3D.X -> offsetX = totalMove
                        Axis3D.Y -> offsetY = totalMove
                        Axis3D.Z -> offsetZ = totalMove
                    }
                }

                rules.add(
                    AssistRuleData(targetBlockId, matchType, targetStates, offsetX, offsetY, offsetZ, assistBlockStr, condition)
                )
            } catch (e: Exception) {
                e.printStackTrace() // 略过断言失败的行
            }
        }
        return rules
    }
}

data class AssistRuleData(
    val targetBlockId: String,
    val matchType: MatchType,
    val targetStates: Map<String, Any?>?,
    val offsetX: Int,
    val offsetY: Int,
    val offsetZ: Int,
    val assistBlockStr: String,
    val condition: PartitionCondition
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McStructure3DCommandScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val prefs = remember {
        context.getSharedPreferences("McStructure3DCommandPrefs", Context.MODE_PRIVATE)
    }

    // 所有的状态全部直接存储为 String，确保文本框可以自由输入任何内容（如负号，删空等）
    var structureSource by rememberPreference("structureSource", "", prefs)
    var scoreboardObj by rememberPreference("scoreboardObj", "n", prefs)
    var entityName by rememberPreference("entityName", "C", prefs)
    var charLimit by rememberPreference("charLimitStr", "10000", prefs)
    var startX by rememberPreference("startXStr", "0", prefs)
    var startY by rememberPreference("startYStr", "0", prefs)
    var startZ by rememberPreference("startZStr", "0", prefs)
    var mirrorX by rememberPreference("mirrorX", false, prefs)
    var mirrorY by rememberPreference("mirrorY", false, prefs)
    var mirrorZ by rememberPreference("mirrorZ", false, prefs)
    var innerAxis by rememberPreference("innerAxis", Axis3D.X.name, prefs)
    var innerStep by rememberPreference("innerStepStr", "1", prefs)
    var middleAxis by rememberPreference("middleAxis", Axis3D.Z.name, prefs)
    var middleStep by rememberPreference("middleStepStr", "1", prefs)
    var outerAxis by rememberPreference("outerAxis", Axis3D.Y.name, prefs)
    var outerStep by rememberPreference("outerStepStr", "1", prefs)
    var startScoreOffset by rememberPreference("startScoreOffsetStr", "0", prefs)
    var forbiddenScoresText by rememberPreference("forbiddenScoresText", "6489,8964", prefs)
    var generationMultiplier by rememberPreference("generationMultiplierStr", "1", prefs)

    var outputType by rememberPreference("outputType", "内部存储", prefs)
    var outputPath by rememberPreference("outputPath", "/storage/emulated/0/McStructure3D", prefs)
    var outputFileName by rememberPreference("outputFileName", "McStructure3D", prefs)
    val aaa = """
[CONTAINS]sapling -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:dandelion -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:poppy -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:golden_dandelion -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:short_grass -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:tall_grass -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:fern -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:large_fern -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:pink_petals -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:torchflower -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:pitcher_plant -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:wheat -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:carrots -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:potatoes -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:beetroot -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:torchflower_crop -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:pitcher_crop -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:melon_stem -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:pumpkin_stem -> (0,-1,0) -> minecraft:farmland [DIFFERENT]
[EXACT]minecraft:cocoa(direction=0) -> (0,0,1) -> minecraft:jungle_log [SAME]
[EXACT]minecraft:cocoa(direction=1) -> (-1,0,0) -> minecraft:jungle_log [DIFFERENT]
[EXACT]minecraft:cocoa(direction=2) -> (0,0,-1) -> minecraft:jungle_log [DIFFERENT]
[EXACT]minecraft:nether_wart -> (0,-1,0) -> minecraft:soul_sand [DIFFERENT]
[EXACT]minecraft:crimson_fungus -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:warped_fungus -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:crimson_roots -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:warped_roots -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:nether_sprouts -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:weeping_vines -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:cave_vines -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:twisting_vines -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:spore_blossom -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:big_dripleaf -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:chorus_flower -> (0,-1,0) -> minecraft:end_stone [DIFFERENT]
[EXACT]minecraft:chorus_plant -> (0,-1,0) -> minecraft:end_stone [DIFFERENT]
[EXACT]minecraft:lily_pad -> (0,-1,0) -> minecraft:water [DIFFERENT]
[EXACT]minecraft:sea_pickle -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:seagrass -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:kelp -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:brown_mushroom -> (0,-1,0) -> minecraft:mycelium [DIFFERENT]
[EXACT]minecraft:red_mushroom -> (0,-1,0) -> minecraft:mycelium [DIFFERENT]
[EXACT]minecraft:flowering_azalea -> (0,-1,0) -> minecraft:grass [DIFFERENT]
[EXACT]minecraft:azalea -> (0,-1,0) -> minecraft:grass [DIFFERENT]

[EXACT]minecraft:redstone_wire -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:repeater -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:comparator -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:lever(lever_direction=east) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:lever(lever_direction=north) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:lever(lever_direction=south) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:lever(lever_direction=up_east_west) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:lever(lever_direction=up_north_south) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:lever(lever_direction=down_east_west) -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:lever(lever_direction=down_east_west) -> (0,1,0) -> minecraft:deny [SAME]
[CONTAINS]button(facing_direction=0) -> (0,1,0) -> minecraft:deny [SAME]
[CONTAINS]button(facing_direction=1) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]button(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[CONTAINS]button(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[CONTAINS]button(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]plate -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]rail -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=west) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=north) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=south) -> (0,0,1) -> minecraft:deny [SAME]
[CONTAINS]_torch(torch_facing_direction=top) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:torch(torch_facing_direction=west) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:torch(torch_facing_direction=north) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:torch(torch_facing_direction=south) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:torch(torch_facing_direction=top) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:ladder(facing_direction=0) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:ladder(facing_direction=1) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:ladder(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:ladder(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:ladder(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=west) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=north) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[CONTAINS]_torch(torch_facing_direction=south) -> (0,0,1) -> minecraft:deny [SAME]
[CONTAINS]carpet -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]lantern(hanging=true) -> (0,1,0) -> minecraft:deny [SAME]
[CONTAINS]lantern(hanging=false) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:pointed_dripstone(hanging=true) -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:pointed_dripstone(hanging=false) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]button(facing_direction=0) -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:frame(facing_direction=1) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:frame(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:frame(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:frame(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:glow_frame(facing_direction=1) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:glow_frame(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:glow_frame(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:glow_frame(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:bell(attachment=hanging) -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:bell(attachment=standing) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:grindstone(attachment=hanging) -> (0,1,0) -> minecraft:deny [SAME]
[EXACT]minecraft:grindstone(attachment=standing) -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:grindstone(attachment=side,direction=0) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:grindstone(attachment=side,direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:grindstone(attachment=side,direction=3) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]wall_sign(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]wall_sign(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[CONTAINS]wall_sign(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[CONTAINS]standing_sign -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:flower_pot -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:standing_banner -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:wall_banner(facing_direction=5) -> (-1,0,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:wall_banner(facing_direction=3) -> (0,0,-1) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:wall_banner(facing_direction=2) -> (0,0,1) -> minecraft:deny [SAME]
[EXACT]minecraft:wall_banner(facing_direction=4) -> (1,0,0) -> minecraft:deny [SAME]
[CONTAINS]_door -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]hanging_sign(attached=true) -> (0,1,0) -> minecraft:deny [SAME]

[EXACT]minecraft:sand -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:red_sand -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:suspicious_sand -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:suspicious_gravel -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:gravel -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:anvil -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[EXACT]minecraft:dragon_egg -> (0,-1,0) -> minecraft:deny [DIFFERENT]
[CONTAINS]concrete_powder -> (0,-1,0) -> minecraft:deny [DIFFERENT]
    """.trimIndent()
    var assistRulesText by rememberPreference(
        "assistRulesText",
        aaa,
        prefs
    )
var mappingMode by rememberPreference("mappingMode", "内置资源", prefs) // 可选: "内置资源", "指定路径"
var customMappingPath by rememberPreference("customMappingPath", "/storage/emulated/0/Download/mapping.json", prefs)
var bedrockVersion by rememberPreference("bedrockVersion", "1.26.30", prefs)
var skipBlockIdsText by rememberPreference("skipBlockIdsText", "", prefs)
var skipMatchType by rememberPreference("skipMatchType", MatchType.EXACT.name, prefs)
var skipFilterMode by rememberPreference("skipFilterMode", FilterMode.WHITELIST.name, prefs)
    
    
   
    var isGenerating by remember { mutableStateOf(false) }
    var generationStatus by remember { mutableStateOf("") }
    var lastSavedPath by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }

    // 重置逻辑：恢复到最初默认初始值
    fun resetAll() {
        structureSource = ""
        scoreboardObj = "n"
        entityName = "C"
        charLimit = "10000"
        startX = "0"
        startY = "0"
        startZ = "0"
        mirrorX = false
        mirrorY = false
        mirrorZ = false
        innerAxis = Axis3D.X.name
        innerStep = "1"
        middleAxis = Axis3D.Z.name
        middleStep = "1"
        outerAxis = Axis3D.Y.name
        outerStep = "1"
        startScoreOffset = "0"
        forbiddenScoresText = "6489,8964"
        generationMultiplier = "1"
        outputType = "内部存储"
        outputPath = "/storage/emulated/0/McStructure3D"
        outputFileName = "McStructure3D"
        assistRulesText = aaa
        mappingMode = "内置资源"
customMappingPath = "/storage/emulated/0/Download/mapping.json"
bedrockVersion = "1.26.30"
skipBlockIdsText = ""
skipMatchType = MatchType.EXACT.name
skipFilterMode = FilterMode.WHITELIST.name
    }

    val pickStructureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        structureSource = uri.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("3D 盔甲架结构命令生成器") },
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
            Button(
                onClick = {
                    if (!isGenerating) {
                        val source = structureSource.trim()
                        if (source.isBlank()) {
                            Toast.makeText(context, "请先选择结构文件", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val axes = listOf(innerAxis, middleAxis, outerAxis)
                        if (axes.distinct().size != 3) {
                            Toast.makeText(context, "行进轴、加深轴和最外层轴不能重复", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isGenerating = true
                        generationStatus = "准备生成中..."
                        lastSavedPath = ""
                        

                        coroutineScope.launch {
                            try {
                                val structureFile = withContext(Dispatchers.IO) { resolveStructureFile(context, structureSource) }

                                val finalCharLimit = charLimit.toIntOrNull() ?: 10000
                                val finalStartX = startX.toIntOrNull() ?: 0
                                val finalStartY = startY.toIntOrNull() ?: 0
                                val finalStartZ = startZ.toIntOrNull() ?: 0
                                val finalInnerStep = innerStep.toIntOrNull() ?: 1
                                val finalMiddleStep = middleStep.toIntOrNull() ?: 1
                                val finalOuterStep = outerStep.toIntOrNull() ?: 1
                                val finalStartScoreOffset = startScoreOffset.toIntOrNull() ?: 0
                                val finalGenerationMultiplier = generationMultiplier.toIntOrNull() ?: 1
                                val mappingJson = if (mappingMode == "内置资源") {
    withContext(Dispatchers.IO) {
        context.assets.open("minecraft/block_state_mappings.json").bufferedReader().use { it.readText() }
    }
} else {
    withContext(Dispatchers.IO) {
        val file = File(customMappingPath.trim())
        if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }
}

if (mappingJson.isBlank()) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, "映射规则 JSON 读取失败或为空，请检查路径！", Toast.LENGTH_SHORT).show()
        isGenerating = false
    }
    return@launch
}

val finalSkipBlockIds = skipBlockIdsText.split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val finalSkipMatchType = runCatching { MatchType.valueOf(skipMatchType) }.getOrDefault(MatchType.EXACT)
val finalSkipFilterMode = runCatching { FilterMode.valueOf(skipFilterMode) }.getOrDefault(FilterMode.WHITELIST)

                                val builder = BinaryMcStructureCommandGenerator.Builder(structureFile)
                                    .setScoreboardObj(scoreboardObj.trim())
                                    .setEntityName(entityName.trim())
                                    .setCharLimit(finalCharLimit)
                                    .setStartCoords(finalStartX, finalStartY, finalStartZ)
                                    .setMirrors(mirrorX, mirrorY, mirrorZ)
                                    .setAxisConfig(
                                        innerAxis = Axis3D.valueOf(innerAxis), innerStep = finalInnerStep,
                                        middleAxis = Axis3D.valueOf(middleAxis), middleStep = finalMiddleStep,
                                        outerAxis = Axis3D.valueOf(outerAxis), outerStep = finalOuterStep
                                    )
                                    .setStartScoreOffset(finalStartScoreOffset)
                                    .setForbiddenScores(parseForbiddenScores(forbiddenScoresText))
                                    .setGenerationMultiplier(finalGenerationMultiplier)
                                    .setMappingJsonRules(mappingJson)
                                    .setBedrockTargetVersion(bedrockVersion.trim())
                                    .setSkipConfig(
        blockIds = finalSkipBlockIds,
        matchType = finalSkipMatchType,
        filterMode = finalSkipFilterMode
    )
                                // 【修改点 2】：解析 DSL 文本并动态循环加入 addAssistRule
                                val parsedRules = AssistRuleParser.parse(
                                    text = assistRulesText,
                                    innerAxisStr = innerAxis, innerStepStr = innerStep,
                                    middleAxisStr = middleAxis, middleStepStr = middleStep,
                                    outerAxisStr = outerAxis, outerStepStr = outerStep
                               )
                                parsedRules.forEach { rule ->
                                    builder.addAssistRule(
                                        targetBlockId = rule.targetBlockId,
                                        matchType = rule.matchType,
                                        targetStates = rule.targetStates,
                                        offsetX = rule.offsetX,
                                        offsetY = rule.offsetY,
                                        offsetZ = rule.offsetZ,
                                        assistBlockStr = rule.assistBlockStr,
                                        condition = rule.condition
                                    )
                                }

                                val generator = builder.build()

                                generationStatus = "正在生成命令..."
                                val result = withContext(Dispatchers.Default) {
                                    generator.generate()
                                }

                                if (result.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        generationStatus = "生成失败：结果为空"
                                        Toast.makeText(context, "生成结果为空，请检查结构文件或映射规则", Toast.LENGTH_SHORT).show()
                                        isGenerating = false
                                    }
                                    return@launch
                                }

                                val outFile = withContext(Dispatchers.IO) {
                                    saveGeneratedText(
                                        context = context,
                                        outputType = outputType,
                                        outputPath = outputPath,
                                        outputFileName = outputFileName,
                                        text = result
                                    )
                                }

                                withContext(Dispatchers.Main) {
                                    lastSavedPath = outFile.absolutePath
                                    generationStatus = "已保存：${outFile.name}"
                                    Toast.makeText(context, "已输出到：${outFile.absolutePath}", Toast.LENGTH_LONG).show()
                                    isGenerating = false
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    generationStatus = "生成失败：${e.message ?: "未知错误"}"
                                    Toast.makeText(context, "生成失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                                    isGenerating = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isGenerating
            ) {
                Text(text = if (isGenerating) generationStatus else "开始生成")
            }


            SourceCard(
                structureSource = structureSource,
                onStructureSourceChange = { structureSource = it },
                onPickFile = { pickStructureLauncher.launch(arrayOf("*/*")) },
                displayName = remember(structureSource) {
                    resolveDisplayName(context, structureSource)
                }
            )

            OutputCard(
                outputType = outputType,
                onOutputTypeChange = { outputType = it },
                outputPath = outputPath,
                onOutputPathChange = { outputPath = it },
                outputFileName = outputFileName,
                onOutputFileNameChange = { outputFileName = it }
            )

            GeneralCard(
                scoreboardObj = scoreboardObj,
                onScoreboardObjChange = { scoreboardObj = it },
                entityName = entityName,
                onEntityNameChange = { entityName = it },
                charLimit = charLimit,
                onCharLimitChange = { charLimit = it },
                startX = startX,
                onStartXChange = { startX = it },
                startY = startY,
                onStartYChange = { startY = it },
                startZ = startZ,
                onStartZChange = { startZ = it },
                startScoreOffset = startScoreOffset,
                onStartScoreOffsetChange = { startScoreOffset = it },
                generationMultiplier = generationMultiplier,
                onGenerationMultiplierChange = { generationMultiplier = it },
                forbiddenScoresText = forbiddenScoresText,
                onForbiddenScoresTextChange = { forbiddenScoresText = it }
            )

            AxisCard(
                innerAxis = innerAxis,
                onInnerAxisChange = { innerAxis = it },
                innerStep = innerStep,
                onInnerStepChange = { innerStep = it },
                middleAxis = middleAxis,
                onMiddleAxisChange = { middleAxis = it },
                middleStep = middleStep,
                onMiddleStepChange = { middleStep = it },
                outerAxis = outerAxis,
                onOuterAxisChange = { outerAxis = it },
                outerStep = outerStep,
                onOuterStepChange = { outerStep = it },
                mirrorX = mirrorX,
                onMirrorXChange = { mirrorX = it },
                mirrorY = mirrorY,
                onMirrorYChange = { mirrorY = it },
                mirrorZ = mirrorZ,
                onMirrorZChange = { mirrorZ = it }
            )
            
           SkipConfigCard(
    blockIdsText = skipBlockIdsText,
    onBlockIdsTextChange = { skipBlockIdsText = it },
    matchTypeStr = skipMatchType,
    onMatchTypeChange = { skipMatchType = it },
    filterModeStr = skipFilterMode,
    onFilterModeChange = { skipFilterMode = it }
)

            MappingAndVersionCard(
    mappingMode = mappingMode,
    onMappingModeChange = { mappingMode = it },
    customMappingPath = customMappingPath,
    onCustomMappingPathChange = { customMappingPath = it },
    bedrockVersion = bedrockVersion,
    onBedrockVersionChange = { bedrockVersion = it }
)
            
            AssistRulesCard(
                rulesText = assistRulesText,
                onRulesTextChange = { assistRulesText = it }
            )

           
            // 新增的“重置所有配置”按钮
            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("重置所有配置")
            }
        }
    }

    // 重置配置的对话框
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有设置？") },
            text = { Text("这将重置所有文本框、取消选中的方块，并永久删除所有导入的自定义图片。") },
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
}

@Composable
private fun SourceCard(
    structureSource: String,
    onStructureSourceChange: (String) -> Unit,
    onPickFile: () -> Unit,
    displayName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("文件(.mcstructure/.litematic/.nbt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = structureSource,
                onValueChange = onStructureSourceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("文件路径 / Uri") },
                singleLine = true,
                supportingText = { Text("支持直接输入文件路径，也支持 content:// Uri。") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFile) { Text("选择结构文件") }
                TextButton(onClick = { onStructureSourceChange("") }) { Text("清空") }
            }

            if (displayName.isNotBlank()) {
                Text("当前文件：$displayName", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun GeneralCard(
    scoreboardObj: String,
    onScoreboardObjChange: (String) -> Unit,
    entityName: String,
    onEntityNameChange: (String) -> Unit,
    charLimit: String,
    onCharLimitChange: (String) -> Unit,
    startX: String,
    onStartXChange: (String) -> Unit,
    startY: String,
    onStartYChange: (String) -> Unit,
    startZ: String,
    onStartZChange: (String) -> Unit,
    startScoreOffset: String,
    onStartScoreOffsetChange: (String) -> Unit,
    generationMultiplier: String,
    onGenerationMultiplierChange: (String) -> Unit,
    forbiddenScoresText: String,
    onForbiddenScoresTextChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("基础参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = scoreboardObj,
                    onValueChange = onScoreboardObjChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("计分板") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = entityName,
                    onValueChange = onEntityNameChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("盔甲架名前缀") },
                    singleLine = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = charLimit,
                    onValueChange = onCharLimitChange,
                    label = "字符限制",
                    modifier = Modifier.weight(1f)
                )
                NumberField(
                    value = generationMultiplier,
                    onValueChange = onGenerationMultiplierChange,
                    label = "生成倍数",
                    modifier = Modifier.weight(1f)
                )
                NumberField(
                    value = startScoreOffset,
                    onValueChange = onStartScoreOffsetChange,
                    label = "起始分数偏移",
                    modifier = Modifier.weight(1f)
                )
            }

            Text("起点坐标", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(value = startX, onValueChange = onStartXChange, label = "X", modifier = Modifier.weight(1f))
                NumberField(value = startY, onValueChange = onStartYChange, label = "Y", modifier = Modifier.weight(1f))
                NumberField(value = startZ, onValueChange = onStartZChange, label = "Z", modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = forbiddenScoresText,
                onValueChange = onForbiddenScoresTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("禁用分数列表") },
                singleLine = false,
                minLines = 2,
                maxLines= 5,
                supportingText = { Text("用英文逗号分隔") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisCard(
    innerAxis: String,
    onInnerAxisChange: (String) -> Unit,
    innerStep: String,
    onInnerStepChange: (String) -> Unit,
    middleAxis: String,
    onMiddleAxisChange: (String) -> Unit,
    middleStep: String,
    onMiddleStepChange: (String) -> Unit,
    outerAxis: String,
    onOuterAxisChange: (String) -> Unit,
    outerStep: String,
    onOuterStepChange: (String) -> Unit,
    mirrorX: Boolean,
    onMirrorXChange: (Boolean) -> Unit,
    mirrorY: Boolean,
    onMirrorYChange: (Boolean) -> Unit,
    mirrorZ: Boolean,
    onMirrorZChange: (Boolean) -> Unit
) {
    val axisOptions = remember {
        enumValues<Axis3D>().map { it.name }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("轴与镜像", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            AxisSelectorRow(
                label = "行进轴",
                value = innerAxis,
                options = axisOptions,
                onValueChange = onInnerAxisChange
            )
            NumberField(value = innerStep, onValueChange = onInnerStepChange, label = "行进步长", modifier = Modifier.fillMaxWidth())

            AxisSelectorRow(
                label = "加深轴",
                value = middleAxis,
                options = axisOptions,
                onValueChange = onMiddleAxisChange
            )
            NumberField(value = middleStep, onValueChange = onMiddleStepChange, label = "加深步长", modifier = Modifier.fillMaxWidth())

            AxisSelectorRow(
                label = "最外层轴",
                value = outerAxis,
                options = axisOptions,
                onValueChange = onOuterAxisChange
            )
            NumberField(value = outerStep, onValueChange = onOuterStepChange, label = "外层步长", modifier = Modifier.fillMaxWidth())

            Text("镜像", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                MirrorCheck(label = "X", checked = mirrorX, onCheckedChange = onMirrorXChange)
                MirrorCheck(label = "Y", checked = mirrorY, onCheckedChange = onMirrorYChange)
                MirrorCheck(label = "Z", checked = mirrorZ, onCheckedChange = onMirrorZChange)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputCard(
    outputType: String,
    onOutputTypeChange: (String) -> Unit,
    outputPath: String,
    onOutputPathChange: (String) -> Unit,
    outputFileName: String,
    onOutputFileNameChange: (String) -> Unit
) {
    val options = remember { listOf("内部存储", "外部存储") }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输出设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = outputType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("输出类型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                onOutputTypeChange(item)
                                expanded = false
                            }
                        )
                    }
                }
            }

            if (outputType == "外部存储") {
                OutlinedTextField(
                    value = outputPath,
                    onValueChange = onOutputPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("输出目录") },
                    singleLine = true,
                    supportingText = { Text("会自动保存成 .txt 文件") }
                )
            } else {
                Text(
                    text = "内部存储目录：/storage/emulated/0/Android/data/command.plus/files/McStructure3D/",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = outputFileName,
                onValueChange = onOutputFileNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输出文件名") },
                singleLine = true,
                supportingText = { Text("不需要手动写后缀，最终会保存为 .txt") }
            )
        }
    }
}

@Composable
private fun ActionCard(
    isGenerating: Boolean,
    generationStatus: String,
    lastSavedPath: String,
    onGenerate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onGenerate,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isGenerating) "生成中..." else "生成并保存 txt")
            }

            if (generationStatus.isNotBlank()) {
                Text(text = generationStatus, style = MaterialTheme.typography.bodyMedium)
            }
            if (lastSavedPath.isNotBlank()) {
                Text(text = lastSavedPath, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    // 移除了内部 filter 过滤字符的限制，允许无限制任意输入（如输入负号、小数点、或者临时留空等）
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true
    )
}

// 后面其余的 UI 组件（SourceCard, GeneralCard, AxisCard, MappingTextCard, OutputCard, AxisSelectorRow, MirrorCheck）以及辅助方法均保持不变...

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisSelectorRow(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text("选择轴") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorCheck(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label)
    }
}

private fun parseForbiddenScores(text: String): Set<Int> {
    return text
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()
}

private fun resolveDisplayName(context: Context, source: String): String {
    if (source.isBlank()) return ""
    return try {
        when {
            source.startsWith("content://") -> {
                val uri = Uri.parse(source)
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx) ?: uri.lastPathSegment.orEmpty()
                    } else {
                        uri.lastPathSegment.orEmpty()
                    }
                } ?: Uri.parse(source).lastPathSegment.orEmpty()
            }
            source.startsWith("file://") -> File(Uri.parse(source).path ?: source).name
            else -> File(source).name
        }
    } catch (_: Exception) {
        source
    }
}

@Composable
private fun AssistRulesCard(
    rulesText: String,
    onRulesTextChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("依附辅助方块生成规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            Text(
                text = "语法说明：\n" +
                        "1. [EXACT]或[CONTAINS]开头指定匹配模式\n" +
                        "2. 括号()内填写选填状态，如 (hanging=true)\n" +
                        "3. 偏移可用纯坐标 (0,1,0) 或内部变量 innerAxis 1 / middleAxis -1 / outerAxis 1\n" +
                        "4. 结尾加上分区条件 [SAME] 或 [DIFFERENT]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = rulesText,
                onValueChange = onRulesTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                label = { Text("依附方块规则配置文本") },
                singleLine = false,
                minLines = 10,
                maxLines = 18
            )
        }
    }
}



@Composable
private fun MappingAndVersionCard(
    mappingMode: String,
    onMappingModeChange: (String) -> Unit,
    customMappingPath: String,
    onCustomMappingPathChange: (String) -> Unit,
    bedrockVersion: String,
    onBedrockVersionChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "映射规则与目标版本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // 基岩版目标版本
            OutlinedTextField(
                value = bedrockVersion,
                onValueChange = onBedrockVersionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("基岩版目标版本 ") },
                singleLine = true,
                supportingText = { Text("用于适配对应版本的方块状态结构") }
            )

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // 映射规则模式选择标题
            Text(
                text = "方块状态映射规则 (Java~Bedrock)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 单选按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = mappingMode == "内置资源",
                        onClick = { onMappingModeChange("内置资源") }
                    )
                    Text(
                        text = "内置资源 (Assets)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp) // 简易间距
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = mappingMode == "指定路径",
                        onClick = { onMappingModeChange("指定路径") }
                    )
                    Text(
                        text = "指定外部路径",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // 根据模式动态展示不同的提示或输入框
            AnimatedVisibility(
                visible = mappingMode == "内置资源",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "将自动读取应用自带的资源文件:\n/assets/minecraft/block_state_mappings.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = mappingMode == "指定路径",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                OutlinedTextField(
                    value = customMappingPath,
                    onValueChange = onCustomMappingPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("指定映射规则 JSON 路径") },
                    singleLine = true,
                    supportingText = { Text("请输入绝对路径，并确保应用拥有该文件的读取权限") }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipConfigCard(
    blockIdsText: String,
    onBlockIdsTextChange: (String) -> Unit,
    matchTypeStr: String,
    onMatchTypeChange: (String) -> Unit,
    filterModeStr: String,
    onFilterModeChange: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val matchTypeOptions = remember { enumValues<MatchType>().map { it.name } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(300)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "方块跳过优化配置 (Skip Config)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // 过滤模式：黑名单 / 白名单
            Text(
                text = "过滤模式",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FilterMode.values().forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = filterModeStr == mode.name,
                            onClick = { onFilterModeChange(mode.name) }
                        )
                        Text(
                            text = if (mode == FilterMode.WHITELIST) "白名单 (仅优化)" else "黑名单 (仅不优化)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            // 匹配模式：EXACT / CONTAINS 下拉菜单
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("匹配模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = isExpanded,
                    onExpandedChange = { isExpanded = !isExpanded }
                ) {
                    OutlinedTextField(
                        value = if (matchTypeStr == MatchType.EXACT.name) "EXACT (完全等于)" else "CONTAINS (包含子串)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择匹配算法") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = isExpanded,
                        onDismissRequest = { isExpanded = false }
                    ) {
                        matchTypeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { 
                                    Text(if (option == MatchType.EXACT.name) "EXACT (完全等于)" else "CONTAINS (包含子串)") 
                                },
                                onClick = {
                                    onMatchTypeChange(option)
                                    isExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 方块ID输入文本框
            OutlinedTextField(
                value = blockIdsText,
                onValueChange = onBlockIdsTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("方块 ID 列表") },
                placeholder = { Text("例如: minecraft:stone,minecraft:grass_block") },
                singleLine = false,
                minLines = 2,
                maxLines=15,
                supportingText = { Text("多个方块 ID 请使用英文逗号 , 分隔") }
            )
        }
    }
}

@Throws(IOException::class)
private fun resolveStructureFile(context: Context, source: String): File {
    val trimmed = source.trim()
    require(trimmed.isNotEmpty()) { "结构文件不能为空" }

    return when {
        trimmed.startsWith("content://") -> copyUriToCacheFile(context, Uri.parse(trimmed))
        trimmed.startsWith("file://") -> File(Uri.parse(trimmed).path ?: trimmed)
        else -> File(trimmed)
    }
}

@Throws(IOException::class)
private fun copyUriToCacheFile(context: Context, uri: Uri): File {
    val displayName = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    val suffix = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        ?: ".bin"

    val target = File(context.cacheDir, "mcstructure_${System.currentTimeMillis()}$suffix")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取所选结构文件" }
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
    }
    return target
}

@Throws(IOException::class)
private fun saveGeneratedText(
    context: Context,
    outputType: String,
    outputPath: String,
    outputFileName: String,
    text: String
): File {
    val dir = if (outputType == "内部存储") {
        File(context.getExternalFilesDir(null), "McStructure3D")
    } else {
        File(outputPath.trim())
    }

    if (!dir.exists()) {
        dir.mkdirs()
    }

    val baseName = outputFileName.trim().ifBlank { "McStructure3D" }
    val finalName = if (baseName.endsWith(".txt", ignoreCase = true)) baseName else "$baseName.txt"
    val outFile = File(dir, finalName)
    outFile.writeText(text, Charsets.UTF_8)
    return outFile
}

