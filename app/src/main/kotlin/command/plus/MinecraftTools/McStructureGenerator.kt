package command.plus

import java.io.*
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

// 1. 扩展数据模型：增加 extraId 用于支持第二层（含水层）
data class BlockInput(
    val x: Int,
    val y: Int,
    val z: Int,
    val id: String,
    val states: Map<String, Any?> = emptyMap(),
    val blockEntityNbt: Map<String, Any?>? = null,
    val extraId: String = "minecraft:air" // 默认第二层为空气
)

// 2. 扩展结构化数据：增加 extraIndices 数组存储第二层索引
class LoadedStructure(
    val width: Int,
    val height: Int,
    val depth: Int,
    val primaryIndices: IntArray,
    val extraIndices: IntArray, // 存储含水层数据
    val palette: List<McStructureExporter.BlockStateKey>,
    val blockPositionData: Map<Int, Map<String, Any?>>
)

object McStructureExporter {

    data class PrefixRule(
        val prefix: String,
        val states: Map<String, Any?>
    )

    data class BlockStateKey(
        val name: String,
        val states: Map<String, Any?>
    )
    
    private fun applyPrefixEncode(
        blockId: String,
        states: Map<String, Any?>,
        prefixRules: List<PrefixRule>
    ): String {
        val rule = prefixRules.firstOrNull { rule ->
            rule.states.all { (k, v) -> states[k] == v }
        }
        return if (rule != null) rule.prefix + blockId else blockId
    }

    private fun applyPrefixDecode(
        rawId: String,
        prefixRules: List<PrefixRule>
    ): Pair<String, Map<String, Any?>> {
        val rule = prefixRules.firstOrNull { rawId.startsWith(it.prefix) }
        return if (rule != null) {
            rawId.removePrefix(rule.prefix) to rule.states
        } else {
            rawId to emptyMap()
        }
    }

   
    // --- 功能 1: 解析 mcstructure 文件（零内存浪费流式读取优化版） ---
    fun load(inputFile: File): LoadedStructure {
        val fis = FileInputStream(inputFile)
        val bis = BufferedInputStream(fis)
        
        var root: NbtTag.CompoundTag? = NbtReader(bis).readRoot() as NbtTag.CompoundTag

        // 兼容处理可能被底层重构的 size 节点
        val size = when (val sizeTag = root!!.value["size"]) {
            is NbtTag.IntArrayTag -> sizeTag.value.toList()
            is NbtTag.ListTag -> sizeTag.items.map { (it as NbtTag.IntTag).value }
            else -> throw IOException("Invalid size tag")
        }
        val width = size[0]
        val height = size[1]
        val depth = size[2]
        val totalVolume = width * height * depth

        val structure = root.value["structure"] as NbtTag.CompoundTag
        val blockIndices = structure.value["block_indices"] as NbtTag.ListTag
        val blockIndicesList = blockIndices.items
        
        // ⚡ 【第 0 层提取】由于底层已经是 IntArray，直接拿出来复用，省下 44.45MB 内存和全部的遍历时间！
        val primaryIndices = when (val layer0 = blockIndicesList[0]) {
            is NbtTag.IntArrayTag -> layer0.value
            is NbtTag.ListTag -> { // 预留传统模式兼容
                val array = IntArray(totalVolume)
                val items = layer0.items
                for (i in 0 until totalVolume) {
                    if (i < items.size) array[i] = (items[i] as NbtTag.IntTag).value
                }
                array
            }
            else -> IntArray(totalVolume) { -1 }
        }

        // ⚡ 【第 1 层提取】同理直接复用，再次免去 44.45MB 的分配开销
        val extraIndices = if (blockIndicesList.size > 1) {
            when (val layer1 = blockIndicesList[1]) {
                is NbtTag.IntArrayTag -> layer1.value
                is NbtTag.ListTag -> {
                    val array = IntArray(totalVolume) { -1 }
                    val items = layer1.items
                    for (i in 0 until totalVolume) {
                        if (i < items.size) array[i] = (items[i] as NbtTag.IntTag).value
                    }
                    array
                }
                else -> IntArray(totalVolume) { -1 }
            }
        } else {
            IntArray(totalVolume) { -1 }
        }

        val paletteData = (structure.value["palette"] as NbtTag.CompoundTag).value["default"] as NbtTag.CompoundTag
        val blockPalette = paletteData.value["block_palette"] as NbtTag.ListTag
        
        val palette = blockPalette.items.map { tag ->
            val t = tag as NbtTag.CompoundTag
            BlockStateKey(
                name = (t.value["name"] as NbtTag.StringTag).value,
                states = stateToMap(t.value["states"] as NbtTag.CompoundTag)
            )
        }

        val blockPosDataTag = paletteData.value["block_position_data"] as? NbtTag.CompoundTag
        val blockPositionData = mutableMapOf<Int, Map<String, Any?>>()
        blockPosDataTag?.value?.forEach { (k, v) ->
            blockPositionData[k.toInt()] = nbtToMap(v as NbtTag.CompoundTag)
        }

        // 彻底释放原始庞大的 NBT 树
        root = null 
        System.gc() 

        return LoadedStructure(width, height, depth, primaryIndices, extraIndices, palette, blockPositionData)
    }

