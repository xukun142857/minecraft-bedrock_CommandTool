package command.plus

import java.io.File
import java.io.FileOutputStream
import java.util.TreeSet
import java.util.regex.Pattern

/**
 * 我的世界 Minecraft 盔甲架命令生成器 (多实体增强版) - 已修复首行置位TP偏移Bug
 */
class McCommandGenerator private constructor(
    private val rawMatrix: List<List<String>>?,
    private val inputData: String?,
    private val scoreboardObj: String,
    private val entityName: String,
    private val charLimit: Int,
    private val startLength: Int, // X
    private val startDepth: Int,   // Y
    private val startWidth: Int,   // Z
    private val mirrorHorizontal: Boolean,
    private val mirrorVertical: Boolean,
    private val innerAxis: Axis,
    private val innerStep: Int,
    private val outerAxis: Axis,
    private val outerStep: Int,
    private val startScoreOffset: Int,
    private val forbiddenScores: Set<Int>,
    private val generationMultiplier: Int, // 生成倍数
    private val enableExtraSensitiveOptimization: Boolean, // 【新增】是否开启附加违禁数值优化
    private val saveFile: File?,
    private val callback: (List<String>) -> Unit
) {

    enum class Axis { 长, 深, 宽 }

    private data class RangeResult(
        var selectorPart: String,
        val corrections: MutableList<Int> = mutableListOf()
    )

    // 【新增】网易敏感数值检测器 Kotlin 移植版
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

    private val detector = if (enableExtraSensitiveOptimization) NeteaseSensitiveDetector() else null

    fun generate() {
        try {
            // 【优化】优先使用直接传入的矩阵，若没有才解析 inputData
            var matrix = rawMatrix ?: parseInput(inputData ?: "")
            if (matrix.isEmpty()) {
                callback(emptyList())
                return
            }

            matrix = applyMirror(matrix)
            val commands = generateCommands(matrix)

            saveFile?.let { file ->
                try {
                    file.parentFile?.mkdirs()
                    // 【优化】使用 BufferedWriter 配合大缓冲区流式写入，避免全量内存开销
                    file.bufferedWriter(Charsets.UTF_8, bufferSize = 65536).use { writer ->
                        commands.forEach { line ->
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            callback(commands)
        } catch (e: Exception) {
            e.printStackTrace()
            callback(emptyList())
        }
    }

    private fun parseInput(data: String): List<List<String>> {
        val matrix = mutableListOf<List<String>>()
        val lines = data.lines()
        val regex = "'([^']*)'|\"([^\"]*)\"".toRegex()

        for (line in lines) {
            var cleanLine = line.trim()
            if (cleanLine.contains(".")) {
                cleanLine = cleanLine.substringAfter(".").trim()
            }
            if (cleanLine.isEmpty()) continue

            val row = regex.findAll(cleanLine).map {
                it.groupValues[1].ifEmpty { it.groupValues[2] }
            }.toList()

            if (row.isNotEmpty()) {
                matrix.add(row)
            }
        }
        return matrix
    }

    private fun applyMirror(matrix: List<List<String>>): List<List<String>> {
        var processed = matrix
        if (mirrorHorizontal) processed = processed.map { it.reversed() }
        if (mirrorVertical) processed = processed.reversed()
        return processed
    }

    private fun getConsecutiveRanges(numbers: List<Int>): List<String> {
        if (numbers.isEmpty()) return emptyList()
        val sorted = numbers.distinct().sorted()
        val ranges = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]

        for (i in 1 until sorted.size) {
            val x = sorted[i]
            if (x == prev + 1) {
                prev = x
            } else {
                ranges.add(if (start == prev) "$start" else "$start..$prev")
                start = x
                prev = x
            }
        }
        ranges.add(if (start == prev) "$start" else "$start..$prev")
        return ranges
    }

    private fun getSafeRange(start: Int, end: Int, allTimesInChain: Set<Int>): RangeResult {
        var finalStart = start
        var finalEnd = end
        val res = RangeResult(selectorPart = "")

        // 1. 基础违禁数值检查（保留基础黑名单过滤）
        if (forbiddenScores.contains(start) && !isConsecutiveForbidden(start)) {
            finalStart = start - 1
            if (!allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)
        }

        if (forbiddenScores.contains(end) && !isConsecutiveForbidden(end)) {
            finalEnd = end + 1
            if (!allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)
        }

        // 2. 网易敏感词精细化检测与规避
        if (detector != null) {
            if (start == end) {
                // 【单数场景】：严格检查 "obj=!s," 形式
                val testStr = "$scoreboardObj=!$start,"
                if (detector.matchText(testStr)) {
                    finalStart = start - 1
                    finalEnd = end - 1
                    if (!allTimesInChain.contains(finalStart)) {
                        res.corrections.add(finalStart)
                    }
                }
            } else {
                // 【区间场景】：拆解单值敏感性与组合敏感性
                val testStrRange = "$scoreboardObj=!$start..$end,"
                val startSensitive = detector.matchText("$scoreboardObj=!$start,")
                val endSensitive = detector.matchText("$scoreboardObj=!$end,")
                val rangeSensitive = detector.matchText(testStrRange)

                if (rangeSensitive && !startSensitive && !endSensitive) {
                    // 场景 A：单独看 start 和 end 都不违规，但组合起来（如 "bc..de"）触发敏感
                    // 仅偏移前面的 start (start - 1) 打断组合
                    finalStart = start - 1
                    if (!allTimesInChain.contains(finalStart)) {
                        res.corrections.add(finalStart)
                    }
                } else {
                    // 场景 B/C：单纯由 start 或 end 单值引起的违规，按需单独偏移
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

        // 3. 组装最终的选择器文本
        res.selectorPart = if (finalStart == finalEnd) {
            "$scoreboardObj=!$finalStart"
        } else {
            "$scoreboardObj=!$finalStart..$finalEnd"
        }
        return res
    }

    private fun isConsecutiveForbidden(valNum: Int): Boolean = forbiddenScores.contains(valNum - 1) || forbiddenScores.contains(valNum + 1)

    private fun buildSafeRanges(numbers: List<Int>): List<RangeResult> {
        if (numbers.isEmpty()) return emptyList()
        val sorted = numbers.distinct().sorted()
        val allTimesInChain = sorted.toSet()
        val results = mutableListOf<RangeResult>()
        var start = sorted[0]
        var prev = sorted[0]

        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur != prev + 1) {
                results.add(getSafeRange(start, prev, allTimesInChain))
                start = cur
            }
            prev = cur
        }
        results.add(getSafeRange(start, prev, allTimesInChain))
        return results
    }

    private fun buildSetblockCommands(blockId: String, scores: List<Int>): List<String> {
        val rangeResults = buildSafeRanges(scores)
        if (rangeResults.isEmpty()) return emptyList()

        val cmds = mutableListOf<String>()
        val currentSelectors = mutableListOf<String>()
        val currentCorrections = TreeSet<Int>()

        val prefix = "/execute as @e at @s unless entity @s[scores={"
        val suffixStart = "}]"
        val runPart = " run setblock ~ ~ ~ $blockId"

        var currentLen = prefix.length + suffixStart.length + runPart.length + 10

        for (rr in rangeResults) {
            val partLen = rr.selectorPart.length + 1
            var correctionExtraLen = 0
            for (c in rr.corrections) {
                if (!currentCorrections.contains(c)) {
                    correctionExtraLen += (scoreboardObj.length + c.toString().length + 2)
                }
            }
            if (currentCorrections.isEmpty() && rr.corrections.isNotEmpty()) correctionExtraLen += 30

            if (charLimit > 0 && currentLen + partLen + correctionExtraLen > charLimit) {
                flushSetblockCommand(cmds, currentSelectors, currentCorrections, blockId)
                currentSelectors.clear()
                currentCorrections.clear()
                currentLen = prefix.length + suffixStart.length + runPart.length + 10
            }

            currentSelectors.add(rr.selectorPart)
            currentCorrections.addAll(rr.corrections)
            currentLen += (partLen + correctionExtraLen)
        }

        flushSetblockCommand(cmds, currentSelectors, currentCorrections, blockId)
        return cmds
    }

    private fun flushSetblockCommand(
        out: MutableList<String>,
        selectors: List<String>,
        corrections: Set<Int>,
        blockId: String
    ) {
        if (selectors.isEmpty()) return

        val sb = StringBuilder()

        sb.append("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={")
            .append(selectors.joinToString(","))
            .append("}]")

        if (corrections.isNotEmpty()) {
            sb.append(" if entity @s[scores={")

            sb.append(
                corrections.joinToString(",") {
                    "$scoreboardObj=!$it"
                }
            )

            sb.append("}]")
        }

        sb.append(" run setblock ~ ~ ~ ").append(blockId)
        out.add(sb.toString())
    }

    private fun generateCommands(matrix: List<List<String>>): List<String> {
        val cmds = mutableListOf<String>()
        val rowLength = matrix.firstOrNull()?.size ?: 0 
        val totalRows = matrix.size
        val totalBlocks = rowLength * totalRows
        
        if (totalBlocks == 0) return emptyList()

        val actualCount = if (totalBlocks % generationMultiplier == totalBlocks) 1 else generationMultiplier
        val names = List(actualCount) { if (it == 0) entityName else "$entityName$it" }
        val unlessNamesPart = names.joinToString(",") { "name=!$it" }

        // ===== 1. 初始化部分 (tickingarea & summon) =====
        cmds.add("### 初始化指令 ###")
        cmds.add("$0,0,0")
        cmds.add("/tickingarea remove_all")
        cmds.add("$")
        cmds.add("$1,0,1")

        val endLength = startLength + (if (innerAxis == Axis.长) (rowLength - 1) * innerStep else 0) + (if (outerAxis == Axis.长) (totalRows - 1) * outerStep else 0)
        val endWidth = startWidth + (if (innerAxis == Axis.宽) (rowLength - 1) * innerStep else 0) + (if (outerAxis == Axis.宽) (totalRows - 1) * outerStep else 0)
        val minX = minOf(startLength, endLength); val maxX = maxOf(startLength, endLength)
        val minZ = minOf(startWidth, endWidth); val maxZ = maxOf(startWidth, endWidth)

        for (sx in minX..maxX step 160) {
            for (sz in minZ..maxZ step 160) {
                val ex = minOf(sx + 159, maxX)
                val ez = minOf(sz + 159, maxZ)
                cmds.add("/tickingarea add $sx 0 $sz $ex 0 $ez")
            }
        }
        cmds.add("$")
        cmds.add("$0,0,0")
        cmds.add("/scoreboard objectives add $scoreboardObj dummy")
        cmds.add("$")

        cmds.add("$1,0,1")
        
        val avg = totalBlocks / actualCount
        val remainder = totalBlocks % actualCount
        
        val taskSizes = IntArray(actualCount) { i ->
            if (i < remainder) avg + 1 else avg
        }

        var currentGlobalPos = 1 
        val killScores = mutableListOf<Int>()
        val initialScoresList = mutableListOf<Int>()

        for (n in 0 until actualCount) {
            val currentName = names[n]
            val posIdx = currentGlobalPos 

            val row = (posIdx - 1) / rowLength
            val col = (posIdx - 1) % rowLength
            
            val offsetInner = col.toLong() * innerStep
            val offsetOuter = row.toLong() * outerStep
            
            val finalScore = posIdx 

            val finalCoords = mutableMapOf(
                Axis.长 to startLength.toLong(),
                Axis.深 to startDepth.toLong(),
                Axis.宽 to startWidth.toLong()
            )
            finalCoords[innerAxis] = finalCoords[innerAxis]!! + offsetInner
            finalCoords[outerAxis] = finalCoords[outerAxis]!! + offsetOuter

            val initialScore = startScoreOffset + finalScore
            initialScoresList.add(initialScore)

            cmds.add("/summon armor_stand ${finalCoords[Axis.长]} ${finalCoords[Axis.深]} ${finalCoords[Axis.宽]} ~ ~ . $currentName")
            cmds.add("/execute as @e[name=\"$currentName\"] run scoreboard players set @s $scoreboardObj $initialScore")

            currentGlobalPos += taskSizes[n]
            
            if (n < actualCount - 1) {
                killScores.add(startScoreOffset + currentGlobalPos)
            }
        }
        killScores.add(startScoreOffset + totalBlocks + 1)
        cmds.add("$")

        // ===== 2. 循环部分 (换行 TP) =====
        cmds.add("")
        cmds.add("### 循环命令链 ###")
        cmds.add("$2,0,0")
        
        val rowEndIndices = (1..totalRows - 1).map { it * rowLength }
        val jumpScores = rowEndIndices.map { startScoreOffset + it + 1 }
            .filter { it !in initialScoresList }
        
        if (jumpScores.isNotEmpty()) {
            val jumpRanges = getConsecutiveRanges(jumpScores)
            val scoreSelector = jumpRanges.joinToString(",") { "$scoreboardObj=!$it" }
            val tpCoords = arrayOf("~", "~", "~")
            when (innerAxis) {
                Axis.长 -> tpCoords[0] = startLength.toString()
                Axis.深 -> tpCoords[1] = startDepth.toString()
                Axis.宽 -> tpCoords[2] = startWidth.toString()
            }
            val idxOuter = when(outerAxis) { Axis.长 -> 0; Axis.深 -> 1; Axis.宽 -> 2 }
            tpCoords[idxOuter] = "~$outerStep"
            
            cmds.add("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] at @s unless entity @s[scores={$scoreSelector}] run tp ${tpCoords.joinToString(" ")}")
        }
        cmds.add("$")

        // ===== 3. 连锁部分 (放置方块与步进) =====
        cmds.add("$1,0,1")
        
        val flatBlocks = matrix.flatten()
        val blockMap = mutableMapOf<String, MutableList<Int>>()
        flatBlocks.forEachIndexed { i, blockId ->
            blockMap.getOrPut(blockId) { mutableListOf() }.add(startScoreOffset + i + 1)
        }
        for ((blockId, scores) in blockMap) {
            cmds.addAll(buildSetblockCommands(blockId, scores))
        }

        val stepCoords = arrayOf("~", "~", "~")
        val innerIdx = when(innerAxis) { Axis.长 -> 0; Axis.深 -> 1; Axis.宽 -> 2 }
        stepCoords[innerIdx] = "~$innerStep"
        
        cmds.add("/execute as @e unless entity @s[$unlessNamesPart] at @s run tp ${stepCoords.joinToString(" ")}")
        cmds.add("/execute as @e unless entity @s[$unlessNamesPart] run scoreboard players add @s $scoreboardObj 1")

        // ===== 4. Kill 逻辑 =====
        val killScoreRanges = getConsecutiveRanges(killScores)
        val killScoreSelector = killScoreRanges.joinToString(",") { "$scoreboardObj=!$it" }
        
        cmds.add("/execute as @e[scores={$scoreboardObj=$startScoreOffset..}] unless entity @s[scores={$killScoreSelector}] run kill @s")
        cmds.add("$")

        return cmds
    }

    class Builder {
        private var saveFile: File? = null
        private var rawMatrix: List<List<String>>? = null
        private var inputData: String? = null
        private var scoreboardObj: String = "n"
        private var entityName: String = "C"
        private var charLimit: Int = 10000
        private var startLength: Int = 0
        private var startDepth: Int = 0
        private var startWidth: Int = 0
        private var mirrorHorizontal: Boolean = false
        private var mirrorVertical: Boolean = false
        private var innerAxis: Axis = Axis.长
        private var innerStep: Int = 1
        private var outerAxis: Axis = Axis.宽
        private var outerStep: Int = 1
        private var startScoreOffset: Int = 0
        private var forbiddenScores: Set<Int> = emptySet()
        private var generationMultiplier: Int = 1 
        private var enableExtraSensitiveOptimization: Boolean = true // 【新增】默认为 false
        private var callback: ((List<String>) -> Unit)? = null

        fun setRawMatrix(matrix: List<List<String>>) = apply { this.rawMatrix = matrix }
        fun setInputData(data: String) = apply { this.inputData = data }
        fun setScoreboardObj(obj: String) = apply { this.scoreboardObj = obj }
        fun setEntityName(name: String) = apply { this.entityName = name }
        fun setCharLimit(limit: Int) = apply { this.charLimit = limit }
        fun setStartCoords(长: Int, 深: Int, 宽: Int) = apply {
            this.startLength = 长
            this.startDepth = 深
            this.startWidth = 宽
        }
        fun setMirror(horizontal: Boolean, vertical: Boolean) = apply {
            this.mirrorHorizontal = horizontal
            this.mirrorVertical = vertical
        }
        fun setAxisConfig(innerAxis: Axis, innerStep: Int, outerAxis: Axis, outerStep: Int) = apply {
            require(innerAxis != outerAxis) { "内部行进轴和换行轴不能是同一个！" }
            this.innerAxis = innerAxis
            this.innerStep = innerStep
            this.outerAxis = outerAxis
            this.outerStep = outerStep
        }
        fun setStartScoreOffset(offset: Int) = apply { this.startScoreOffset = offset }
        fun setForbiddenScores(values: Set<Int>) = apply { this.forbiddenScores = values }
        fun setGenerationMultiplier(m: Int) = apply { this.generationMultiplier = if (m < 1) 1 else m }
        fun setEnableExtraSensitiveOptimization(enable: Boolean) = apply { this.enableExtraSensitiveOptimization = enable } // 【新增】设置方法
        fun setCallback(callback: (List<String>) -> Unit) = apply { this.callback = callback }
        fun setOutputFile(path: String) = apply { this.saveFile = File(path) }
        fun setOutputFile(file: File) = apply { this.saveFile = file }

        fun build(): McCommandGenerator {
            requireNotNull(callback) { "必须设置 Callback" }
            return McCommandGenerator(
                rawMatrix, inputData, scoreboardObj, entityName, charLimit, startLength, startDepth, startWidth,
                mirrorHorizontal, mirrorVertical, innerAxis, innerStep, outerAxis, outerStep,
                startScoreOffset, forbiddenScores, generationMultiplier, enableExtraSensitiveOptimization, saveFile, callback!!
            )
        }
    }
}