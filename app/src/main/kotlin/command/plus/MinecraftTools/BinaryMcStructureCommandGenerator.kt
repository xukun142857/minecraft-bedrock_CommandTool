package command.plus

import java.io.File
import java.util.Locale
import java.util.TreeSet
import java.util.HashMap

// ============================================================
//  辅助方块相关枚举与规则定义
// ============================================================

enum class Axis3D { X, Y, Z }

enum class FilterMode { WHITELIST, BLACKLIST }

data class SkipConfig(
val blockIds: List<String>,       // 批量传入的基岩版方块ID组
val matchType: MatchType,         // 匹配模式：全匹配 或 包含匹配
val filterMode: FilterMode        // 过滤模式：白名单 或 黑名单
)

internal data class AppliedBlock(
val id: String,
val states: Map<String, Any?>,
val assists: List<AssistSpec>
)

interface MatchContext {
fun isTargetInOtherPartition(dx: Int, dy: Int, dz: Int): Boolean
}

/**

方块ID的匹配模式
*/
enum class MatchType {
EXACT,      // 全匹配（完全等于给定的ID）
CONTAINS    // 包含匹配（给定字符串是方块ID的子串）
}


/**

盔甲架管理分区的判定条件
*/
enum class PartitionCondition {
ANY,                // 总是放置
SAME_PARTITION,     // 仅在当前被辅助方块属于“当前盔甲架管理的分区”时放置
DIFFERENT_PARTITION // 仅在当前被辅助方块属于“其他盔甲架管理的分区”时放置
}


/**

依附辅助方块的匹配与放置规则
*/
data class AssistRule(
val targetBlockId: String,                    // 被辅助方块ID关键字或全称
val matchType: MatchType,                     // 匹配模式：全匹配 或 包含匹配
val targetStates: Map<String, Any?>?,         // 被辅助方块状态（null表示不校验状态，仅匹配ID）
val offsetX: Int,                             // 辅助方块相对被辅助方块的 X 偏移
val offsetY: Int,                             // 辅助方块相对被辅助方块的 Y 偏移
val offsetZ: Int,                             // 辅助方块相对被辅助方块的 Z 偏移
val assistBlockStr: String,                   // 辅助方块完整标示（如 "minecraft:stone" 或带状态字符串）
val placementCondition: PartitionCondition    // 盔甲架分区放置条件
)


data class AssistSpec(
val blockId: String,
val dx: Int,
val dy: Int,
val dz: Int
)

// ============================================================
//  主生成器类
// ============================================================