    // --- 功能 2: 输入坐标读取方块详细信息（已修复含水数据提取） ---
    fun getBlockAt(loaded: LoadedStructure, x: Int, y: Int, z: Int): BlockInput? {
        if (x !in 0 until loaded.width || y !in 0 until loaded.height || z !in 0 until loaded.depth) return null
        
        val idx = indexOf(x, y, z, loaded.width, loaded.height, loaded.depth)
        val paletteIdx = loaded.primaryIndices[idx]
        val extraPaletteIdx = loaded.extraIndices[idx]
        
        if (paletteIdx == -1) return null
        
        val state = loaded.palette[paletteIdx]
        
        // 修复：解析含水层的方块 ID（通常为 minecraft:water）
        val extraId = if (extraPaletteIdx != -1 && extraPaletteIdx < loaded.palette.size) {
            loaded.palette[extraPaletteIdx].name
        } else {
            "minecraft:air"
        }

        val entityData = loaded.blockPositionData[idx]?.get("block_entity_data") as? Map<String, Any?>

        return BlockInput(x, y, z, state.name, state.states, entityData, extraId)
    }

    // --- 功能 3: 读取所有方块 ID 并输出标准的三维阵列字符串 [y[z[x]]] ---
    fun exportIdGridString(
        loaded: LoadedStructure,
        prefixRules: List<PrefixRule> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("[\n")

        for (y in 0 until loaded.height) {
            sb.append("  [")
            val zLayers = mutableListOf<String>()

            for (z in 0 until loaded.depth) {
                val xRow = mutableListOf<String>()
                for (x in 0 until loaded.width) {
                    val idx = indexOf(x, y, z, loaded.width, loaded.height, loaded.depth)
                    val pIdx = loaded.primaryIndices[idx]

                    val rawId = if (pIdx != -1 && pIdx < loaded.palette.size) {
                        loaded.palette[pIdx].name
                    } else {
                        "minecraft:air"
                    }

                    val states = if (pIdx != -1 && pIdx < loaded.palette.size) {
                        loaded.palette[pIdx].states
                    } else {
                        emptyMap()
                    }

                    val idWithPrefix = applyPrefixEncode(rawId, states, prefixRules)
                    xRow.add("\"$idWithPrefix\"")
                }
                zLayers.add(xRow.joinToString(", ", prefix = "[", postfix = "]"))
            }

            sb.append(zLayers.joinToString(",\n    "))
            sb.append("]")
            if (y < loaded.height - 1) sb.append(",\n")
        }

        sb.append("\n]")
        return sb.toString()
    }
    
