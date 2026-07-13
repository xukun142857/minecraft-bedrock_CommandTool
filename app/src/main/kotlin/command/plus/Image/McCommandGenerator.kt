package command.plus

import java.io.File
import java.io.FileOutputStream
import java.util.TreeSet

/**
 * 我的世界 Minecraft 盔甲架命令生成器 (多实体增强版) - 已修复首行置位TP偏移Bug
 */
class McCommandGenerator private constructor(
    private val inputData: String,
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
    private val saveFile: File?,
    private val callback: (List<String>) -> Unit
) {

    enum class Axis { 长, 深, 宽 }

    private data class RangeResult(
        var selectorPart: String,
        val corrections: MutableList<Int> = mutableListOf()
    )

    fun generate() {
        try {
            var matrix = parseInput(inputData)
            if (matrix.isEmpty()) {
                callback(emptyList())
                return
            }

            matrix = applyMirror(matrix)
            val commands = generateCommands(matrix)

            saveFile?.let { file ->
                try {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        commands.forEach { line ->
                            fos.write((line + "\n").toByteArray(Charsets.UTF_8))
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

        if (forbiddenScores.contains(start) && !isConsecutiveForbidden(start)) {
            finalStart = start - 1
            if (!allTimesInChain.contains(finalStart)) res.corrections.add(finalStart)
        }

        if (forbiddenScores.contains(end) && !isConsecutiveForbidden(end)) {
            finalEnd = end + 1
            if (!allTimesInChain.contains(finalEnd)) res.corrections.add(finalEnd)
        }

        res.selectorPart = if (finalStart == finalEnd) "$scoreboardObj=!$finalStart" else "$scoreboardObj=!$finalStart..$finalEnd"
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
        // 【新增】用于记录所有实体初始分数的集合
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
            // 【新增】保存当前实体的初始分数
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
        // 【修改】通过 .filter 断开冲突：如果换行分数正好是某个实体的初始诞生分数，直接将其剔除！
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
        private var inputData: String = ""
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
        private var callback: ((List<String>) -> Unit)? = null

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
        fun setCallback(callback: (List<String>) -> Unit) = apply { this.callback = callback }
        fun setOutputFile(path: String) = apply { this.saveFile = File(path) }
        fun setOutputFile(file: File) = apply { this.saveFile = file }

        fun build(): McCommandGenerator {
            requireNotNull(callback) { "必须设置 Callback" }
            return McCommandGenerator(
                inputData, scoreboardObj, entityName, charLimit, startLength, startDepth, startWidth,
                mirrorHorizontal, mirrorVertical, innerAxis, innerStep, outerAxis, outerStep,
                startScoreOffset, forbiddenScores, generationMultiplier, saveFile, callback!!
            )
        }
    }
}