class BinaryMcStructureCommandGenerator private constructor(
private val structureFile: File,
private val scoreboardObj: String,
private val entityName: String,
private val charLimit: Int,
private val startX: Int,
private val startY: Int,
private val startZ: Int,
private val mirrorX: Boolean,
private val mirrorY: Boolean,
private val mirrorZ: Boolean,
private val innerAxis: Axis3D,
private val innerStep: Int,
private val middleAxis: Axis3D,
private val middleStep: Int,
private val outerAxis: Axis3D,
private val outerStep: Int,
private val startScoreOffset: Int,
private val forbiddenScores: Set<Int>,
private val generationMultiplier: Int,
private val mappingJsonRules: String,
private val bedrockTargetVersion: String,
private val assistRules: List<AssistRule>,
private val skipConfig: SkipConfig?
) {

private data class RangeResult(    
    var selectorPart: String,    
    val corrections: MutableList<Int> = mutableListOf()    
)    

private data class SolidBlock(    
    var score: Int,    
    val partition: Int,    
    val i: Int,    
    val m: Int,    
    val o: Int,    
    var id: String,    
    var states: Map<String, Any?>,    
    var assists: List<AssistSpec>,    
    var extraId: String = "minecraft:air",   
    var isRepeatHead: Boolean = false,    
    var isRepeatTail: Boolean = false,    
    var isRepeatIntermediate: Boolean = false    
)    

// 修改：加入 blockStr 保存原始方块标示，以便后续二分投射调用  
private data class RepeatRunInfo(    
    val headScore: Int,    
    val tailScore: Int,  
    val blockStr: String  
)    

private val mappingEngine: MappingDatabase by lazy {    
    MappingDatabase.fromString(mappingJsonRules)    
}    

private val targetVersionTuple: Triple<Int, Int, Int> by lazy {    
    Utils.versionTuple(bedrockTargetVersion)    
}    

private object LitematicParser {    
    fun isLitematic(file: File): Boolean = file.extension.lowercase(Locale.ROOT) == "litematic"    
    fun parse(file: File): LitematicResult {    
        val litematicFile = command.plus.LitematicParser.parse(file)    
        return object : LitematicResult {    
            override val regions: Map<String, Region> = litematicFile.regions.mapValues { (_, r) ->    
                object : Region {    
                    override val minWorldX: Int get() = r.minWorldX    
                    override val minWorldY: Int get() = r.minWorldY    
                    override val minWorldZ: Int get() = r.minWorldZ    
                    override val maxWorldX: Int get() = r.maxWorldX    
                    override val maxWorldY: Int get() = r.maxWorldY    
                    override val maxWorldZ: Int get() = r.maxWorldZ    
                    override fun getBlockAtWorld(x: Int, y: Int, z: Int): BlockRef? {    
                        val block = r.getBlockAtWorld(x, y, z) ?: return null    
                        return object : BlockRef {    
                            override val state: StateRef = object : StateRef {    
                                override val name: String get() = block.state.name    
                                override val properties: Map<String, Any?> get() = block.state.properties    
                            }    
                        }    
                    }    
                }    
            }    
        }    
    }    
}    
    
private interface LitematicResult { val regions: Map<String, Region> }    
private interface Region { val minWorldX: Int; val minWorldY: Int; val minWorldZ: Int; val maxWorldX: Int; val maxWorldY: Int; val maxWorldZ: Int; fun getBlockAtWorld(x: Int, y: Int, z: Int): BlockRef? }    
private interface BlockRef { val state: StateRef }; private interface StateRef { val name: String; val properties: Map<String, Any?> }    

private object McStructureExporter {    
    fun load(file: File): command.plus.LoadedStructure {    
        return command.plus.McStructureExporter.load(file)    
    }    
    fun getBlockAt(struct: command.plus.LoadedStructure, x: Int, y: Int, z: Int): GenericBlock? {    
        val block = command.plus.McStructureExporter.getBlockAt(struct, x, y, z) ?: return null    
        return GenericBlock(block.id, block.states, block.extraId)   
    }    
}    
    
private object JavaNbtStructureParser {    
    fun isJavaNbt(file: File): Boolean {    
        val ext = file.extension.lowercase(Locale.ROOT)    
        return ext == "nbt"    
    }    
    fun parse(file: File): StructureFile {    
        return JavaStructureNbtParser.parse(file)    
    }    
}    

private fun loadStructure(file: File): GenericStructure {    
    if (LitematicParser.isLitematic(file)) {    
        val litematic = LitematicParser.parse(file)    
        val regions = litematic.regions.values    
        if (regions.isEmpty()) {    
            return object : GenericStructure {    
                override val width = 0; override val height = 0; override val depth = 0    
                override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? = null    
            }    
        }    
        val minX = regions.minOf { it.minWorldX }; val minY = regions.minOf { it.minWorldY }; val minZ = regions.minOf { it.minWorldZ }    
        val maxX = regions.maxOf { it.maxWorldX }; val maxY = regions.maxOf { it.maxWorldY }; val maxZ = regions.maxOf { it.maxWorldZ }    
        return object : GenericStructure {    
            override val width = maxX - minX + 1; override val height = maxY - minY + 1; override val depth = maxZ - minZ + 1    
            override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {    
                for (r in regions) {    
                    val blockRef = r.getBlockAtWorld(minX + x, minY + y, minZ + z) ?: continue    
                    val convertedStates = blockRef.state.properties.mapValues { (_, v) -> convertDynamicValue(v) }    
                    val isWaterlogged = convertedStates["waterlogged"] == true || convertedStates["waterlogged"] == "true"    
                    val extraId = if (isWaterlogged) "minecraft:water" else "minecraft:air"    
                    return GenericBlock(blockRef.state.name, convertedStates, extraId)    
                }    
                return null    
            }    
        }    
    } else if (JavaNbtStructureParser.isJavaNbt(file)) {    
        val structFile = JavaNbtStructureParser.parse(file)    
        val size = structFile.size ?: Vec3i(0, 0, 0)    
        val blockMap = HashMap<Long, StructureBlock>()    
        for (block in structFile.blocks) {    
            val key = (block.pos.x.toLong() shl 40) or (block.pos.y.toLong() shl 20) or block.pos.z.toLong()    
            blockMap[key] = block    
        }    
        return object : GenericStructure {    
            override val width = size.x; override val height = size.y; override val depth = size.z    
            override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {    
                val key = (x.toLong() shl 40) or (y.toLong() shl 20) or z.toLong()    
                val block = blockMap[key] ?: return null    
                val palette = block.palette ?: return null    
                val convertedStates = palette.properties.mapValues { (_, v) -> convertDynamicValue(v) }    
                val isWaterlogged = convertedStates["waterlogged"] == true || convertedStates["waterlogged"] == "true"    
                val extraId = if (isWaterlogged) "minecraft:water" else "minecraft:air"    
                return GenericBlock(palette.name, convertedStates, extraId)    
            }    
        }    
    } else {    
        val loaded = McStructureExporter.load(file)    
        return object : GenericStructure {    
            override val width = loaded.width; override val height = loaded.height; override val depth = loaded.depth    
            override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {    
                return McStructureExporter.getBlockAt(loaded, x, y, z)    
            }    
        }    
    }    
}    

private fun convertDynamicValue(v: Any?): Any? {    
    if (v == null) return null    
    val s = v.toString()    
    return when {    
        s.equals("true", ignoreCase = true) -> true    
        s.equals("false", ignoreCase = true) -> false    
        s.toIntOrNull() != null -> s.toInt()    
        s.toLongOrNull() != null -> s.toLong()    
        s.toDoubleOrNull() != null -> { val d = s.toDouble(); if (d == d.toLong().toDouble()) d.toLong() else d }    
        else -> s    
    }    
}    

private fun adaptToBedrock(id: String, states: Map<String, Any?>, ctx: MatchContext): AppliedBlock {    
    val converted = mappingEngine.convertJavaToBedrock(id, states, targetVersionTuple) ?: Pair(id, states)    
    return AppliedBlock(id = converted.first, states = converted.second, assists = emptyList())    
}    

private fun getStructureCoords(iVal: Int, mVal: Int, oVal: Int, w: Int, h: Int, d: Int): Triple<Int, Int, Int> {    
    var lx = 0; var ly = 0; var lz = 0    
    when (innerAxis) { Axis3D.X -> lx = iVal; Axis3D.Y -> ly = iVal; Axis3D.Z -> lz = iVal }    
    when (middleAxis) { Axis3D.X -> lx = mVal; Axis3D.Y -> ly = mVal; Axis3D.Z -> lz = mVal }    
    when (outerAxis) { Axis3D.X -> lx = oVal; Axis3D.Y -> ly = oVal; Axis3D.Z -> lz = oVal }    
    val ax = if (mirrorX) w - 1 - lx else lx    
    val ay = if (mirrorY) h - 1 - ly else ly    
    val az = if (mirrorZ) d - 1 - lz else lz    
    return Triple(ax, ay, az)    
}    

private fun getAxisLength(axis: Axis3D, w: Int, h: Int, d: Int): Int = when (axis) {    
    Axis3D.X -> w; Axis3D.Y -> h; Axis3D.Z -> d    
}    
    
private fun matchesStates(actual: Map<String, Any?>, expected: Map<String, Any?>?): Boolean {    
    if (expected == null) return true    
    for ((k, v) in expected) {    
        val key = Utils.normalizeKey(k) ?: continue    
        val actualValue = actual[key] ?: actual[k] ?: actual[k.lowercase(Locale.ROOT)]    
        if (actualValue == null) return false    
        if (Utils.normalizeValue(actualValue) != Utils.normalizeValue(v)) return false    
    }    
    return true    
}    

private fun isBlockAllowed(id: String): Boolean {    
    val config = skipConfig ?: return true    
    val matches = config.blockIds.any { token ->    
        if (config.matchType == MatchType.EXACT) id == token || id.substringAfter(":") == token    
        else id.contains(token)    
    }    
    return if (config.filterMode == FilterMode.WHITELIST) matches else !matches    
}    
    
private fun canUseSkipOptimization(block: SolidBlock): Boolean {    
    if (!isBlockAllowed(block.id)) return false    
    if (block.assists.isNotEmpty()) return false    
    return block.extraId == "minecraft:air" || block.extraId == "air"  
}  

fun generate(): String {    
    return try {    
        val structure = loadStructure(structureFile)    
        if (structure.width == 0 || structure.height == 0 || structure.depth == 0) return ""    

        val w = structure.width; val h = structure.height; val d = structure.depth    
        val lenInner = getAxisLength(innerAxis, w, h, d)    
        val lenMiddle = getAxisLength(middleAxis, w, h, d)    
        val lenOuter = getAxisLength(outerAxis, w, h, d)    
        val totalBlocks = lenInner * lenMiddle * lenOuter    

        val tempSolidBlocks = mutableListOf<SolidBlock>()    

        for (o in 0 until lenOuter) {    
            for (m in 0 until lenMiddle) {    
                for (i in 0 until lenInner) {    
                    val (ax, ay, az) = getStructureCoords(i, m, o, w, h, d)    
                    val block = structure.getBlockAt(ax, ay, az)    
                    if (block != null) {    
                        val isMainAir = block.id == "minecraft:air" || block.id == "air"    
                        val isExtraAir = block.extraId == "minecraft:air" || block.extraId == "air"    
                        if (!isMainAir || !isExtraAir) {    
                            tempSolidBlocks.add(    
                                SolidBlock(score = 0, partition = 0, i = i, m = m, o = o, id = "", states = emptyMap(), assists = emptyList(), extraId = "minecraft:air")    
                            )    
                        }    
                    }    
                }    
            }  
        }  

        if (tempSolidBlocks.isEmpty()) return ""    

        val actualSolidCount = tempSolidBlocks.size    
        val actualCount = minOf(generationMultiplier.coerceAtLeast(1), actualSolidCount.coerceAtLeast(1))    
        val avg = actualSolidCount / actualCount    
        val remainder = actualSolidCount % actualCount    

        var currentIdx = 0    
        for (p in 0 until actualCount) {    
            val size = if (p < remainder) avg + 1 else avg    
            for (s in 0 until size) {    
                if (currentIdx < tempSolidBlocks.size) {    
                    tempSolidBlocks[currentIdx] = tempSolidBlocks[currentIdx].copy(partition = p)    
                    currentIdx++    
                }    
            }    
        }    

        var virtualIdx = 0    
        val totalCells = lenOuter * lenMiddle * lenInner    
        val cellAvg = totalCells / actualCount    
        val cellRemainder = totalCells % actualCount    
            
        val cellPartitionMap = HashMap<Long, Int>(totalCells)    
        for (p in 0 until actualCount) {    
            val size = if (p < cellRemainder) cellAvg + 1 else cellAvg    
            for (s in 0 until size) {    
                val tO = virtualIdx / (lenInner * lenMiddle)    
                val rem = virtualIdx % (lenInner * lenMiddle)    
                val tM = rem / lenInner    
                val tI = rem % lenInner    
                val key = (tO.toLong() shl 32) or (tM.toLong() shl 16) or tI.toLong()    
                cellPartitionMap[key] = p    
                virtualIdx++    
            }    
        }    
            
        for (sb in tempSolidBlocks) {    
            val key = (sb.o.toLong() shl 32) or (sb.m.toLong() shl 16) or sb.i.toLong()    
            cellPartitionMap[key] = sb.partition    
        }    

        val finalSolidBlocks = mutableListOf<SolidBlock>()    
        for (sb in tempSolidBlocks) {    
            val i = sb.i; val m = sb.m; val o = sb.o    
            val currentPartition = sb.partition    
            val (ax, ay, az) = getStructureCoords(i, m, o, w, h, d)    
            val block = structure.getBlockAt(ax, ay, az)!!    

            val ctx = object : MatchContext {    
                override fun isTargetInOtherPartition(dx: Int, dy: Int, dz: Int): Boolean {    
                    val sourceWorldX = calcWorldCoord(startX.toLong(), Axis3D.X, innerAxis, i, innerStep, middleAxis, m, middleStep, outerAxis, o, outerStep)    
                    val sourceWorldY = calcWorldCoord(startY.toLong(), Axis3D.Y, innerAxis, i, innerStep, middleAxis, m, middleStep, outerAxis, o, outerStep)    
                    val sourceWorldZ = calcWorldCoord(startZ.toLong(), Axis3D.Z, innerAxis, i, innerStep, middleAxis, m, middleStep, outerAxis, o, outerStep)    
                        
                    val tx = sourceWorldX + dx    
                    val ty = sourceWorldY + dy    
                    val tz = sourceWorldZ + dz    
                        
                    fun axisValue(axis: Axis3D, x: Long, y: Long, z: Long): Long = when (axis) { Axis3D.X -> x; Axis3D.Y -> y; Axis3D.Z -> z }    
                    fun axisPos(target: Long, start: Long, step: Int, len: Int): Int? {    
                        if (step == 0) return null    
                        val delta = target - start; val stepL = step.toLong()    
                        if (delta % stepL != 0L) return null    
                        val pos = (delta / stepL).toInt()    
                        return if (pos in 0 until len) pos else null    
                    }    
                        
                    val tI = axisPos(axisValue(innerAxis, tx, ty, tz), axisStart(innerAxis), innerStep, lenInner) ?: return false    
                    val tM = axisPos(axisValue(middleAxis, tx, ty, tz), axisStart(middleAxis), middleStep, lenMiddle) ?: return false    
                    val tO = axisPos(axisValue(outerAxis, tx, ty, tz), axisStart(outerAxis), outerStep, lenOuter) ?: return false    
                        
                    val targetKey = (tO.toLong() shl 32) or (tM.toLong() shl 16) or tI.toLong()    
                    val targetPartition = cellPartitionMap[targetKey] ?: return false    
                    return targetPartition != currentPartition    
                }    
            }    

            val applied = adaptToBedrock(block.id, block.states, ctx)    
            val isWaterlogged = applied.states["waterlogged"] == true || applied.states["waterlogged"] == "true" || block.extraId == "minecraft:water" || block.extraId == "water"    
            val finalExtraId = if (isWaterlogged) "minecraft:water" else "minecraft:air"    
            val cleanedStates = applied.states.filterKeys { it.lowercase(Locale.ROOT) != "waterlogged" }    

            val calculatedAssists = mutableListOf<AssistSpec>()    
            for (rule in assistRules) {    
                val idMatches = when (rule.matchType) {    
                    MatchType.EXACT -> applied.id == rule.targetBlockId    
                    MatchType.CONTAINS -> applied.id.contains(rule.targetBlockId)    
                }    
                if (idMatches) {    
                    val statesMatch = matchesStates(cleanedStates, rule.targetStates)    
                    if (statesMatch) {    
                        val isOther = ctx.isTargetInOtherPartition(rule.offsetX, rule.offsetY, rule.offsetZ)    
                        val shouldPlace = when (rule.placementCondition) {    
                            PartitionCondition.ANY -> true    
                            PartitionCondition.SAME_PARTITION -> !isOther    
                            PartitionCondition.DIFFERENT_PARTITION -> isOther    
                        }    
                        if (shouldPlace) {    
                            calculatedAssists.add(AssistSpec(blockId = rule.assistBlockStr, dx = rule.offsetX, dy = rule.offsetY, dz = rule.offsetZ))    
                        }    
                    }    
                }    
            }    
                
            finalSolidBlocks.add(    
                SolidBlock(score = 0, partition = currentPartition, i = i, m = m, o = o, id = applied.id, states = cleanedStates, assists = calculatedAssists, extraId = finalExtraId)    
            )    
        }    

        // 修改点：扫描并标记同一行进轴内的连续重复串方块，现在允许两个或一个方块的长度  
        val rowGroups = finalSolidBlocks.groupBy { Triple(it.partition, it.o, it.m) }  
        for ((_, rowBlocks) in rowGroups) {  
            val sortedRow = rowBlocks.sortedBy { it.i }  
            var idx = 0  
            while (idx < sortedRow.size) {  
                val startBlock = sortedRow[idx]  
                  
                if (!canUseSkipOptimization(startBlock)) {  
                    idx++  
                    continue  
                }  
                  
                var endIdx = idx    
                while (endIdx + 1 < sortedRow.size &&    
                       sortedRow[endIdx + 1].i == sortedRow[endIdx].i + 1 &&    
                       sortedRow[endIdx + 1].id == startBlock.id &&    
                       sortedRow[endIdx + 1].states == startBlock.states &&    
                       sortedRow[endIdx + 1].extraId == startBlock.extraId &&    
                       sortedRow[endIdx + 1].assists == startBlock.assists &&    
                       canUseSkipOptimization(sortedRow[endIdx + 1])) {    
                    endIdx++  
                }  
                val runLength = endIdx - idx + 1  
                if (runLength >= 1) { // 满足一个及以上的连续重复串条件  
                    startBlock.isRepeatHead = true  
                    sortedRow[endIdx].isRepeatTail = true  
                    for (k in idx + 1 until endIdx) {  
                        sortedRow[k].isRepeatIntermediate = true  
                    }  
                    idx = endIdx + 1  
                } else {  
                    idx++  
                }  
            }  
        }  

        val filteredSolidBlocks = finalSolidBlocks.filter { !it.isRepeatIntermediate }.toMutableList()    
        assignGlobalScores(filteredSolidBlocks, lenInner, lenMiddle, lenOuter)    

        val partitionRepeatRuns = mutableMapOf<Int, MutableList<RepeatRunInfo>>()    
        val refGrouped = filteredSolidBlocks.groupBy { Triple(it.partition, it.o, it.m) }    
        for ((key, rowBlocks) in refGrouped) {    
            val sortedRow = rowBlocks.sortedBy { it.i }    
            var iScan = 0    
            while (iScan < sortedRow.size) {    
                val b = sortedRow[iScan]    
                if (b.isRepeatHead) {    
                    // 找到该 head 对应的 tail  
                    var tailScan = iScan  
                    while (tailScan < sortedRow.size && !sortedRow[tailScan].isRepeatTail) {  
                        tailScan++  
                    }  
                    if (tailScan < sortedRow.size) {  
                        val tailB = sortedRow[tailScan]  
                        val statesStr = blockStatesToString(b.states)  
                        val fullBlockStr = if (statesStr.isEmpty()) b.id else "${b.id} $statesStr"  
                          
                        partitionRepeatRuns.getOrPut(key.first) { mutableListOf() }.add(    
                            RepeatRunInfo(b.score, tailB.score, fullBlockStr)    
                        )    
                        iScan = tailScan + 1    
                        continue    
                    }  
                }    
                iScan++    
            }    
        }    

        return generateCommands(filteredSolidBlocks, lenInner, lenMiddle, lenOuter, totalBlocks, partitionRepeatRuns).joinToString("\n")    
    } catch (e: Exception) {    
        e.printStackTrace()    
        "### ${e::class.simpleName}: ${e.message} ###"    
    }    
}    

private fun assignGlobalScores(solidBlocks: MutableList<SolidBlock>, lenInner: Int, lenMiddle: Int, lenOuter: Int) {    
    if (solidBlocks.isEmpty()) return    
    var tickScore = startScoreOffset + 1    
    solidBlocks[0].score = tickScore    

    for (idx in 0 until solidBlocks.size - 1) {    
        val current = solidBlocks[idx]    
        val next = solidBlocks[idx + 1]    

        // 用数学公式代替原有的 while 循环步进模拟
        var deltaScore = 0
        if (next.o > current.o) {
            deltaScore += (next.o - current.o) // 外轴跳跃层数
            deltaScore += next.m               // 中轴重置后推进到对应位置
            if (next.i > 0) deltaScore += 1    // 内轴推进
        } else if (next.m > current.m) {
            deltaScore += (next.m - current.m) // 中轴推进
            if (next.i > 0) deltaScore += 1    // 内轴推进
        } else {
            if (next.i > current.i) deltaScore += 1 // 仅内轴推进
        }

        tickScore = current.score + deltaScore
        next.score = tickScore    
    }  
}

private fun scoreAt(globalIndex: Int): Int = startScoreOffset + globalIndex + 1  
private fun nextScore(current: Int): Int = current + 1  

private fun getWorldDelta(deltaI: Int, deltaM: Int, deltaO: Int): Triple<Int, Int, Int> {    
    var dx = 0; var dy = 0; var dz = 0    
    when (innerAxis) { Axis3D.X -> dx += deltaI * innerStep; Axis3D.Y -> dy += deltaI * innerStep; Axis3D.Z -> dz += deltaI * innerStep }    
    when (middleAxis) { Axis3D.X -> dx += deltaM * middleStep; Axis3D.Y -> dy += deltaM * middleStep; Axis3D.Z -> dz += deltaM * middleStep }    
    when (outerAxis) { Axis3D.X -> dx += deltaO * outerStep; Axis3D.Y -> dy += deltaO * outerStep; Axis3D.Z -> dz += deltaO * outerStep }    
    return Triple(dx, dy, dz)    
}    

private fun generateCommands(    
    solidBlocks: List<SolidBlock>,     
    lenInner: Int,     
    lenMiddle: Int,     
    lenOuter: Int,     
    totalBlocks: Int,    
    partitionRepeatRuns: Map<Int, List<RepeatRunInfo>>    
): List<String> {    
    val cmds = mutableListOf<String>()    
    val actualCount = minOf(generationMultiplier.coerceAtLeast(1), totalBlocks.coerceAtLeast(1))    
    val names = List(actualCount) { if (it == 0) entityName else "$entityName$it" }    


    cmds.add("### 初始化指令 ###")    
    cmds.add("$0,0,0")    
    cmds.add("/tickingarea remove_all")    
    cmds.add("$")    
    cmds.add("$1,0,1")    

    var endX = startX; var endZ = startZ    
    if (innerAxis == Axis3D.X) endX += (lenInner - 1) * innerStep    
    if (middleAxis == Axis3D.X) endX += (lenMiddle - 1) * middleStep    
    if (outerAxis == Axis3D.X) endX += (lenOuter - 1) * outerStep    
    if (innerAxis == Axis3D.Z) endZ += (lenInner - 1) * innerStep    
    if (middleAxis == Axis3D.Z) endZ += (lenMiddle - 1) * middleStep    
    if (outerAxis == Axis3D.Z) endZ += (lenOuter - 1) * outerStep    

    val minX = minOf(startX, endX); val maxX = maxOf(startX, endX)    
    val minZ = minOf(startZ, endZ); val maxZ = maxOf(startZ, endZ)    

    for (sx in minX..maxX step 160) {    
        for (sz in minZ..maxZ step 160) {    
            val ex = minOf(sx + 159, maxX); val ez = minOf(sz + 159, maxZ)    
            cmds.add("/tickingarea add $sx 0 $sz $ex 0 $ez")    
        }    
    }    
    cmds.add("$")    
    cmds.add("$0,0,0")    
    cmds.add("/scoreboard objectives add $scoreboardObj dummy")    
    cmds.add("$")    

    val blockMap = mutableMapOf<String, MutableList<Int>>()    
    val waterMap = mutableMapOf<String, MutableList<Int>>()   
    val assistMap = mutableMapOf<AssistSpec, MutableList<Int>>()    
    val lineBreaks = mutableMapOf<String, MutableList<Int>>()    
    val binaryTPs = mutableMapOf<Int, MutableList<Int>>()    
    val killScores = mutableListOf<Int>()    

    fun getCoordStringLocal(axis: Axis3D, stepM: Int, stepO: Int, isCrossLayer: Boolean): String {    
        val startVal = when (axis) { Axis3D.X -> startX; Axis3D.Y -> startY; Axis3D.Z -> startZ }    
        return when (axis) {    
            innerAxis -> startVal.toString()    
            middleAxis -> if (isCrossLayer) startVal.toString() else (stepM * middleStep).let { if (it == 0) "~" else "~$it" }    
            outerAxis -> (stepO * outerStep).let { if (it == 0) "~" else "~$it" }    
            else -> "~"    
        }    
    }    
    // 提前计算外轴和中轴移动的常量字符串
val outerTpX = getCoordStringLocal(Axis3D.X, stepM = 0, stepO = 1, isCrossLayer = true)
val outerTpY = getCoordStringLocal(Axis3D.Y, stepM = 0, stepO = 1, isCrossLayer = true)
val outerTpZ = getCoordStringLocal(Axis3D.Z, stepM = 0, stepO = 1, isCrossLayer = true)
val outerKey = "$outerTpX $outerTpY $outerTpZ"

val middleTpX = getCoordStringLocal(Axis3D.X, stepM = 1, stepO = 0, isCrossLayer = false)
val middleTpY = getCoordStringLocal(Axis3D.Y, stepM = 1, stepO = 0, isCrossLayer = false)
val middleTpZ = getCoordStringLocal(Axis3D.Z, stepM = 1, stepO = 0, isCrossLayer = false)
val middleKey = "$middleTpX $middleTpY $middleTpZ"

    cmds.add("$1,0,1")    
    
    // 在进入分区遍历前，一次性按 partition 分组
val blocksByPartition = solidBlocks.groupBy { it.partition }

for (p in 0 until actualCount) {    
    val pBlocks = blocksByPartition[p] ?: continue // O(1) 获取当前分区的方块
    if (pBlocks.isEmpty()) continue

        val first = pBlocks.first()    
        var currentI = first.i; var currentM = first.m; var currentO = first.o    
        var tickScore = first.score    

        for (idx in 0 until pBlocks.size - 1) {    
            val current = pBlocks[idx]; val next = pBlocks[idx + 1]    
            tickScore = current.score    

            while (currentO < next.o) {    
    lineBreaks.getOrPut(outerKey) { mutableListOf() }.add(tickScore) // 直接使用 outerKey
    currentO++; currentM = 0; currentI = 0; tickScore = nextScore(tickScore)    
}    

while (currentM < next.m) {    
    lineBreaks.getOrPut(middleKey) { mutableListOf() }.add(tickScore) // 直接使用 middleKey
    currentM++; currentI = 0; tickScore = nextScore(tickScore)    
}

            val innerDistance = next.i - currentI    
            if (innerDistance > 0) {    
                var remaining = innerDistance; var power = 1    
                while (remaining > 0) {    
                    if (remaining and 1 == 1) { binaryTPs.getOrPut(power) { mutableListOf() }.add(tickScore) }    
                    power = power shl 1; remaining = remaining shr 1    
                }    
                tickScore = nextScore(tickScore)    
            }    
            currentI = next.i    
        }    

        val currentName = names[p]    
        var fx = startX.toLong(); var fy = startY.toLong(); var fz = startZ.toLong()    
        when (innerAxis) { Axis3D.X -> fx += first.i * innerStep; Axis3D.Y -> fy += first.i * innerStep; Axis3D.Z -> fz += first.i * innerStep }    
        when (middleAxis) { Axis3D.X -> fx += first.m * middleStep; Axis3D.Y -> fy += first.m * middleStep; Axis3D.Z -> fz += first.m * middleStep }    
        when (outerAxis) { Axis3D.X -> fx += first.o * outerStep; Axis3D.Y -> fy += first.o * outerStep; Axis3D.Z -> fz += first.o * outerStep }    

        cmds.add("/summon armor_stand $fx $fy $fz ~ ~ . $currentName")    
        cmds.add("/execute as @e[name=\"$currentName\"] run scoreboard players set @s $scoreboardObj ${first.score}")    
        killScores.add(pBlocks.last().score)    

        for (current in pBlocks) {    
            if (current.isRepeatHead) continue
    if (current.isRepeatIntermediate) continue
    if (current.isRepeatTail) continue

            if (current.id != "minecraft:air" && current.id != "air") {    
                val statesStr = blockStatesToString(current.states)    
                val fullBlock = if (statesStr.isEmpty()) current.id else "${current.id} $statesStr"    
                blockMap.getOrPut(fullBlock) { mutableListOf() }.add(current.score)    
            }    
                
            if (current.extraId != "minecraft:air" && current.extraId != "air") {    
                waterMap.getOrPut(current.extraId) { mutableListOf() }.add(current.score)    
            }    
                
            current.assists.forEach { assist -> assistMap.getOrPut(assist) { mutableListOf() }.add(current.score) }    
        }    
    }    
    cmds.add("$")    

    cmds.add("\n### 循环命令链 ###")    
    val rawLoopCmds = mutableListOf<String>()    

    // 0. 【新修改点】：将召唤新盔甲架移动到循环命令链的最开头  
    for (p in 0 until actualCount) {    
        val pName = names[p]    
        val runs = partitionRepeatRuns[p] ?: emptyList()    
        if (runs.isNotEmpty()) {  
            val summonScores = mutableListOf<Int>()  
            for (run in runs) {  
                if (run.headScore == run.tailScore) {  
                    summonScores.add(run.headScore) // 长度为一个时,只记录那一个方块的值  
                } else {  
                    summonScores.add(run.headScore)  
                    summonScores.add(run.tailScore) // 长度两个或两个以上时,记录头和尾的值  
                }  
            }  
            val suffix = "_1"  
            rawLoopCmds.addAll(buildPartitionSummonCommands("${pName}$suffix", summonScores))    
        }    
    }  

    // 1. 优先将依附辅助方块输出至命令链头部  
    for ((assist, scores) in assistMap) {    
        rawLoopCmds.addAll(buildSetblockCommands(assist.blockId, scores, assist.dx, assist.dy, assist.dz))    
    }    

    // 2. 将 water 紧随放在辅助方块之后  
    for ((waterId, scores) in waterMap) {    
        rawLoopCmds.addAll(buildSetblockCommands(waterId, scores))    
    }    

    // 3. 生成主普通方块  
    for ((blockId, scores) in blockMap) rawLoopCmds.addAll(buildSetblockCommands(blockId, scores))    
        
    // ============================================================    
    //  连续相同方块优化控制核心指令群 (二分投射 setblock 代替 clone)    
    // ============================================================    
      
    // 计算二分投射段  
    val pFactor = lenInner / 2.0    
    var kFactor = 1.0    
    while (kFactor < pFactor) { kFactor *= 2.0 }    
        
    val projectionSegments = mutableListOf<Double>()    
    var stepK = kFactor    
    while (stepK >= 0.5) {    
        projectionSegments.add(stepK)    
        stepK /= 2.0    
    }    

    // 决定负一的位置方向（根据行进轴）  
    val offsetStrX = if (innerAxis == Axis3D.X) "~-1" else "~"  
    val offsetStrY = if (innerAxis == Axis3D.Y) "~-1" else "~"  
    val offsetStrZ = if (innerAxis == Axis3D.Z) "~-1" else "~"  

    // 批量放置方块 (C.)  
for (p in 0 until actualCount) {    
    val pName = names[p]    
    val suffix = "_1"    
    val runs = partitionRepeatRuns[p] ?: emptyList()    
      
    if (runs.isNotEmpty()) {  
        // 【优化】外提到这里：针对当前分区，二分选择器字符串是完全固定的
        val sbC = java.lang.StringBuilder()
        val selector = "@e[name=\"${pName}${suffix}\"]"  
        projectionSegments.forEach { v ->
            val vStr = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
            sbC.append(" facing entity $selector feet positioned ^ ^ ^$vStr")
        }
        val sbCStr = sbC.toString()

        val runsByBlock = runs.groupBy { it.blockStr }  
        for ((blockStr, blockRuns) in runsByBlock) {  
            val tailScores = blockRuns.map { it.tailScore } 
            // 直接复用构建好的 sbCStr
            val executionPart =" as @e[name=\"${pName}${suffix}\",c=1] at @s${sbCStr} run setblock $offsetStrX $offsetStrY $offsetStrZ $blockStr"
            rawLoopCmds.addAll(buildProjectionSetblockCommands(tailScores, executionPart))
        }
    }
}

// 统一合并全局清洗终结多余衍生盔甲架机制（合并处理所有尾节点方块 n）  
    val globalTailScores = partitionRepeatRuns.values.flatten().map { it.tailScore }    
    if (globalTailScores.isNotEmpty()) {    
        val dynamicValidNames = (0 until actualCount).filter { partitionRepeatRuns[it]?.isNotEmpty() == true }.map { names[it] }    
        if (dynamicValidNames.isNotEmpty()) {    
            rawLoopCmds.addAll(buildFinalKillCommands(dynamicValidNames, globalTailScores))    
        }    
    }    
    // ============================================================    

    for ((tpCoords, scores) in lineBreaks) {    
        val parts = tpCoords.split(" ")    
        rawLoopCmds.addAll(buildTpCommands(scores, parts[0], parts[1], parts[2]))    
    }    
    val sortedPowers = binaryTPs.keys.sorted()    
    for (power in sortedPowers) {    
        val scores = binaryTPs[power]!!    
        val (dx, dy, dz) = getWorldDelta(power, 0, 0)    
        val tpX = if (dx == 0) "~" else "~$dx"; val tpY = if (dy == 0) "~" else "~$dy"; val tpZ = if (dz == 0) "~" else "~$dz"    
        rawLoopCmds.addAll(buildTpCommands(scores, tpX, tpY, tpZ))    
    }    

    val killScoreSelector = getConsecutiveRanges(killScores).joinToString(",") { "$scoreboardObj=!$it" }    
    rawLoopCmds.add("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] unless entity @s[scores={$killScoreSelector}] run kill @s")    
    rawLoopCmds.add("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] run scoreboard players add @s $scoreboardObj 1")    

    if (rawLoopCmds.isNotEmpty()) {    
        cmds.add("$2,0,0")    
        cmds.add(rawLoopCmds[0])    
        cmds.add("$")    
        if (rawLoopCmds.size > 1) {    
            cmds.add("$1,0,1")    
            for (k in 1 until rawLoopCmds.size) { cmds.add(rawLoopCmds[k]) }    
            cmds.add("$")    
        }    
    }    
    return cmds    
}    

private fun blockStatesToString(states: Map<String, Any?>): String {    
    if (states.isEmpty()) return ""    
    return states.entries.joinToString(separator = ",", prefix = "[", postfix = "]") { (k, v) ->    
        "\"$k\"=${formatStateValue(v)}"    
    }    
}    

private fun formatStateValue(v: Any?): String = when (v) {    
    is Boolean -> if (v) "true" else "false"    
    is Number -> {    
        if (v is Double && v == v.toInt().toDouble()) v.toInt().toString() else v.toString()    
    }    
    is List<*> -> {    
        val stringList = v.map { it?.toString()?.lowercase() ?: "" }    
        val directions = setOf("north", "south", "east", "west", "up", "down")    
        val foundDir = stringList.firstOrNull { it in directions }    
        if (foundDir != null) {    
            "\"$foundDir\""    
        } else {    
            val first = v.firstOrNull()    
            if (first is Boolean) if (first) "true" else "false" else "\"$first\""    
        }    
    }    
    else -> "\"$v\""    
}    

private fun getConsecutiveRanges(numbers: List<Int>): List<String> {    
    if (numbers.isEmpty()) return emptyList()    
    val sorted = numbers.distinct().sorted()    
    val ranges = mutableListOf<String>()    
    var start = sorted[0]; var prev = sorted[0]    
    for (i in 1 until sorted.size) {    
        if (sorted[i] == prev + 1) prev = sorted[i]    
        else { ranges.add(if (start == prev) "$start" else "$start..$prev"); start = sorted[i]; prev = sorted[i] }    
    }    
    ranges.add(if (start == prev) "$start" else "$start..$prev")    
    return ranges    
}    

private fun buildSafeRanges(numbers: List<Int>): List<RangeResult> {  
    if (numbers.isEmpty()) return emptyList()  
    
    // 移除 distinct().sorted()，直接使用原集合。仅在有禁止分数时才构建 Set
    val allTimesInChain = if (forbiddenScores.isNotEmpty()) numbers.toSet() else null    
    val results = mutableListOf<RangeResult>()    

    var start = numbers[0]    
    var prev = numbers[0]    

    for (i in 1 until numbers.size) {    
        val cur = numbers[i]    
        if (cur != prev + 1) {    
            results.add(getSafeRange(start, prev, allTimesInChain))    
            start = cur    
        }    
        prev = cur    
    }    

    results.add(getSafeRange(start, prev, allTimesInChain))    
    return results  
}  

// 同步修改配套的调用入参签名
private fun getSafeRange(start: Int, end: Int, allTimesInChain: Set<Int>?): RangeResult {    
    var finalStart = start    
    var finalEnd = end    
    val res = RangeResult(selectorPart = "")    

    if (forbiddenScores.contains(start) && !isConsecutiveForbidden(start)) {    
        finalStart = start - 1    
        if (allTimesInChain == null || !allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)    
    }    

    if (forbiddenScores.contains(end) && !isConsecutiveForbidden(end)) {    
        finalEnd = end + 1    
        if (allTimesInChain == null || !allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)    
    }    

    res.selectorPart = if (finalStart == finalEnd) "$scoreboardObj=!$finalStart" else "$scoreboardObj=!$finalStart..$finalEnd"    
    return res  
}

private fun isConsecutiveForbidden(valNum: Int): Boolean = forbiddenScores.contains(valNum - 1) || forbiddenScores.contains(valNum + 1)  

private fun buildTpCommands(scores: List<Int>, tpX: String, tpY: String, tpZ: String): List<String> {    
    val rangeResults = buildSafeRanges(scores)    
    if (rangeResults.isEmpty()) return emptyList()    
    val cmds = mutableListOf<String>()    
    val currentSelectors = mutableListOf<String>()    
    val currentCorrections = TreeSet<Int>()    
    val prefix = "/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={"    
    val runPart = "}] run tp $tpX $tpY $tpZ"    
    var currentLen = prefix.length + runPart.length + 10    

    for (rr in rangeResults) {    
        val partLen = rr.selectorPart.length + 1    
        var correctionExtraLen = 0    
        for (c in rr.corrections) if (!currentCorrections.contains(c)) correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)    
        if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30    

        if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {    
            flushTpCommand(cmds, currentSelectors, currentCorrections, tpX, tpY, tpZ)    
            currentSelectors.clear(); currentCorrections.clear()    
            currentLen = prefix.length + runPart.length + 10    
        }    
        currentSelectors.add(rr.selectorPart); currentCorrections.addAll(rr.corrections)    
        currentLen += (partLen + correctionExtraLen)    
    }    
    flushTpCommand(cmds, currentSelectors, currentCorrections, tpX, tpY, tpZ)    
    return cmds    
}    

