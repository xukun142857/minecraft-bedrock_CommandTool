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
 * 方块ID的匹配模式
 */
enum class MatchType {
    EXACT,      // 全匹配（完全等于给定的ID）
    CONTAINS    // 包含匹配（给定字符串是方块ID的子串）
}

/**
 * 盔甲架管理分区的判定条件
 */
enum class PartitionCondition {
    ANY,                // 总是放置
    SAME_PARTITION,     // 仅在当前被辅助方块属于“当前盔甲架管理的分区”时放置
    DIFFERENT_PARTITION // 仅在当前被辅助方块属于“其他盔甲架管理的分区”时放置
}

/**
 * 依附辅助方块的匹配与放置规则
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
    private val skipConfig: SkipConfig?,
    private val enableExtraSensitiveOptimization: Boolean
) {

    private val detector = if (enableExtraSensitiveOptimization) NeteaseSensitiveDetector() else null

class NeteaseSensitiveDetector {
    private val patterns: List<Regex>

    init {
        // 提取原有的边界定义，以便在正则中复用
        val boundaryLeft64 = """[（）+\-#0-9一-龥{}/a-z,\s。°￥¥%:.\…，~]"""
        val boundaryRight64 = """[（）（）{}+\-#0-9_a-z一-龥=.\，,\—\…'%１２３４５６７８９０]|\s[0-9]"""

        patterns = listOf(
            // 1. 基础数字隔离干扰匹配 (6489 / 8964 变体)
            Regex("""^[\s\.,，]*?[6][\s\.,，]*?[4][\s\.,，]*?[8][\s\.,，]*?[9][\s\.,，]*?$""", RegexOption.IGNORE_CASE),
            Regex("""^[\s.,，]?[6][\s.,，]?[4][\s.,，]?[8][\s.,，]?[9][\s.,，]?$""", RegexOption.IGNORE_CASE),
            Regex("""^[\s.,，]?[8][\s.,，]?[9][\s.,，]?[6][\s.,，]?[4][\s.,，]?$""", RegexOption.IGNORE_CASE),
            
            // 2. 带前后缀过滤的 64 变体 (包含多国字符/全角数字变形)
            Regex("""^(?!.?@).?(?<!$boundaryLeft64)([6б６⒍⑥ｂБЬ㈥⑹][^一-龥1-9１２３４５６７８９０a-z=/]?[4４⒋④㈣⑷Ч４ㄐчㄐ])(?!($boundaryRight64))""", RegexOption.IGNORE_CASE),
            
            // 3. 带后缀过滤的 89 变体 (过滤了“法师”、“求组”、JPE文件等)
            Regex("""(?<![\[\<\>\(\)\（\）\+\-#0-9一-龥\\\{\}\/a-z\,\s_。°￥¥%\:\.\…\'\"\，\^\~\]])[捌八8][^一-龥、\-#0-9a-z=]*?[九玖9](?!(\-JPE|\\|[\.\-][0-9]))(?!([\[\<\>\(\)\（\）\{\}\+\-#0-9_a-z一-龥\=\*\.\，\,\—\…\'\"\'%１２３４５６７８９０\]]|\s[0-9]|\s法师|\s求组))""", RegexOption.IGNORE_CASE),
            
            // 4. 常见敏感数字组合变形 (89, 64, 535) 及后缀防误报
            Regex("""(?<![\<\>\(\%\~#0-9a-z座米零一二三五七世开期周])([8八捌][9九玖]|[6六陆][4四肆]|[5五][3三][5五])(?![_a-z0-9个道分级进月班舍寝室八节点,\)])""", RegexOption.IGNORE_CASE),
            
            // 5. 64 变形与复杂干扰符匹配
            Regex("""^(?!.*?@).*?(?<![\[\<\>\(\)\（\）\+\-#0-9一-龥\\\{\}\/a-z\,\s_。°￥¥%\:\.\…\，\~\]])([6б６⒍⑥ｂБЬ㈥⑹六陆][^一-龥1-9１２３４５６７８９０a-z=\/]*?[4４⒋④㈣⑷四肆Ч４ㄐчㄐ])(?!([\[\<\>\(\)\（\）\{\}\+\-#0-9_a-z一-龥\=\*\.\，\,\—\…\'%１２３４５６７８９０\]]|\s[0-9]))""", RegexOption.IGNORE_CASE),
            
            // 6. 罗马数字、大写数字与长多位数混淆组合 (如 896404, 469891 等)
            Regex("""(?<![\[\<\>\(\)\（\）\+\-#0-9一-龥\\\{\}\/a-z\,\s_。°￥¥%\:\.\…\，\~\]])([捌八8Ⅷ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[九玖9Ⅸ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[6б６⒍⑥ｂБЬ㈥⑹六陆Ⅵ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[0零]?[^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[4４⒋④㈣⑷四肆Ч４ㄐчㄐⅣ]|1?9?[捌八8][九玖9].?[6六陆溜][0零]?[4四肆]|1?9?[6六陆][0零]?[4四肆][^一-龥0-9a-z]?[捌八8][九玖9]|469891)(?!([\[\<\>\(\)\（\）\+\-#0-9_a-z一-龥\*\.\，\,\—\…\'%１２３４５６７８９０\]]|\s[0-9]))""", RegexOption.IGNORE_CASE),
            
            // 7. 8964 各类标点、空格大跨度隔离匹配 (已移除原 content= 依赖)
            Regex("""^[\s\.\,\，。丶]*?[8八捌叭][\s\.\,\，。丶]*?[9九玖][\s\.\,\，。丶]*?[6六陆][\s\.\,\，。丶]*?[4四肆][\s\.\,\，。丶_]*?$""", RegexOption.IGNORE_CASE),
            Regex("""^[\s\.\,\，。丶]*?[8八捌叭][\s\.\,\，。丶]*?[9九玖][\s\.\,\，。丶]*?[6六陆][\s\.\,\，。丶]*?[4四肆][\s\.\,\，。丶]*?[8八捌叭][\s\.\,\，。丶]*?[9九玖][\s\.\,\，。丶]*?$""", RegexOption.IGNORE_CASE),
            Regex("""^[\s\.\,\，。丶]*?[6六陆][\s\.\,\，。丶]*?[4四肆][\s\.\,\，。丶]*?[8八捌叭][\s\.\,\，。丶]*?[9九玖][\s\.\,\，。丶]*?[6六陆][\s\.\,\，。丶]*?[4四肆][\s\.\,\，。丶]*?$""", RegexOption.IGNORE_CASE),
            
            // 8. 拼音及数字混合组合型 (如 liu si ba jiu / 6489)
            Regex("""(?<![\(a-z0-9#\)])((liu|[6六])\s*?(si|[4四])\s*?(ba|[8八])\s*?(jiu|[9九])|[六6陆][十0拾]?[四4肆][八8捌][十0拾]?[九9玖])(?![\(_0-9a-z\)])""", RegexOption.IGNORE_CASE),
            
            // 9. 拼音缩写及带点字符混淆组合型 (ba jiu liu si)
            Regex("""^(?!.*?@[as]).*?(?<![\[\<\>\(\)\（\）\+\-#0-9a-z\\\{\}\/\,\s_。°￥¥%\:\.\…\，\]])[8八][^一-龥a-z0-9]*?[9九][^一-龥a-z0-9]*?[6六][^一-龥a-z0-9]*?[4四](?![\[\<\>\(\)\（\）\+\-#0-9a-z\\\{\}\/\,\s。°￥¥%\:\.\…\，\]])""", RegexOption.IGNORE_CASE),
            Regex("""(?<![a-z0-9#])(ba|[8八])\s*?(jiu|[9九])\s*?(liu|[6六])\s*?(si|[4四])(?![0-9a-z])""", RegexOption.IGNORE_CASE),
            Regex("""(?<![a-z0-9#])[8八][s\.\,\，丶]*?[9九][s\.\,\，丶]*?l[s\.\,\，丶]*?si?(?![0-9a-z])""", RegexOption.IGNORE_CASE),
            
            // 10. 拆字/算式型变形 (如 6+3 3+3 2+2)
            Regex("""6\+3[^a-z一-龥0-9]*?3\+3[^a-z一-龥0-9]*?2\+2(?![0-9a-z])""", RegexOption.IGNORE_CASE),
            
            // 11. 历史跨度多位敏感数字 (原 375415 规则与新增 37... 规则合流)
            Regex("""[3].{0,3}?[7].{0,3}?[5].{0,3}?[4].{0,3}?[1一].{0,3}?[5]""", RegexOption.IGNORE_CASE),
            Regex("""(?<![0-9#])37[a-z\s\.\,\，。丶]*?年[0-9a-z\s\.\,\，。丶]*?前""", RegexOption.IGNORE_CASE),
            Regex("""(?<![0-9#])[4四]?[\s\.\,\，。丶]*?[2二][\s\.\,\，。丶]*?[6六][\s\.\,\，。丶]*?社论""", RegexOption.IGNORE_CASE),
            Regex("""^[^\sa-zA-Z一-龥0-9]*?(?<![#])[3３三叁]([^一-龥0-9１２３４五六七八九零a-z=\/]*?|十)[7七柒][^a-zA-Z一-龥0-9]*?$""", RegexOption.IGNORE_CASE)
        )
    }

    fun matchText(text: String): Boolean {
        return patterns.any { it.containsMatchIn(text) }
    }
}

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

    private data class RepeatRunInfo(    
        val headScore: Int,    
        val tailScore: Int,  
        val blockStr: String  
    )    

    private data class Element(    
        val blocks: List<SolidBlock>,    
        val weight: Int    
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

    private fun canUseSkipOptimizationPrePartition(block: SolidBlock): Boolean {
        if (!isBlockAllowed(block.id)) return false
        if (block.extraId != "minecraft:air" && block.extraId != "air") return false
        
        // 校验是否匹配任何辅助方块规则，若存在可能挂载的辅助方块，则该位置无法执行合并跳过
        val matchesAnyAssist = assistRules.any { rule ->
            val idMatches = when (rule.matchType) {
                MatchType.EXACT -> block.id == rule.targetBlockId
                MatchType.CONTAINS -> block.id.contains(rule.targetBlockId)
            }
            idMatches && matchesStates(block.states, rule.targetStates)
        }
        if (matchesAnyAssist) return false
        
        return true
    }

    // ============================================================
    //  高性能在线分区推导核心算法（替代原先庞大的全局哈希映射映射）
    // ============================================================
    private fun getCellPartition(
        i: Int, m: Int, o: Int, 
        tempSolidBlocks: List<SolidBlock>, 
        lenInner: Int, lenMiddle: Int, 
        totalCells: Long, actualCount: Int
    ): Int {
        // 1. 实体方块通过全序二分查找进行分区定位 (无分配，时间复杂度 O(log N))
        var low = 0
        var high = tempSolidBlocks.size - 1
        var foundIdx = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = tempSolidBlocks[mid]
            if (midVal.o < o) {
                low = mid + 1
            } else if (midVal.o > o) {
                high = mid - 1
            } else {
                if (midVal.m < m) {
                    low = mid + 1
                } else if (midVal.m > m) {
                    high = mid - 1
                } else {
                    if (midVal.i < i) {
                        low = mid + 1
                    } else if (midVal.i > i) {
                        high = mid - 1
                    } else {
                        foundIdx = mid
                        break
                    }
                }
            }
        }
        
        if (foundIdx != -1) {
            return tempSolidBlocks[foundIdx].partition
        }
        
        // 2. 如果当前坐标不是实体方块（即空气网格），通过纯数学映射推导其默认分区 (时间复杂度 O(1))
        val virtualIdx = o.toLong() * (lenInner.toLong() * lenMiddle) + m.toLong() * lenInner + i
        val cellAvg = totalCells / actualCount
        val cellRemainder = totalCells % actualCount
        val threshold = cellRemainder * (cellAvg + 1)
        
        return if (virtualIdx < threshold) {
            (virtualIdx / (cellAvg + 1)).toInt()
        } else {
            (cellRemainder + (virtualIdx - threshold) / cellAvg).toInt()
        }
    }

    fun generate(): String {    
        return try {    
            val structure = loadStructure(structureFile)    
            if (structure.width == 0 || structure.height == 0 || structure.depth == 0) return ""    

            val w = structure.width; val h = structure.height; val d = structure.depth    
            val lenInner = getAxisLength(innerAxis, w, h, d)    
            val lenMiddle = getAxisLength(middleAxis, w, h, d)    
            val lenOuter = getAxisLength(outerAxis, w, h, d)    
            val totalCells = lenInner.toLong() * lenMiddle * lenOuter    

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
                                // 在遍历时立即执行 Bedrock 转换与状态清洗，彻底避免后续二次转换
                                val dummyCtx = object : MatchContext {
                                    override fun isTargetInOtherPartition(dx: Int, dy: Int, dz: Int): Boolean = false
                                }
                                val applied = adaptToBedrock(block.id, block.states, dummyCtx)
                                val isWaterlogged = applied.states["waterlogged"] == true || applied.states["waterlogged"] == "true" || block.extraId == "minecraft:water" || block.extraId == "water"    
                                val finalExtraId = if (isWaterlogged) "minecraft:water" else "minecraft:air"    
                                val cleanedStates = applied.states.filterKeys { it.lowercase(Locale.ROOT) != "waterlogged" }

                                tempSolidBlocks.add(    
                                    SolidBlock(score = 0, partition = 0, i = i, m = m, o = o, id = applied.id, states = cleanedStates, assists = emptyList(), extraId = finalExtraId)    
                                )    
                            }    
                        }    
                    }    
                }    
            }    

            if (tempSolidBlocks.isEmpty()) return ""    

            val actualSolidCount = tempSolidBlocks.size    
            val actualCount = minOf(generationMultiplier.coerceAtLeast(1), actualSolidCount.coerceAtLeast(1))    

            // 1. 预计算每个实体方块是否满足 Skip 跳过资格
            val canSkipList = tempSolidBlocks.map { canUseSkipOptimizationPrePartition(it) }

            // 2. 将行进轴（Inner轴）上的连续串打包成不可拆分的分区元素 Element，将 >=2 个连续方块串视作 2 个方块计算权重
            val elements = mutableListOf<Element>()
            var startIdx = 0
            while (startIdx < tempSolidBlocks.size) {
                var endIdx = startIdx
                val startBlock = tempSolidBlocks[startIdx]
                
                // 找出属于同一 O 轴和 M 轴切面的连续块集合
                while (endIdx + 1 < tempSolidBlocks.size &&
                       tempSolidBlocks[endIdx + 1].o == startBlock.o &&
                       tempSolidBlocks[endIdx + 1].m == startBlock.m) {
                    endIdx++
                }
                
                var idx = startIdx
                while (idx <= endIdx) {
                    val runStartBlock = tempSolidBlocks[idx]
                    
                    if (!canSkipList[idx]) {
                        elements.add(Element(listOf(runStartBlock), 1))
                        idx++
                        continue
                    }
                    
                    var runEndIdx = idx
                    while (runEndIdx + 1 <= endIdx &&
                           tempSolidBlocks[runEndIdx + 1].i == tempSolidBlocks[runEndIdx].i + 1 &&
                           tempSolidBlocks[runEndIdx + 1].id == runStartBlock.id &&
                           tempSolidBlocks[runEndIdx + 1].states == runStartBlock.states &&
                           tempSolidBlocks[runEndIdx + 1].extraId == runStartBlock.extraId &&
                           canSkipList[runEndIdx + 1]) {
                        runEndIdx++
                    }
                    
                    val runLength = runEndIdx - idx + 1
                    if (runLength >= 2) {
                        // 将要合并的重复连续串：不论多长，在分区权重的分配里都当成 2 个方块来计算
                        val runBlocks = tempSolidBlocks.subList(idx, runEndIdx + 1).toList()
                        elements.add(Element(runBlocks, 2))
                        idx = runEndIdx + 1
                    } else {
                        elements.add(Element(listOf(runStartBlock), 1))
                        idx++
                    }
                }
                startIdx = endIdx + 1
            }

            // 3. 实行高性能的顺序加权分区定位，完美规避连续串被跨分区截断问题
            val totalWeight = elements.sumOf { it.weight }
            var currentPartition = 0
            var accumulatedWeight = 0
            val tempSolidBlocksWithPartition = mutableListOf<SolidBlock>()
            
            for (element in elements) {
                // 边界划分校验：让每个分区的理想权重分配趋向均匀
                while (currentPartition < actualCount - 1 && 
                       accumulatedWeight >= (totalWeight.toLong() * (currentPartition + 1)) / actualCount) {
                    currentPartition++
                }
                
                for (block in element.blocks) {
                    tempSolidBlocksWithPartition.add(block.copy(partition = currentPartition))
                }
                accumulatedWeight += element.weight
            }

            tempSolidBlocks.clear()
            tempSolidBlocks.addAll(tempSolidBlocksWithPartition)

            // 已剔除旧版本中开散极大的 cellPartitionMap 循环分配区块行为，实现完全无内存损耗的在线寻址机制。

            val finalSolidBlocks = mutableListOf<SolidBlock>()    
            for (sb in tempSolidBlocks) {    
                val i = sb.i; val m = sb.m; val o = sb.o    
                val currentPartition = sb.partition    

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
                            
                        val targetPartition = getCellPartition(tI, tM, tO, tempSolidBlocks, lenInner, lenMiddle, totalCells, actualCount)
                        return targetPartition != currentPartition    
                    }    
                }    

                val calculatedAssists = mutableListOf<AssistSpec>()    
                for (rule in assistRules) {    
                    val idMatches = when (rule.matchType) {    
                        MatchType.EXACT -> sb.id == rule.targetBlockId    
                        MatchType.CONTAINS -> sb.id.contains(rule.targetBlockId)    
                    }    
                    if (idMatches) {    
                        val statesMatch = matchesStates(sb.states, rule.targetStates)    
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
                    SolidBlock(score = 0, partition = currentPartition, i = i, m = m, o = o, id = sb.id, states = sb.states, assists = calculatedAssists, extraId = sb.extraId)    
                )    
            }    

            // 优化点：采用高性能单次扫描滑动窗口取代大规模 Triple 对象的 groupBy 分组
            startIdx = 0
            
            while (startIdx < finalSolidBlocks.size) {  
                var endIdx = startIdx  
                val startBlock = finalSolidBlocks[startIdx]  
                while (endIdx + 1 < finalSolidBlocks.size &&  
                       finalSolidBlocks[endIdx + 1].partition == startBlock.partition &&  
                       finalSolidBlocks[endIdx + 1].o == startBlock.o &&  
                       finalSolidBlocks[endIdx + 1].m == startBlock.m) {  
                    endIdx++  
                }  
                
                var idx = startIdx  
                while (idx <= endIdx) {  
                    val runStartBlock = finalSolidBlocks[idx]  
                      
                    if (!canUseSkipOptimization(runStartBlock)) {  
                        idx++  
                        continue  
                    }  
                      
                    var runEndIdx = idx    
                    while (runEndIdx + 1 <= endIdx &&    
                           finalSolidBlocks[runEndIdx + 1].i == finalSolidBlocks[runEndIdx].i + 1 &&    
                           finalSolidBlocks[runEndIdx + 1].id == runStartBlock.id &&    
                           finalSolidBlocks[runEndIdx + 1].states == runStartBlock.states &&    
                           finalSolidBlocks[runEndIdx + 1].extraId == runStartBlock.extraId &&    
                           finalSolidBlocks[runEndIdx + 1].assists == runStartBlock.assists &&    
                           canUseSkipOptimization(finalSolidBlocks[runEndIdx + 1])) {    
                        runEndIdx++  
                    }  
                    val runLength = runEndIdx - idx + 1  
                    if (runLength >= 1) {  
                        runStartBlock.isRepeatHead = true  
                        finalSolidBlocks[runEndIdx].isRepeatTail = true  
                        for (k in idx + 1 until runEndIdx) {  
                            finalSolidBlocks[k].isRepeatIntermediate = true  
                        }  
                        idx = runEndIdx + 1  
                    } else {  
                        idx++  
                    }  
                }  
                startIdx = endIdx + 1  
            }  

            val filteredSolidBlocks = finalSolidBlocks.filter { !it.isRepeatIntermediate }.toMutableList()    
            assignGlobalScores(filteredSolidBlocks, lenInner, lenMiddle, lenOuter)    

            // 优化点：使用双指针窗口替代第二次 groupBy 操作，进一步阻止内存碎片和 GC 压力
            val partitionRepeatRuns = mutableMapOf<Int, MutableList<RepeatRunInfo>>()    
            var fStartIdx = 0  
            while (fStartIdx < filteredSolidBlocks.size) {  
                var fEndIdx = fStartIdx  
                val startBlock = filteredSolidBlocks[fStartIdx]  
                while (fEndIdx + 1 < filteredSolidBlocks.size &&  
                       filteredSolidBlocks[fEndIdx + 1].partition == startBlock.partition &&  
                       filteredSolidBlocks[fEndIdx + 1].o == startBlock.o &&  
                       filteredSolidBlocks[fEndIdx + 1].m == startBlock.m) {  
                    fEndIdx++  
                }  
                
                var iScan = fStartIdx  
                while (iScan <= fEndIdx) {  
                    val b = filteredSolidBlocks[iScan]  
                    if (b.isRepeatHead) {  
                        var tailScan = iScan  
                        while (tailScan <= fEndIdx && !filteredSolidBlocks[tailScan].isRepeatTail) {  
                            tailScan++  
                        }  
                        if (tailScan <= fEndIdx) {  
                            val tailB = filteredSolidBlocks[tailScan]  
                            val statesStr = blockStatesToString(b.states)  
                            val fullBlockStr = if (statesStr.isEmpty()) b.id else "${b.id} $statesStr"  
                              
                            partitionRepeatRuns.getOrPut(b.partition) { mutableListOf() }.add(    
                                RepeatRunInfo(b.score, tailB.score, fullBlockStr)    
                            )    
                            iScan = tailScan + 1    
                            continue    
                        }  
                    }    
                    iScan++    
                }  
                fStartIdx = fEndIdx + 1  
            }    

            return generateCommands(filteredSolidBlocks, lenInner, lenMiddle, lenOuter, totalCells.toInt(), partitionRepeatRuns)    
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

            var deltaScore = 0
            if (next.o > current.o) {
                deltaScore += (next.o - current.o) 
                deltaScore += next.m               
                if (next.i > 0) deltaScore += 1    
            } else if (next.m > current.m) {
                deltaScore += (next.m - current.m) 
                if (next.i > 0) deltaScore += 1    
            } else {
                if (next.i > current.i) deltaScore += 1 
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
    ): String {  
        val out = StringBuilder(64 * 1024)  
  
        fun emit(line: String) {  
            out.append(line).append('\n')  
        }  
  
        val actualCount = minOf(generationMultiplier.coerceAtLeast(1), totalBlocks.coerceAtLeast(1))  
        val names = Array(actualCount) { idx -> if (idx == 0) entityName else "$entityName$idx" }  
  
        emit("### 初始化指令 ###")  
        emit("$0,0,0")  
        emit("/tickingarea remove_all")  
        emit("$")  
        emit("$1,0,1")  
  
        var endX = startX  
        var endZ = startZ  
        if (innerAxis == Axis3D.X) endX += (lenInner - 1) * innerStep  
        if (middleAxis == Axis3D.X) endX += (lenMiddle - 1) * middleStep  
        if (outerAxis == Axis3D.X) endX += (lenOuter - 1) * outerStep  
        if (innerAxis == Axis3D.Z) endZ += (lenInner - 1) * innerStep  
        if (middleAxis == Axis3D.Z) endZ += (lenMiddle - 1) * middleStep  
        if (outerAxis == Axis3D.Z) endZ += (lenOuter - 1) * outerStep  
  
        val minX = minOf(startX, endX)  
        val maxX = maxOf(startX, endX)  
        val minZ = minOf(startZ, endZ)  
        val maxZ = maxOf(startZ, endZ)  
  
        for (sx in minX..maxX step 160) {  
            for (sz in minZ..maxZ step 160) {  
                val ex = minOf(sx + 159, maxX)  
                val ez = minOf(sz + 159, maxZ)  
                emit("/tickingarea add $sx 0 $sz $ex 0 $ez")  
            }  
        }  
  
        emit("$")  
        emit("$0,0,0")  
        emit("/scoreboard objectives add $scoreboardObj dummy")  
        emit("$")  
        emit("$1,0,1")  
  
        val blockMap = LinkedHashMap<String, MutableList<Int>>()  
        val waterMap = LinkedHashMap<String, MutableList<Int>>()  
        val assistMap = LinkedHashMap<AssistSpec, MutableList<Int>>()  
        val lineBreaks = LinkedHashMap<String, MutableList<Int>>()  
        val binaryTPs = LinkedHashMap<Int, MutableList<Int>>()  
        val killScores = ArrayList<Int>()  
  
        fun getCoordStringLocal(axis: Axis3D, stepM: Int, stepO: Int, isCrossLayer: Boolean): String {  
            val startVal = when (axis) {  
                Axis3D.X -> startX  
                Axis3D.Y -> startY  
                Axis3D.Z -> startZ  
            }  
            return when (axis) {  
                innerAxis -> startVal.toString()  
                middleAxis -> if (isCrossLayer) startVal.toString() else (stepM * middleStep).let { if (it == 0) "~" else "~$it" }  
                outerAxis -> (stepO * outerStep).let { if (it == 0) "~" else "~$it" }  
                else -> "~"  
            }  
        }  
  
        val outerTpX = getCoordStringLocal(Axis3D.X, stepM = 0, stepO = 1, isCrossLayer = true)  
        val outerTpY = getCoordStringLocal(Axis3D.Y, stepM = 0, stepO = 1, isCrossLayer = true)  
        val outerTpZ = getCoordStringLocal(Axis3D.Z, stepM = 0, stepO = 1, isCrossLayer = true)  
        val outerKey = "$outerTpX $outerTpY $outerTpZ"  
  
        val middleTpX = getCoordStringLocal(Axis3D.X, stepM = 1, stepO = 0, isCrossLayer = false)  
        val middleTpY = getCoordStringLocal(Axis3D.Y, stepM = 1, stepO = 0, isCrossLayer = false)  
        val middleTpZ = getCoordStringLocal(Axis3D.Z, stepM = 1, stepO = 0, isCrossLayer = false)  
        val middleKey = "$middleTpX $middleTpY $middleTpZ"  
  
        var pIdx = 0  
        val totalFiltered = solidBlocks.size  
  
        for (p in 0 until actualCount) {  
            if (pIdx >= totalFiltered || solidBlocks[pIdx].partition != p) {  
                continue  
            }  
  
            val startP = pIdx  
            while (pIdx < totalFiltered && solidBlocks[pIdx].partition == p) {  
                pIdx++  
            }  
  
            val pBlocks = solidBlocks.subList(startP, pIdx)  
            if (pBlocks.isEmpty()) continue  
  
            val first = pBlocks[0]  
            var currentI = first.i  
            var currentM = first.m  
            var currentO = first.o  
            var tickScore = first.score  
  
            for (idx in 0 until pBlocks.size - 1) {  
                val current = pBlocks[idx]  
                val next = pBlocks[idx + 1]  
                tickScore = current.score  
  
                while (currentO < next.o) {  
                    lineBreaks.getOrPut(outerKey) { ArrayList() }.add(tickScore)  
                    currentO++  
                    currentM = 0  
                    currentI = 0  
                    tickScore = nextScore(tickScore)  
                }  
  
                while (currentM < next.m) {  
                    lineBreaks.getOrPut(middleKey) { ArrayList() }.add(tickScore)  
                    currentM++  
                    currentI = 0  
                    tickScore = nextScore(tickScore)  
                }  
  
                val innerDistance = next.i - currentI  
                if (innerDistance > 0) {  
                    var remaining = innerDistance  
                    var power = 1  
                    while (remaining > 0) {  
                        if ((remaining and 1) == 1) {  
                            binaryTPs.getOrPut(power) { ArrayList() }.add(tickScore)  
                        }  
                        power = power shl 1  
                        remaining = remaining shr 1  
                    }  
                    tickScore = nextScore(tickScore)  
                }  
                currentI = next.i  
            }  
  
            val currentName = names[p]  
            var fx = startX.toLong()  
            var fy = startY.toLong()  
            var fz = startZ.toLong()  
            when (innerAxis) {  
                Axis3D.X -> fx += first.i * innerStep  
                Axis3D.Y -> fy += first.i * innerStep  
                Axis3D.Z -> fz += first.i * innerStep  
            }  
            when (middleAxis) {  
                Axis3D.X -> fx += first.m * middleStep  
                Axis3D.Y -> fy += first.m * middleStep  
                Axis3D.Z -> fz += first.m * middleStep  
            }  
            when (outerAxis) {  
                Axis3D.X -> fx += first.o * outerStep  
                Axis3D.Y -> fy += first.o * outerStep  
                Axis3D.Z -> fz += first.o * outerStep  
            }  
  
            emit("/summon armor_stand $fx $fy $fz ~ ~ . $currentName")  
            emit("/execute as @e[name=\"$currentName\"] run scoreboard players set @s $scoreboardObj ${first.score}")  
            killScores.add(pBlocks[pBlocks.size - 1].score)  
  
            for (current in pBlocks) {  
                if (current.isRepeatHead || current.isRepeatIntermediate || current.isRepeatTail) continue  
  
                if (current.id != "minecraft:air" && current.id != "air") {  
                    val statesStr = blockStatesToString(current.states)  
                    val fullBlock = if (statesStr.isEmpty()) current.id else "${current.id} $statesStr"  
                    blockMap.getOrPut(fullBlock) { ArrayList() }.add(current.score)  
                }  
  
                if (current.extraId != "minecraft:air" && current.extraId != "air") {  
                    waterMap.getOrPut(current.extraId) { ArrayList() }.add(current.score)  
                }  
  
                for (assist in current.assists) {  
                    assistMap.getOrPut(assist) { ArrayList() }.add(current.score)  
                }  
            }  
        }  
  
        emit("$")  
        emit("\n### 循环命令链 ###")  
        
        // ============================================================
        // 【修改注入点】：在循环链第一条游戏指令上方加上 "$2,0,0"
        // ============================================================
        emit("$2,0,0")
  
        var isFirstLoopInstructionEmitted = false
  
        // 辅助临时收集器，用于保证第二行加上 "$" 与第三行加上 "$1,0,1" 的逻辑能够精准切入第一条指令之后
        fun emitLoopCommand(cmd: String) {
            if (!isFirstLoopInstructionEmitted) {
                out.append(cmd).append('\n')
                out.append("$").append('\n')
                out.append("$1,0,1").append('\n')
                isFirstLoopInstructionEmitted = true
            } else {
                out.append(cmd).append('\n')
            }
        }
  
        for (p in 0 until actualCount) {  
            val pName = names[p]  
            val runs = partitionRepeatRuns[p].orEmpty()  
            if (runs.isEmpty()) continue  
  
            val summonScores = ArrayList<Int>(runs.size * 2)  
            for (run in runs) {  
                if (run.headScore == run.tailScore) {  
                    summonScores.add(run.headScore)  
                } else {  
                    summonScores.add(run.headScore)  
                    summonScores.add(run.tailScore)  
                }  
            }  
            val suffix = "_1"  
            for (cmd in buildPartitionSummonCommands("${pName}$suffix", summonScores)) emitLoopCommand(cmd)  
        }  
  
        for ((assist, scores) in assistMap) {  
            for (cmd in buildSetblockCommands(assist.blockId, scores, assist.dx, assist.dy, assist.dz)) emitLoopCommand(cmd)  
        }  
  
        for ((waterId, scores) in waterMap) {  
            for (cmd in buildSetblockCommands(waterId, scores)) emitLoopCommand(cmd)  
        }  
  
        for ((blockId, scores) in blockMap) {  
            for (cmd in buildSetblockCommands(blockId, scores)) emitLoopCommand(cmd)  
        }  
  
        val pFactor = lenInner / 4.0  
        var kFactor = 1.0  
        while (kFactor < pFactor) kFactor *= 2.0  
  
        val projectionSegments = ArrayList<Double>()  
        var stepK = kFactor  
        while (stepK >= 0.5) {  
            projectionSegments.add(stepK)  
            stepK /= 2.0  
        }  
  
        val offsetStrX = if (innerAxis == Axis3D.X) "~-1" else "~"  
        val offsetStrY = if (innerAxis == Axis3D.Y) "~-1" else "~"  
        val offsetStrZ = if (innerAxis == Axis3D.Z) "~-1" else "~"  
  
        for (p in 0 until actualCount) {  
            val pName = names[p]  
            val suffix = "_1"  
            val runs = partitionRepeatRuns[p].orEmpty()  
  
            if (runs.isNotEmpty()) {  
                val selector = "@e[name=\"${pName}${suffix}\"]"  
                val sbC = StringBuilder()  
                for (v in projectionSegments) {  
                    val vStr = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()  
                    sbC.append(" facing entity ").append(selector).append(" feet positioned ^ ^ ^").append(vStr)  
                }  
                val facingPart = sbC.toString()  
  
                val runsByBlock = LinkedHashMap<String, MutableList<RepeatRunInfo>>()  
                for (run in runs) {  
                    runsByBlock.getOrPut(run.blockStr) { ArrayList() }.add(run)  
                }  
  
                for ((blockStr, blockRuns) in runsByBlock) {  
                    val tailScores = ArrayList<Int>(blockRuns.size)  
                    for (run in blockRuns) tailScores.add(run.tailScore)  
                    val executionPart = " as @e[name=\"${pName}${suffix}\"] at @s$facingPart run setblock $offsetStrX $offsetStrY $offsetStrZ $blockStr"  
                    for (cmd in buildProjectionSetblockCommands(tailScores, executionPart)) emitLoopCommand(cmd)  
                }  
            }  
        }  
  
        val finalKillTargets = ArrayList<Pair<String, List<Int>>>()
        for (i in 0 until actualCount) {
            val runs = partitionRepeatRuns[i]
            if (!runs.isNullOrEmpty()) {
                finalKillTargets.add(names[i] to runs.map { it.tailScore })
            }
        }
        if (finalKillTargets.isNotEmpty()) {
            for (cmd in buildFinalKillCommands(finalKillTargets)) emitLoopCommand(cmd)
        }
        for ((tpCoords, scores) in lineBreaks) {  
            val parts = tpCoords.split(' ')  
            if (parts.size == 3) {  
                for (cmd in buildTpCommands(scores, parts[0], parts[1], parts[2])) emitLoopCommand(cmd)  
            }  
        }  
  
        val sortedPowers = binaryTPs.keys.sorted()  
        for (power in sortedPowers) {  
            val scores = binaryTPs[power].orEmpty()  
            val (dx, dy, dz) = getWorldDelta(power, 0, 0)  
            val tpX = if (dx == 0) "~" else "~$dx"  
            val tpY = if (dy == 0) "~" else "~$dy"  
            val tpZ = if (dz == 0) "~" else "~$dz"  
            for (cmd in buildTpCommands(scores, tpX, tpY, tpZ)) emitLoopCommand(cmd)  
        }  
  
        val killScoreSelector = getConsecutiveRanges(killScores).joinToString(",") { "$scoreboardObj=!$it" }  
        emitLoopCommand("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] unless entity @s[scores={$killScoreSelector}] run kill @s")  
        emitLoopCommand("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] run scoreboard players add @s $scoreboardObj 1")  
        
        // ============================================================
        // 【修改注入点】：在最后一条游戏指令（即上面add 1分那条）的下面加上 "$"
        // ============================================================
        emit("$")
  
        return out.toString()  
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
        if (numbers.size == 1) return listOf(numbers[0].toString())

        var sorted = true
        var prev = numbers[0]
        for (i in 1 until numbers.size) {
            val cur = numbers[i]
            if (cur < prev) {
                sorted = false
                break
            }
            prev = cur
        }

        val source = if (sorted) {
            numbers
        } else {
            numbers.distinct().sorted()
        }

        val ranges = ArrayList<String>()
        var start = source[0]
        var last = source[0]
        for (i in 1 until source.size) {
            val cur = source[i]
            if (cur == last) continue
            if (cur == last + 1) {
                last = cur
            } else {
                ranges.add(if (start == last) "$start" else "$start..$last")
                start = cur
                last = cur
            }
        }
        ranges.add(if (start == last) "$start" else "$start..$last")
        return ranges
    }

    private fun buildSafeRanges(numbers: List<Int>): List<RangeResult> {
        if (numbers.isEmpty()) return emptyList()

        val results = ArrayList<RangeResult>()
        val allTimesInChain: Set<Int> = numbers.toSet()

        var start = numbers[0]
        var prev = numbers[0]
        for (i in 1 until numbers.size) {
            val cur = numbers[i]
            if (cur < prev) {
                return buildSafeRanges(numbers.distinct().sorted())
            }
            if (cur != prev + 1) {
                results.add(getSafeRange(start, prev, allTimesInChain))
                start = cur
            }
            prev = cur
        }
        results.add(getSafeRange(start, prev, allTimesInChain))
        return results
    }
    
    private fun getSafeRange(start: Int, end: Int, allTimesInChain: Set<Int>): RangeResult {
    var finalStart = start
    var finalEnd = end
    val res = RangeResult(selectorPart = "")

    // 1. 基础违禁数值检查
    if (forbiddenScores.contains(start) && !isConsecutiveForbidden(start)) {
        finalStart = start - 1
        if (!allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)
    }

    if (forbiddenScores.contains(end) && !isConsecutiveForbidden(end)) {
        finalEnd = end + 1
        if (!allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)
    }

    // 2. 贴合真实语境的敏感词检测与规避
    if (detector != null) {
        if (start == end) {
            // 单个数场景：严格模拟 "n=!s," 形式进行审查
            val testStr = "n=!$start,"
            if (detector.matchText(testStr)) {
                finalStart = start - 1
                finalEnd = end + 1
                if (!allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)
                if (!allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)
            }
        } else {
            // 两个数区间场景：严格模拟 "n=!s..e," 形式进行审查
            val testStr = "n=!$start..$end,"
            if (detector.matchText(testStr)) {
                // 如果整个选择器区间串触发了敏感（如 n=!64..89,），则对前方数值进行偏移规避
                finalStart = start - 1
                if (!allTimesInChain.contains(finalStart)) {
                    res.corrections.add(finalStart)
                }
            } else {
                // 如果整体安全，再分别确认在独立放入选择器时边界是否安全（如分开生成指令时的场景）
                val startSensitive = detector.matchText("n=!$start,")
                val endSensitive = detector.matchText("n=!$end,")
                if (startSensitive) {
                    finalStart = start - 1
                    if (!allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)
                }
                if (endSensitive) {
                    finalEnd = end + 1
                    if (!allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)
                }
            }
        }
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

    private fun buildFinalKillCommands(targets: List<Pair<String, List<Int>>>): List<String> {    
        val cmds = mutableListOf<String>()    
        for ((assistName, scores) in targets) {    
            val rangeResults = buildSafeRanges(scores)    
            if (rangeResults.isEmpty()) continue    
                
            val currentSelectors = mutableListOf<String>()    
            val currentCorrections = TreeSet<Int>()    
            val executionPart = " as @e unless entity @s[name=!${assistName}_1] run kill @s"    
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
        }    
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
        private var enableExtraSensitiveOptimization: Boolean = true

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
        fun setEnableExtraSensitiveOptimization(enable: Boolean) = apply { 
            this.enableExtraSensitiveOptimization = enable 
        } 

        fun build(): BinaryMcStructureCommandGenerator = BinaryMcStructureCommandGenerator(    
            structureFile, scoreboardObj, entityName, charLimit,    
            startX, startY, startZ, mirrorX, mirrorY, mirrorZ,    
            innerAxis, innerStep, middleAxis, middleStep, outerAxis, outerStep,    
            startScoreOffset, forbiddenScores, generationMultiplier, mappingJsonRules, bedrockTargetVersion,    
            assistRules, skipConfig, enableExtraSensitiveOptimization
        )    
    }
}