    // --- 功能 4: 采用建筑紧凑优化格式恢复 mcstructure ---
    fun importFromGridString(
        gridString: String,
        outputDir: File,
        prefixRules: List<PrefixRule> = emptyList(),
        limitX: Int = 64,
        limitZ: Int = 64
    ) {
        val blocks = mutableListOf<BlockInput>()
        
        var depth = 0
        var y = -1
        var z = -1
        var x = 0

        val sb = StringBuilder()
        var inQuote = false

        var i = 0
        while (i < gridString.length) {
            val c = gridString[i]
            
            if (c == '"' || c == '\'') {
                inQuote = !inQuote
                if (!inQuote) {
                    val id = sb.toString().trim()
                    if (id.isNotEmpty()) {
                        val cleanRawId = if (id.contains(":")) id else "minecraft:$id"
                        val (baseId, extraStates) = applyPrefixDecode(cleanRawId, prefixRules)
                        
                        // 即使是空气也先记录下来，以便保留建筑内部的空腔结构
                        blocks.add(BlockInput(x, y, z, baseId, extraStates))
                    }
                    sb.setLength(0)
                    x++ 
                }
            } else if (inQuote) {
                sb.append(c)
            } else {
                when (c) {
                    '[' -> {
                        depth++
                        if (depth == 2) { y++; z = -1 } 
                        else if (depth == 3) { z++; x = 0 }  
                    }
                    ']' -> {
                        depth--
                    }
                }
            }
            i++
        }

        // 如果完全没有读取到方块，直接输出一个空的默认结构
        if (blocks.isEmpty()) {
            val defaultFile = File(outputDir, "part_x0_z0.mcstructure")
            export(listOf(BlockInput(0, 0, 0, "minecraft:air")), defaultFile)
            return
        }

        // 1. 使用 Math.floorDiv 按 limitX 和 limitZ 将方块动态分块（完美支持各种坐标系）
        val chunks = LinkedHashMap<String, MutableList<BlockInput>>()
        for (b in blocks) {
            val chunkX = Math.floorDiv(b.x, limitX)
            val chunkZ = Math.floorDiv(b.z, limitZ)
            chunks.getOrPut("${chunkX}_${chunkZ}") { mutableListOf() }.add(b)
        }

        outputDir.mkdirs()

        // 2. 遍历每个有方块的切片，执行紧凑化计算
        chunks.forEach { (key, chunkBlocks) ->
            val tokens = key.split("_")
            val cx = tokens[0].toInt()
            val cz = tokens[1].toInt()
            val outFile = File(outputDir, "part_x${cx}_z${cz}.mcstructure")

            // 过滤掉纯空气，只保留有效建筑方块来计算包围盒
            val validBlocks = chunkBlocks.filter { it.id != "minecraft:air" || it.blockEntityNbt != null }
            
            if (validBlocks.isEmpty()) {
                // 如果该区域内全是导入的空气，则导出一个最小化 1x1x1 的空结构，不再强行撑大
                export(listOf(BlockInput(cx * limitX, 0, cz * limitZ, "minecraft:air")), outFile)
            } else {
                // 计算当前切片内建筑实体的动态紧凑包围盒
                val chunkMinX = validBlocks.minOf { it.x }
                val chunkMaxX = validBlocks.maxOf { it.x }
                val chunkMinY = validBlocks.minOf { it.y }
                val chunkMaxY = validBlocks.maxOf { it.y }
                val chunkMinZ = validBlocks.minOf { it.z }
                val chunkMaxZ = validBlocks.maxOf { it.z }

                // 提取出在该紧凑包围盒范围内的所有方块（包括内部的空气，剔除外部无用空气）
                val exportBlocks = chunkBlocks.filter { 
                    it.x in chunkMinX..chunkMaxX && 
                    it.y in chunkMinY..chunkMaxY && 
                    it.z in chunkMinZ..chunkMaxZ 
                }.toMutableList()

                // 补全紧凑对角线端点，确保 McStructureExporter 计算 size 正确
                if (exportBlocks.none { it.x == chunkMinX && it.y == chunkMinY && it.z == chunkMinZ }) {
                    exportBlocks.add(BlockInput(chunkMinX, chunkMinY, chunkMinZ, "minecraft:air"))
                }
                if (exportBlocks.none { it.x == chunkMaxX && it.y == chunkMaxY && it.z == chunkMaxZ }) {
                    exportBlocks.add(BlockInput(chunkMaxX, chunkMaxY, chunkMaxZ, "minecraft:air"))
                }

                export(exportBlocks, outFile)
            }
        }
    }
    