private fun flushTpCommand(out: MutableList<String>, selectors: List<String>, corrections: Set<Int>, tpX: String, tpY: String, tpZ: String) {    
    if (selectors.isEmpty()) return    
    val sb = java.lang.StringBuilder()    
    sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={").append(selectors.joinToString(",")).append("}]")    
    if (corrections.isNotEmpty()) {    
        sb.append(" if entity @s[scores={")    
        var count = 0    
        for (c in corrections) {    
            sb.append(scoreboardObj).append("=!")    
                .append(c)    
            count++    
            if (count < corrections.size) sb.append(",")    
        }    
        sb.append("}]")    
    }    
    sb.append(" run tp ").append(tpX).append(" ").append(tpY).append(" ").append(tpZ)    
    out.add(sb.toString())  
}  

private fun buildSetblockCommands(blockId: String, scores: List<Int>, dx: Int = 0, dy: Int = 0, dz: Int = 0): List<String> {    
    val rangeResults = buildSafeRanges(scores)    
    if (rangeResults.isEmpty()) return emptyList()    
    val cmds = mutableListOf<String>()    
    val currentSelectors = mutableListOf<String>()    
    val currentCorrections = TreeSet<Int>()    
    val prefix = "/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={"    
    val runPart = "}] run setblock ${relative(dx)} ${relative(dy)} ${relative(dz)} $blockId"    
    var currentLen = prefix.length + runPart.length + 10    

    for (rr in rangeResults) {    
        val partLen = rr.selectorPart.length + 1    
        var correctionExtraLen = 0    
        for (c in rr.corrections) if (!currentCorrections.contains(c)) correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)    
        if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30    

        if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {    
            flushSetblockCommand(cmds, currentSelectors, currentCorrections, blockId, dx, dy, dz)    
            currentSelectors.clear(); currentCorrections.clear()    
            currentLen = prefix.length + runPart.length + 10    
        }    
        currentSelectors.add(rr.selectorPart); currentCorrections.addAll(rr.corrections)    
        currentLen += (partLen + correctionExtraLen)    
    }    
    flushSetblockCommand(cmds, currentSelectors, currentCorrections, blockId, dx, dy, dz)    
    return cmds    
}    

