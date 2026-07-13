package command.plus

import java.io.File
import java.util.LinkedHashMap

data class BuildParams(
    val content: String,
    val outputFile: File, 
    val limitX: Int = 64,
    val limitZ: Int = 64
)

sealed class BuildResult {
    data class Progress(val percent: Int, val message: String) : BuildResult()
    object Success : BuildResult()
    data class Error(val throwable: Throwable) : BuildResult()
}

object CommandStructureBuilder {

    data class BlockConfig(
        val type: Int, val conditional: Int, val redstone: Int
    )

    private data class LayoutRule(
        val axis: String,      // "X", "Y", "Z", "COUNT"
        val operator: String,  // ">=", "<=", ">", "<", "=="
        val targetValue: Int,
        val faceOverride: String?,
        val immediateStep: Triple<Int, Int, Int>?,
        val nextDefaultStep: Triple<Int, Int, Int>?,
        val nextState: String? // 状态转移目标
    )

    private val ruleRegex = Regex("""#rule\s+(X|Y|Z|COUNT)\s*(>=|<=|>|<|==|=)\s*(-?\d+)\s*->\s*(.*)""", RegexOption.IGNORE_CASE)

    fun build(params: BuildParams, onUpdate: (BuildResult) -> Unit) {
        try {
            onUpdate(BuildResult.Progress(0, "正在初始化局部状态机编译器..."))

            val blocks = mutableListOf<BlockInput>()
            val lines = params.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            
            if (lines.isEmpty()) {
                onUpdate(BuildResult.Error(Exception("输入内容为空")))
                return
            }

            // 状态机运行时坐标与步进变量
            var curX = 0; var curY = 0; var curZ = 0
            var stepX = 0; var stepY = 1; var stepZ = 0 
            var facingMode = "AUTO"
            var currentConfig = BlockConfig(0, 0, 1)
            
            // 核心：控制当前是否处于 $A,B,C 闭合区间内，默认关闭
            var isInZone = false 

            // 局部状态机规则库：State -> List<Rule>
            val stateRulesMap = LinkedHashMap<String, MutableList<LayoutRule>>()
            var currentParsingState = "default" // 当前静态解析的目标状态域
            var currentState = "default"        // 运行期当前的动态状态

            // 核心生命周期隔离标记：用于判断在“当前这串规则定义后”，是否已经开始放置方块
            var hasPlacedBlocksInCurrentScope = false

            lines.forEachIndexed { index, line ->
                when {
                    // ── 规则与状态定义（触发隔离检测） ──
                    line.startsWith("#state") || line.startsWith("#rule") -> {
                        if (hasPlacedBlocksInCurrentScope) {
                            stateRulesMap.clear()
                            currentParsingState = "default"
                            currentState = "default"
                            hasPlacedBlocksInCurrentScope = false 
                        }

                        if (line.startsWith("#state")) {
                            val tokens = line.split("\\s+".toRegex())
                            if (tokens.size >= 2) {
                                currentParsingState = tokens[1]
                            }
                        } else {
                            val match = ruleRegex.matchEntire(line)
                            if (match != null) {
                                val (axis, op, valStr, actionStr) = match.destructured
                                val rule = parseRuleActions(axis.uppercase(), op, valStr.toInt(), actionStr)
                                stateRulesMap.getOrPut(currentParsingState) { mutableListOf() }.add(rule)
                            }
                        }
                    }

                    // ── 基础位置与步进/对齐修改指令 ──
                    line.startsWith("#") -> {
                        val tokens = line.split("\\s+".toRegex())
                        when (tokens[0].lowercase()) {
                            // 【新增核心指令】：#align <轴> <相对偏移> [其他轴=绝对值]
                            "#align" -> {
                                if (tokens.size >= 3) {
                                    val targetAxis = tokens[1].uppercase()
                                    val offset = tokens[2].toInt()
                                    
                                    // 默认将另外两个非目标轴初始化为 0 (绝对值)
                                    var nextX = 0; var nextY = 0; var nextZ = 0
                                    // 标记位：用来记录用户有没有手动覆盖其他轴的绝对值
                                    var hasSetX = false; var hasSetY = false; var hasSetZ = false

                                    // 解析可能存在的后续覆盖参数（如 X=10 或 Z=-5）
                                    for (i in 3 until tokens.size) {
                                        val kv = tokens[i].split("=")
                                        if (kv.size == 2) {
                                            when (kv[0].uppercase()) {
                                                "X" -> { nextX = kv[1].toInt(); hasSetX = true }
                                                "Y" -> { nextY = kv[1].toInt(); hasSetY = true }
                                                "Z" -> { nextZ = kv[1].toInt(); hasSetZ = true }
                                            }
                                        }
                                    }

                                    // 执行混合变轨赋值
                                    when (targetAxis) {
                                        "X" -> {
                                            curX += offset
                                            curY = if (hasSetY) nextY else 0
                                            curZ = if (hasSetZ) nextZ else 0
                                        }
                                        "Y" -> {
                                            curY += offset
                                            curX = if (hasSetX) nextX else 0
                                            curZ = if (hasSetZ) nextZ else 0
                                        }
                                        "Z" -> {
                                            curZ += offset
                                            curX = if (hasSetX) nextX else 0
                                            curY = if (hasSetY) nextY else 0
                                        }
                                    }
                                }
                            }
                            "#step" -> {
                                stepX = tokens.getOrNull(1)?.toInt() ?: stepX
                                stepY = tokens.getOrNull(2)?.toInt() ?: stepY
                                stepZ = tokens.getOrNull(3)?.toInt() ?: stepZ
                            }
                            "#face" -> facingMode = tokens.getOrNull(1)?.uppercase() ?: "AUTO"
                            "#goto" -> {
                                curX = tokens.getOrNull(1)?.toInt() ?: curX
                                curY = tokens.getOrNull(2)?.toInt() ?: curY
                                curZ = tokens.getOrNull(3)?.toInt() ?: curZ
                            }
                            "#shift" -> {
                                curX += tokens.getOrNull(1)?.toInt() ?: 0
                                curY += tokens.getOrNull(2)?.toInt() ?: 0
                                curZ += tokens.getOrNull(3)?.toInt() ?: 0
                            }
                        }
                    }

                    // ── 配置切换与区间闭合校验 ──
                    line.startsWith("$") -> {
                        val content = line.substring(1).trim()
                        if (content.isEmpty()) {
                            isInZone = false
                            currentConfig = BlockConfig(0, 0, 1) 
                        } else {
                            val parts = content.split(",").map { it.trim() }
                            if (parts.size == 3) {
                                try {
                                    currentConfig = BlockConfig(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                                    isInZone = true 
                                } catch (e: NumberFormatException) {
                                    onUpdate(BuildResult.Error(Exception("第 ${index + 1} 行配置参数非合法数字: $line")))
                                    return
                                }
                            } else {
                                isInZone = false
                                onUpdate(BuildResult.Error(Exception("第 ${index + 1} 行配置格式错误（必须为单 \$ 或 \$A,B,C）: $line")))
                                return
                            }
                        }
                    }
                    
                    // ── 运行期：放置方块并执行状态机逻辑 ──
                    line.startsWith("/") -> {
                        if (!isInZone) {
                            return@forEachIndexed
                        }

                        hasPlacedBlocksInCurrentScope = true

                        val nextProjX = curX + stepX
                        val nextProjY = curY + stepY
                        val nextProjZ = curZ + stepZ
                        val currentBlockCount = blocks.size + 1 

                        val activeRules = stateRulesMap[currentState] ?: emptyList<LayoutRule>()
                        var matchedRule: LayoutRule? = null
                        
                        for (rule in activeRules) {
                            val checkVal = when (rule.axis) {
                                "X" -> nextProjX
                                "Y" -> nextProjY
                                "Z" -> nextProjZ
                                "COUNT" -> currentBlockCount
                                else -> 0
                            }
                            if (evalCondition(checkVal, rule.operator, rule.targetValue)) {
                                matchedRule = rule
                                break 
                            }
                        }

                        val finalFacingStr = matchedRule?.faceOverride ?: facingMode
                        val finalFacing = calculateFacing(finalFacingStr, stepX, stepY, stepZ)

                        val blockId = when (currentConfig.type) {
                            0 -> "minecraft:command_block"
                            2 -> "minecraft:repeating_command_block"
                            else -> "minecraft:chain_command_block"
                        }
                        
                        val states = mapOf<String, Any?>(
                            "facing_direction" to finalFacing, 
                            "conditional_bit" to currentConfig.conditional
                        )
                        
                        val blockEntityNbt = mapOf<String, Any?>(
                            "id" to "CommandBlock", 
                            "Command" to line.removePrefix("/"),
                            "auto" to currentConfig.redstone.toByte(), 
                            "conditionMet" to 0.toByte(), 
                            "TrackOutput" to 1.toByte()
                        )
                        
                        blocks.add(BlockInput(curX, curY, curZ, blockId, states, blockEntityNbt))

                        if (matchedRule != null) {
                            val impStep = matchedRule.immediateStep ?: Triple(stepX, stepY, stepZ)
                            curX += impStep.first
                            curY += impStep.second
                            curZ += impStep.third
                            
                            matchedRule.nextDefaultStep?.let {
                                stepX = it.first; stepY = it.second; stepZ = it.third
                            }
                            matchedRule.nextState?.let {
                                currentState = it
                            }
                        } else {
                            curX += stepX; curY += stepY; curZ += stepZ
                        }
                    }
                }
            }

            
            // ── 高效切片与结构导出（建筑紧凑优化版） ──
            if (blocks.isEmpty()) { 
                onUpdate(BuildResult.Error(Exception("未生成任何方块（可能由于所有命令都不在 \$ 区间内而被忽略）")))
                return 
            }
            onUpdate(BuildResult.Progress(80, "正在切片并导出紧凑结构..."))
            val outputDir = if (params.outputFile.isDirectory) params.outputFile else params.outputFile.parentFile ?: File(".")
            outputDir.mkdirs()

            // 1. 按指定的 limitX 和 limitZ 将方块分配到不同的网格切片中
            // 使用 Math.floorDiv 确保负数坐标也能正确分块
            val chunks = LinkedHashMap<String, MutableList<BlockInput>>()
            for (b in blocks) {
                val cx = Math.floorDiv(b.x, params.limitX)
                val cz = Math.floorDiv(b.z, params.limitZ)
                chunks.getOrPut("${cx}_${cz}") { mutableListOf() }.add(b)
            }
            
            chunks.forEach { (key, chunkBlocks) ->
                // 过滤掉完全由填充引入的纯空气块或空切片，只对包含有效实体的切片进行紧凑化处理
                val validBlocks = chunkBlocks.filter { it.id != "minecraft:air" || it.blockEntityNbt != null }
                if (validBlocks.isEmpty()) return@forEach

                val tokens = key.split("_")
                val cx = tokens[0].toInt()
                val cz = tokens[1].toInt()
                
                val outFile = File(outputDir, "command_part_x${cx}_z${cz}.mcstructure")
                
                // 2. 动态计算该切片内部实际方块的紧凑包围盒，不再强行填充 limitX/limitZ 边界的空气
                val chunkMinX = validBlocks.minOf { it.x }
                val chunkMaxX = validBlocks.maxOf { it.x }
                val chunkMinY = validBlocks.minOf { it.y }
                val chunkMaxY = validBlocks.maxOf { it.y }
                val chunkMinZ = validBlocks.minOf { it.z }
                val chunkMaxZ = validBlocks.maxOf { it.z }

                // 3. 构建用于导出的紧凑方块列表
                // 为了保证 McStructureExporter 内部相对坐标计算正常，只需包含包围盒内的实际方块即可
                // 如果您希望保留原建筑中包裹在内部的天然空气，这里直接传入 validBlocks 即可
                val exportBlocks = validBlocks.toMutableList()

                // 确保包围盒的对角线端点在列表中（即使它们是空气），从而正确隐式声明结构体紧凑尺寸
                if (exportBlocks.none { it.x == chunkMinX && it.y == chunkMinY && it.z == chunkMinZ }) {
                    exportBlocks.add(BlockInput(chunkMinX, chunkMinY, chunkMinZ, "minecraft:air", emptyMap(), null))
                }
                if (exportBlocks.none { it.x == chunkMaxX && it.y == chunkMaxY && it.z == chunkMaxZ }) {
                    exportBlocks.add(BlockInput(chunkMaxX, chunkMaxY, chunkMaxZ, "minecraft:air", emptyMap(), null))
                }
                
                // 4. 调用导出
                McStructureExporter.export(exportBlocks, outFile)
            }
            onUpdate(BuildResult.Progress(100, "构建成功！已全部导出紧凑结构。"))
            onUpdate(BuildResult.Success)
        } catch (e: Exception) { 
            onUpdate(BuildResult.Error(e)) 
        }
    }

    // （evalCondition, calculateFacing, parseRuleActions 保持不变...）
    private fun evalCondition(current: Int, op: String, target: Int): Boolean = when (op) {
        ">=" -> current >= target; "<=" -> current <= target; ">" -> current > target; "<" -> current < target
        "=", "==" -> current == target; else -> false
    }

    private fun calculateFacing(mode: String, dx: Int, dy: Int, dz: Int): Int = when (mode) {
        "DOWN" -> 0; "UP" -> 1; "NORTH" -> 2; "SOUTH" -> 3; "WEST" -> 4; "EAST" -> 5
        "AUTO" -> when {
            dy > 0 -> 1; dy < 0 -> 0; dz < 0 -> 2; dz > 0 -> 3; dx < 0 -> 4; dx > 0 -> 5; else -> 1
        }
        else -> 1
    }

    private fun parseRuleActions(axis: String, op: String, target: Int, actionStr: String): LayoutRule {
        var face: String? = null; var immediate: Triple<Int, Int, Int>? = null
        var nextStep: Triple<Int, Int, Int>? = null; var nextState: String? = null
        actionStr.split("\\s+".toRegex()).forEach { token ->
            val kv = token.split(":")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "face" -> face = kv[1].uppercase()
                    "step" -> { val xyz = kv[1].split(",").map { it.toInt() }; immediate = Triple(xyz[0], xyz[1], xyz[2]) }
                    "next" -> { val xyz = kv[1].split(",").map { it.toInt() }; nextStep = Triple(xyz[0], xyz[1], xyz[2]) }
                    "state" -> nextState = kv[1] 
                }
            }
        }
        return LayoutRule(axis, op, target, face, immediate, nextStep, nextState)
    }
}