    /**
     * --- 功能 5: 将一个大 mcstructure 文件切割成许多个小的 mcstructure 文件 ---
     * [已优化]：支持整网格（如 0, 64）原点对齐，且裁剪 X+ 和 Z+ 方向多余的空气
     * 
     * @param inputFile 源大结构文件
     * @param outputDir 输出小结构文件的目录
     * @param limitX 每个切片在 X 轴上的最大跨度（例如 64）
     * @param limitZ 每个切片在 Z 轴上的最大跨度（例如 64）
     */
    fun splitMcStructure(
        inputFile: File,
        outputDir: File,
        limitX: Int = 64,
        limitZ: Int = 64
    ) {
        require(limitX > 0 && limitZ > 0) { "limitX / limitZ must be > 0" }

        // 1. 载入原始基础扁平数据 (此时仅持有底层的 IntArray 索引，内存占用极小)
        val loaded = load(inputFile)
        val width = loaded.width
        val height = loaded.height
        val depth = loaded.depth
        val primaryIndices = loaded.primaryIndices
        val extraIndices = loaded.extraIndices
        val palette = loaded.palette
        val blockPositionData = loaded.blockPositionData

        // 临时辅助类：用于低成本追踪每一个切片的绝对包围盒边界
        class ChunkBox {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
            var hasSolid = false
        }

        // 使用 Long 包装网格 Key (高32位为 cx, 低32位为 cz)，彻底干掉循环体内部的高频字符串拼接
        val chunkBoxes = LinkedHashMap<Long, ChunkBox>()

        // 2. 【第一阶段扫描】避免任何 BlockInput 实体创建，直接透视平坦的基元数组
        for (x in 0 until width) {
            val cx = Math.floorDiv(x, limitX)
            for (z in 0 until depth) {
                val cz = Math.floorDiv(z, limitZ)
                val packedKey = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

                var box: ChunkBox? = null
                var hasLookedUp = false

                for (y in 0 until height) {
                    // 完美的连续一维索引映射
                    val idx = (x * height + y) * depth + z
                    val pIdx = primaryIndices[idx]
                    if (pIdx == -1 || pIdx >= palette.size) continue

                    val blockName = palette[pIdx].name
                    val hasNbt = blockPositionData.containsKey(idx)

                    // 只对非空气方块或带 NBT 的特殊方块计算紧凑包围盒
                    if (blockName != "minecraft:air" || hasNbt) {
                        if (!hasLookedUp) {
                            box = chunkBoxes[packedKey]
                            if (box == null) {
                                box = ChunkBox()
                                chunkBoxes[packedKey] = box
                            }
                            hasLookedUp = true
                        }
                        val b = box!!
                        if (x < b.minX) b.minX = x; if (x > b.maxX) b.maxX = x
                        if (y < b.minY) b.minY = y; if (y > b.maxY) b.maxY = y
                        if (z < b.minZ) b.minZ = z; if (z > b.maxZ) b.maxZ = z
                        b.hasSolid = true
                    }
                }
            }
        }

        if (chunkBoxes.isEmpty()) {
            outputDir.mkdirs()
            val defaultFile = File(outputDir, "part_x0_z0.mcstructure")
            export(listOf(BlockInput(0, 0, 0, "minecraft:air")), defaultFile)
            return
        }

        outputDir.mkdirs()

        // 3. 【第二阶段提取】遍历有实际方块的网格空间，按需、小批量地分块提取并序列化
        chunkBoxes.forEach { (packedKey, box) ->
            if (!box.hasSolid) return@forEach

            // 完美还原带符号的相对网格坐标
            val cx = (packedKey shr 32).toInt()
            val cz = packedKey.toInt()
            val outFile = File(outputDir, "part_x${cx}_z${cz}.mcstructure")

            val minGridX = cx * limitX
            val minGridZ = cz * limitZ
            val minY = box.minY

            val maxLocalX = box.maxX - minGridX
            val maxLocalY = box.maxY - minY
            val maxLocalZ = box.maxZ - minGridZ

            // 此时分配的 List 尺寸极其精准，仅包含当前切片内的有效方块
            val exportBlocks = mutableListOf<BlockInput>()

            // 仅在计算出来的绝对包围盒紧凑闭合圈内进行高密度解包
            for (x in box.minX..box.maxX) {
                for (z in box.minZ..box.maxZ) {
                    for (y in box.minY..box.maxY) {
                        val idx = (x * height + y) * depth + z
                        val pIdx = primaryIndices[idx]
                        if (pIdx == -1 || pIdx >= palette.size) continue

                        val state = palette[pIdx]
                        val blockName = state.name
                        val entityData = blockPositionData[idx]?.get("block_entity_data") as? Map<String, Any?>

                        if (blockName != "minecraft:air" || entityData != null) {
                            val extraPaletteIdx = extraIndices[idx]
                            val extraId = if (extraPaletteIdx != -1 && extraPaletteIdx < palette.size) {
                                palette[extraPaletteIdx].name
                            } else {
                                "minecraft:air"
                            }

                            // 映射转换为局部世界对齐坐标
                            exportBlocks.add(
                                BlockInput(
                                    x = x - minGridX,
                                    y = y - minY,
                                    z = z - minGridZ,
                                    id = blockName,
                                    states = state.states,
                                    blockEntityNbt = entityData,
                                    extraId = extraId
                                )
                            )
                        }
                    }
                }
            }

            // 边界锚点补全，保持切片原点对齐及尺寸正常裁剪
            if (exportBlocks.none { it.x == 0 && it.y == 0 && it.z == 0 }) {
                exportBlocks.add(BlockInput(0, 0, 0, "minecraft:air"))
            }
            if (exportBlocks.none { it.x == maxLocalX && it.y == maxLocalY && it.z == maxLocalZ }) {
                exportBlocks.add(BlockInput(maxLocalX, maxLocalY, maxLocalZ, "minecraft:air"))
            }

            // 执行写入，方法执行完毕后 exportBlocks 列表持有的少量对象立刻会被系统判定为可回收
            export(exportBlocks, outFile)
        }
    }
    