private fun flushSetblockCommand(out: MutableList<String>, selectors: List<String>, corrections: Set<Int>, blockId: String, dx: Int, dy: Int, dz: Int) {    
    if (selectors.isEmpty()) return    
    val sb = java.lang.StringBuilder()    
    sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={").append(selectors.joinToString(",")).append("}]")    
    if (corrections.isNotEmpty()) {    
        sb.append(" if entity @s[scores={")    
        var count = 0    
        for (c in corrections) {    
            sb.append(scoreboardObj).append("=!")    
                .append(c)    
            count++    
            if (count < corrections.size) sb.append(",")    
        }    
        sb.append("}]")    
    }    
    sb.append(" run setblock ").append(relative(dx)).append(" ").append(relative(dy)).append(" ").append(relative(dz)).append(" ").append(blockId)    
    out.add(sb.toString())  
}  

// ============================================================    
//  新增替换：将克隆指令改写为二分投射 setblock 指令流  
// ============================================================    
private fun buildProjectionSetblockCommands(scores: List<Int>, executionPart: String): List<String> {  
    val rangeResults = buildSafeRanges(scores)  
    if (rangeResults.isEmpty()) return emptyList()  
    val cmds = mutableListOf<String>()  
    val currentSelectors = mutableListOf<String>()  
    val currentCorrections = TreeSet<Int>()  
    val prefix = "/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={"  
    var currentLen = prefix.length + executionPart.length + 10  

    for (rr in rangeResults) {  
        val partLen = rr.selectorPart.length + 1  
        var correctionExtraLen = 0  
        for (c in rr.corrections) if (!currentCorrections.contains(c)) correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)  
        if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30  

        if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {  
            flushProjectionSetblockCommand(cmds, currentSelectors, currentCorrections, executionPart)  
            currentSelectors.clear(); currentCorrections.clear()  
            currentLen = prefix.length + executionPart.length + 10  
        }  
        currentSelectors.add(rr.selectorPart); currentCorrections.addAll(rr.corrections)  
        currentLen += (partLen + correctionExtraLen)  
    }  
    flushProjectionSetblockCommand(cmds, currentSelectors, currentCorrections, executionPart)  
    return cmds  
}  

private fun flushProjectionSetblockCommand(out: MutableList<String>, selectors: List<String>, corrections: Set<Int>, executionPart: String) {  
    if (selectors.isEmpty()) return  
    val sb = java.lang.StringBuilder()  
    sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={").append(selectors.joinToString(",")).append("}]")  
    if (corrections.isNotEmpty()) {  
        sb.append(" if entity @s[scores={")  
        var count = 0  
        for (c in corrections) {  
            sb.append(scoreboardObj).append("=!")  
                .append(c)  
            count++  
            if (count < corrections.size) sb.append(",")  
        }  
        sb.append("}]")  
    }  
    sb.append(executionPart)  
    out.add(sb.toString())  
}  

private fun buildPartitionSummonCommands(summonName: String, scores: List<Int>): List<String> {    
    val rangeResults = buildSafeRanges(scores)    
    if (rangeResults.isEmpty()) return emptyList()    
    val cmds = mutableListOf<String>()    
    val currentSelectors = mutableListOf<String>()    
    val currentCorrections = TreeSet<Int>()    
    val prefix = "/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={"    
    val runPart = "}] run summon armor_stand ~ ~ ~ ~ ~ . $summonName"    
    var currentLen = prefix.length + runPart.length + 10    

    for (rr in rangeResults) {    
        val partLen = rr.selectorPart.length + 1    
        var correctionExtraLen = 0    
        for (c in rr.corrections) if (!currentCorrections.contains(c)) correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)    
        if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30    

        if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {    
            flushSummonCommand(cmds, currentSelectors, currentCorrections, summonName)    
            currentSelectors.clear(); currentCorrections.clear()    
            currentLen = prefix.length + runPart.length + 10    
        }    
        currentSelectors.add(rr.selectorPart); currentCorrections.addAll(rr.corrections)    
        currentLen += (partLen + correctionExtraLen)    
    }    
    flushSummonCommand(cmds, currentSelectors, currentCorrections, summonName)    
    return cmds    
}    