    // --- 导出逻辑：支持含水方块的双层 NBT 写入 ---
    fun export(blocks: List<BlockInput>, outputFile: File, blockVersion: Int = 17879555) {
        require(blocks.isNotEmpty()) { "blocks 不能为空" }
        
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE

        for (b in blocks) {
            if (b.x < minX) minX = b.x; if (b.x > maxX) maxX = b.x
            if (b.y < minY) minY = b.y; if (b.y > maxY) maxY = b.y
            if (b.z < minZ) minZ = b.z; if (b.z > maxZ) maxZ = b.z
        }
        val width = maxX - minX + 1; val height = maxY - minY + 1; val depth = maxZ - minZ + 1

        val paletteMap = LinkedHashMap<BlockStateKey, Int>()
        val volume = width * height * depth
        val primaryIndices = IntArray(volume) { -1 }
        val extraIndices = IntArray(volume) { -1 } // 修复：初始化含水层索引数组
        val blockPositionData = LinkedHashMap<String, NbtTag>()

        for (b in blocks) {
            val lx = b.x - minX; val ly = b.y - minY; val lz = b.z - minZ
            val idx = indexOf(lx, ly, lz, width, height, depth)
            
            // 写入 Layer 0 (主方块)
            val key = BlockStateKey(normalizeBlockId(b.id), b.states)
            primaryIndices[idx] = paletteMap.getOrPut(key) { paletteMap.size }
            
            // 修复：如果存在含水层（Layer 1），将其注册进调色板并记录索引
            if (b.extraId != "minecraft:air") {
                val extraKey = BlockStateKey(normalizeBlockId(b.extraId), emptyMap())
                extraIndices[idx] = paletteMap.getOrPut(extraKey) { paletteMap.size }
            }
            
            if (b.blockEntityNbt != null) {
                val nbt = b.blockEntityNbt.toMutableMap()
                nbt["x"] = lx; nbt["y"] = ly; nbt["z"] = lz
                blockPositionData[idx.toString()] = compound("block_entity_data" to nbt)
            }
        }

        val paletteList = paletteMap.keys.map { compound("name" to it.name, "states" to it.states, "version" to blockVersion) }

        val root = compound(
            "format_version" to 1,
            "size" to listOf(width, height, depth),
            "structure_world_origin" to listOf(0, 0, 0),
            "structure" to mapOf(
                // 修复：将主层数组和含水层数组一并写入 block_indices
                "block_indices" to listOf(primaryIndices.toList(), extraIndices.toList()),
                "entities" to emptyList<Any>(),
                "palette" to mapOf("default" to mapOf("block_palette" to paletteList, "block_position_data" to blockPositionData))
            )
        )

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos -> BufferedOutputStream(fos).use { out -> NbtWriter(out).writeRoot(root) } }
    }

    private fun normalizeBlockId(raw: String): String = if (':' in raw) raw.lowercase() else "minecraft:${raw.lowercase()}"
    
    private fun indexOf(x: Int, y: Int, z: Int, sx: Int, sy: Int, sz: Int): Int {
        return (x * sy + y) * sz + z
    }

    private fun nbtToMap(tag: NbtTag.CompoundTag): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        tag.value.forEach { (k, v) -> map[k] = tagToValue(v) }
        return map
    }

    private fun tagToValue(tag: NbtTag): Any? = when (tag) {
        is NbtTag.ByteTag -> tag.value
        is NbtTag.ShortTag -> tag.value
        is NbtTag.IntTag -> tag.value
        is NbtTag.LongTag -> tag.value
        is NbtTag.FloatTag -> tag.value
        is NbtTag.DoubleTag -> tag.value
        is NbtTag.StringTag -> tag.value
        is NbtTag.ByteArrayTag -> tag.value
        is NbtTag.IntArrayTag -> tag.value
        is NbtTag.ListTag -> tag.items.map { tagToValue(it) }
        is NbtTag.CompoundTag -> nbtToMap(tag)
        else -> null
    }

    private fun compound(vararg pairs: Pair<String, Any?>): NbtTag.CompoundTag {
        val map = LinkedHashMap<String, NbtTag>()
        for ((k, v) in pairs) { if (v != null) map[k] = toTag(v) }
        return NbtTag.CompoundTag(map)
    }

    private fun toTag(value: Any?): NbtTag = when (value) {
        is NbtTag -> value
        is Boolean -> NbtTag.ByteTag(if (value) 1 else 0)
        is Byte -> NbtTag.ByteTag(value)
        is Short -> NbtTag.ShortTag(value)
        is Int -> NbtTag.IntTag(value)
        is Long -> NbtTag.LongTag(value)
        is Float -> NbtTag.FloatTag(value)
        is Double -> NbtTag.DoubleTag(value)
        is String -> NbtTag.StringTag(value)
        is ByteArray -> NbtTag.ByteArrayTag(value)
        is IntArray -> NbtTag.IntArrayTag(value)
        is Map<*, *> -> {
            val m = LinkedHashMap<String, NbtTag>()
            value.forEach { (k, v) -> if (k != null && v != null) m[k.toString()] = toTag(v) }
            NbtTag.CompoundTag(m)
        }
        is Iterable<*> -> NbtTag.ListTag(value.map { toTag(it) })
        else -> NbtTag.StringTag(value.toString())
    }
    
    fun validateMcStructure(file: File): String? {
        if (!file.exists()) return "文件不存在"
        if (!file.isFile) return "不是文件"
        if (file.length() == 0L) return "文件为空"

        return try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val root = NbtReader(bis).readRoot() as? NbtTag.CompoundTag
                        ?: return "NBT 根节点不是 Compound"

                    val value = root.value

                    if (value["format_version"] !is NbtTag.IntTag)
                        return "缺少 format_version"

                    if (value["size"] !is NbtTag.ListTag)
                        return "缺少 size"

                    val structure = value["structure"] as? NbtTag.CompoundTag
                        ?: return "缺少 structure"

                    if (structure.value["block_indices"] !is NbtTag.ListTag)
                        return "缺少 block_indices"

                    if (structure.value["palette"] !is NbtTag.CompoundTag)
                        return "缺少 palette"

                    null
                }
            }
        } catch (e: Exception) {
            e.message ?: "未知错误"
        }
    }

    private fun stateToValue(tag: NbtTag): Any? = when (tag) {
        is NbtTag.ByteTag -> when (tag.value.toInt()) {
            0 -> false
            1 -> true
            else -> tag.value
        }
        is NbtTag.ShortTag -> tag.value
        is NbtTag.IntTag -> tag.value
        is NbtTag.LongTag -> tag.value
        is NbtTag.FloatTag -> tag.value
        is NbtTag.DoubleTag -> tag.value
        is NbtTag.StringTag -> tag.value
        is NbtTag.ListTag -> tag.items.map(::stateToValue)
        is NbtTag.CompoundTag -> tag.value.mapValues { (_, v) -> stateToValue(v) }
        else -> tagToValue(tag)
    }

    private fun stateToMap(tag: NbtTag.CompoundTag): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        tag.value.forEach { (k, v) ->
            map[k] = stateToValue(v)
        }
        return map
    }

    // --- NBT 定义与读写器 ---
    sealed class NbtTag {
        abstract val id: Byte
        data object End : NbtTag() { override val id: Byte = 0 }
        data class ByteTag(val value: Byte) : NbtTag() { override val id: Byte = 1 }
        data class ShortTag(val value: Short) : NbtTag() { override val id: Byte = 2 }
        data class IntTag(val value: Int) : NbtTag() { override val id: Byte = 3 }
        data class LongTag(val value: Long) : NbtTag() { override val id: Byte = 4 }
        data class FloatTag(val value: Float) : NbtTag() { override val id: Byte = 5 }
        data class DoubleTag(val value: Double) : NbtTag() { override val id: Byte = 6 }
        data class ByteArrayTag(val value: ByteArray) : NbtTag() { override val id: Byte = 7 }
        data class StringTag(val value: String) : NbtTag() { override val id: Byte = 8 }
        data class ListTag(val items: List<NbtTag>) : NbtTag() { override val id: Byte = 9 }
        data class CompoundTag(val value: LinkedHashMap<String, NbtTag>) : NbtTag() { override val id: Byte = 10 }
        data class IntArrayTag(val value: IntArray) : NbtTag() { override val id: Byte = 11 }
    }

    private class NbtReader(private val input: InputStream) {
        fun readRoot(): NbtTag {
            val type = input.read()
            if (type == -1 || type == 0) return NbtTag.End
            readString() 
            return readPayload(type.toByte())
        }

        private fun readPayload(type: Byte): NbtTag = when (type) {
            1.toByte() -> NbtTag.ByteTag(input.read().toByte())
            2.toByte() -> NbtTag.ShortTag(readShortLE())
            3.toByte() -> NbtTag.IntTag(readIntLE())
            4.toByte() -> NbtTag.LongTag(readLongLE())
            5.toByte() -> NbtTag.FloatTag(Float.fromBits(readIntLE()))
            6.toByte() -> NbtTag.DoubleTag(Double.fromBits(readLongLE()))
            7.toByte() -> NbtTag.ByteArrayTag(ByteArray(readIntLE()).also { input.read(it) })
            8.toByte() -> NbtTag.StringTag(readString())
            9.toByte() -> {
                val subType = input.read().toByte()
                val size = readIntLE()
                
                // 🚨【核心优化点】如果列表元素是 IntTag 且数量大于 10（过滤掉普通的 size 等小列表）
                if (subType == 3.toByte() && size > 10) {
                    // 直接分配原始 IntArray 连续读取，拒绝产生千万个包装对象！
                    val arr = IntArray(size)
                    for (i in 0 until size) {
                        arr[i] = readIntLE()
                    }
                    NbtTag.IntArrayTag(arr) // 借用已有的 IntArrayTag 载体返回
                } else {
                    NbtTag.ListTag(List(size) { readPayload(subType) })
                }
            }
            10.toByte() -> {
                val map = LinkedHashMap<String, NbtTag>()
                while (true) {
                    val subType = input.read().toByte()
                    if (subType == 0.toByte()) break
                    val name = readString()
                    map[name] = readPayload(subType)
                }
                NbtTag.CompoundTag(map)
            }
            11.toByte() -> NbtTag.IntArrayTag(IntArray(readIntLE()) { readIntLE() })
            else -> throw IOException("Unknown NBT Tag: $type")
        }

        private fun readString(): String {
            val len = readShortLE().toInt() and 0xFFFF
            val bytes = ByteArray(len)
            input.read(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun readShortLE() = (input.read() or (input.read() shl 8)).toShort()
        private fun readIntLE() = input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
        private fun readLongLE(): Long {
            val l = readIntLE().toLong() and 0xFFFFFFFFL
            val h = readIntLE().toLong() and 0xFFFFFFFFL
            return l or (h shl 32)
        }
    }

    private class NbtWriter(private val out: OutputStream) {
        fun writeRoot(root: NbtTag.CompoundTag) {
            out.write(root.id.toInt()); writeString(""); writePayload(root)
        }
        private fun writePayload(tag: NbtTag) {
            when (tag) {
                is NbtTag.ByteTag -> out.write(tag.value.toInt())
                is NbtTag.ShortTag -> writeShortLE(tag.value.toInt())
                is NbtTag.IntTag -> writeIntLE(tag.value)
                is NbtTag.LongTag -> writeLongLE(tag.value)
                is NbtTag.FloatTag -> writeIntLE(tag.value.toBits())
                is NbtTag.DoubleTag -> writeLongLE(tag.value.toBits())
                is NbtTag.ByteArrayTag -> { writeIntLE(tag.value.size); out.write(tag.value) }
                is NbtTag.StringTag -> writeString(tag.value)
                is NbtTag.ListTag -> {
                    val type = tag.items.firstOrNull()?.id?.toInt() ?: 0
                    out.write(type); writeIntLE(tag.items.size)
                    tag.items.forEach { writePayload(it) }
                }
                is NbtTag.CompoundTag -> {
                    tag.value.forEach { (k, v) -> out.write(v.id.toInt()); writeString(k); writePayload(v) }
                    out.write(0)
                }
                is NbtTag.IntArrayTag -> { writeIntLE(tag.value.size); tag.value.forEach { writeIntLE(it) } }
                else -> {}
            }
        }
        private fun writeString(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8); writeShortLE(b.size); out.write(b)
        }
        private fun writeShortLE(v: Int) { out.write(v); out.write(v shr 8) }
        private fun writeIntLE(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        private fun writeLongLE(v: Long) { writeIntLE(v.toInt()); writeIntLE((v shr 32).toInt()) }
    }
}