private fun flushSummonCommand(out: MutableList<String>, selectors: List<String>, corrections: Set<Int>, currentName: String) {    
    if (selectors.isEmpty()) return    
    val sb = java.lang.StringBuilder()    
    sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={").append(selectors.joinToString(",")).append("}]")    
    if (corrections.isNotEmpty()) {    
        sb.append(" if entity @s[scores={")    
        var count = 0    
        for (c in corrections) {    
            sb.append(scoreboardObj).append("=!")    
                .append(c)    
            count++    
            if (count < corrections.size) sb.append(",")    
        }    
        sb.append("}]")    
    }    
    sb.append(" run summon armor_stand ~ ~ ~ ~ ~ . $currentName")    
    out.add(sb.toString())  
}  

private fun buildFinalKillCommands(names: List<String>, allTailScores: List<Int>): List<String> {    
    val rangeResults = buildSafeRanges(allTailScores)    
    if (rangeResults.isEmpty()) return emptyList()    
    val cmds = mutableListOf<String>()    
    val currentSelectors = mutableListOf<String>()    
    val currentCorrections = TreeSet<Int>()    
        
    val executionPart = " as @e unless entity @s[" + names.joinToString(",") { "name=!${it}_1" } + "] run kill @s"    
    val prefix = "/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={"    
    var currentLen = prefix.length + executionPart.length + 10    

    for (rr in rangeResults) {    
        val partLen = rr.selectorPart.length + 1    
        var correctionExtraLen = 0    
        for (c in rr.corrections) if (!currentCorrections.contains(c)) correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)    
        if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30    

        if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {    
            flushFinalKillCommand(cmds, currentSelectors, currentCorrections, executionPart)    
            currentSelectors.clear(); currentCorrections.clear()    
            currentLen = prefix.length + executionPart.length + 10    
        }    
        currentSelectors.add(rr.selectorPart); currentCorrections.addAll(rr.corrections)    
        currentLen += (partLen + correctionExtraLen)    
    }    
    flushFinalKillCommand(cmds, currentSelectors, currentCorrections, executionPart)    
    return cmds    
}    

private fun flushFinalKillCommand(out: MutableList<String>, selectors: List<String>, corrections: Set<Int>, executionPart: String) {    
    if (selectors.isEmpty()) return    
    val sb = java.lang.StringBuilder()    
    sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={").append(selectors.joinToString(",")).append("}]")    
    if (corrections.isNotEmpty()) {    
        sb.append(" if entity @s[scores={")    
        var count = 0    
        for (c in corrections) {    
            sb.append(scoreboardObj).append("=!")    
                .append(c)    
            count++    
            if (count < corrections.size) sb.append(",")    
        }    
        sb.append("}]")    
    }    
    sb.append(executionPart)    
    out.add(sb.toString())  
}  

private fun relative(v: Int): String = if (v == 0) "~" else "~$v"    

private fun calcWorldCoord(start: Long, axis: Axis3D, innerAxis: Axis3D, innerValue: Int, innerStep: Int, middleAxis: Axis3D, middleValue: Int, middleStep: Int, outerAxis: Axis3D, outerValue: Int, outerStep: Int): Long {    
    var result = start    
    if (axis == innerAxis) result += innerValue.toLong() * innerStep    
    if (axis == middleAxis) result += middleValue.toLong() * middleStep    
    if (axis == outerAxis) result += outerValue.toLong() * outerStep    
    return result    
}    

private fun worldToGlobalIndex(currentWorldX: Long, currentWorldY: Long, currentWorldZ: Long, dx: Int, dy: Int, dz: Int, lenInner: Int, lenMiddle: Int, lenOuter: Int): Int? {    
    val tx = currentWorldX + dx; val ty = currentWorldY + dy; val tz = currentWorldZ + dz    
    fun axisValue(axis: Axis3D, x: Long, y: Long, z: Long): Long = when (axis) { Axis3D.X -> x; Axis3D.Y -> y; Axis3D.Z -> z }    
    fun axisPos(target: Long, start: Long, step: Int, len: Int): Int? {    
        if (step == 0) return null    
        val delta = target - start; val stepL = step.toLong()    
        if (delta % stepL != 0L) return null    
        val pos = (delta / stepL).toInt()    
        return if (pos in 0 until len) pos else null    
    }    
    val innerPos = axisPos(axisValue(innerAxis, tx, ty, tz), axisStart(innerAxis), innerStep, lenInner) ?: return null    
    val middlePos = axisPos(axisValue(middleAxis, tx, ty, tz), axisStart(middleAxis), middleStep, lenMiddle) ?: return null    
    val outerPos = axisPos(axisValue(outerAxis, tx, ty, tz), axisStart(outerAxis), outerStep, lenOuter) ?: return null    
    return outerPos * lenInner * lenMiddle + middlePos * lenInner + innerPos + 1    
}    

private fun axisStart(axis: Axis3D): Long = when (axis) { Axis3D.X -> startX.toLong(); Axis3D.Y -> startY.toLong(); Axis3D.Z -> startZ.toLong() }    

// ============================================================    
//  建造者类 (Builder)    
// ============================================================    
class Builder(private val structureFile: File) {    
    private var scoreboardObj = "n"    
    private var entityName = "C"    
    private var charLimit = 10000    
    private var startX = 0; private var startY = 0; private var startZ = 0    
    private var mirrorX = false; private var mirrorY = false; private var mirrorZ = false    
    private var innerAxis = Axis3D.X; private var innerStep = 1    
    private var middleAxis = Axis3D.Z; private var middleStep = 1    
    private var outerAxis = Axis3D.Y; private var outerStep = 1    
    private var startScoreOffset = 0    
    private var forbiddenScores = emptySet<Int>()    
    private var generationMultiplier = 1    
    private var bedrockTargetVersion = "1.26.30"    
        
    private val assistRules = mutableListOf<AssistRule>()    
    private var skipConfig: SkipConfig? = null    

    private var mappingJsonRules = """{    
        "state_types": {"java": {}, "bedrock": {}},    
        "state_groups": {"java": {}, "bedrock": {}},    
        "block_mappings": {}    
    }""".trimIndent()    

    fun setScoreboardObj(obj: String) = apply { this.scoreboardObj = obj }    
    fun setEntityName(name: String) = apply { this.entityName = name }    
    fun setCharLimit(limit: Int) = apply { this.charLimit = limit }    
    fun setStartCoords(x: Int, y: Int, z: Int) = apply { startX = x; startY = y; startZ = z }    
    fun setMirrors(x: Boolean, y: Boolean, z: Boolean) = apply { mirrorX = x; mirrorY = y; mirrorZ = z }    
    fun setAxisConfig(innerAxis: Axis3D, innerStep: Int, middleAxis: Axis3D, middleStep: Int, outerAxis: Axis3D, outerStep: Int) = apply {    
        require(innerAxis != middleAxis && middleAxis != outerAxis && innerAxis != outerAxis) { "三轴方向不能重叠！" }    
        this.innerAxis = innerAxis; this.innerStep = innerStep    
        this.middleAxis = middleAxis; this.middleStep = middleStep    
        this.outerAxis = outerAxis; this.outerStep = outerStep    
    }    
    fun setStartScoreOffset(offset: Int) = apply { this.startScoreOffset = offset }    
    fun setForbiddenScores(values: Set<Int>) = apply { this.forbiddenScores = values }    
    fun setGenerationMultiplier(m: Int) = apply { this.generationMultiplier = if (m < 1) 1 else m }    
    fun setMappingJsonRules(jsonContent: String) = apply { this.mappingJsonRules = jsonContent }    
    fun setBedrockTargetVersion(version: String) = apply { this.bedrockTargetVersion = version }    

    fun setSkipConfig(blockIds: List<String>, matchType: MatchType, filterMode: FilterMode) = apply {    
        this.skipConfig = SkipConfig(blockIds, matchType, filterMode)    
    }    

    fun addAssistRule(    
        targetBlockId: String,    
        matchType: MatchType,    
        targetStates: Map<String, Any?>?,    
        offsetX: Int,    
        offsetY: Int,    
        offsetZ: Int,    
        assistBlockStr: String,    
        condition: PartitionCondition    
    ) = apply {    
        this.assistRules.add(    
            AssistRule(targetBlockId, matchType, targetStates, offsetX, offsetY, offsetZ, assistBlockStr, condition)    
        )    
    }    

    fun build(): BinaryMcStructureCommandGenerator = BinaryMcStructureCommandGenerator(    
        structureFile, scoreboardObj, entityName, charLimit,    
        startX, startY, startZ, mirrorX, mirrorY, mirrorZ,    
        innerAxis, innerStep, middleAxis, middleStep, outerAxis, outerStep,    
        startScoreOffset, forbiddenScores, generationMultiplier, mappingJsonRules, bedrockTargetVersion,    
        assistRules, skipConfig    
    )    
}

}