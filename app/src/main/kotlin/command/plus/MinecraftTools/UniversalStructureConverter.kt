package command.plus

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

// ==========================================
// 1. 统一的格式枚举与接口定义
// ==========================================

enum class StructureFormat {
    MCSTRUCTURE, // 基岩版结构文件
    LITEMATIC,   // Java版 Litematic 投影文件
    JAVA_NBT     // Java版 原生结构方块 NBT 文件
}

class UniversalStructureConverter(private val mappingDb: MappingDatabase) {

    // ==========================================
    // 2. 核心转换主入口 (集成零 OOM 高速通道)
    // ==========================================
    fun convert(
        inputFile: File,
        outputFile: File,
        targetFormat: StructureFormat,
        gameVersion: Triple<Int, Int, Int> = Triple(1, 20, 0)
    ) {
        val sourceFormat = detectFormat(inputFile)

        // 🚀 大体量 Litematic 转 McStructure 时，直接拦截并走零 OOM 极速转换通道
        if (sourceFormat == StructureFormat.LITEMATIC && targetFormat == StructureFormat.MCSTRUCTURE) {
            convertLitematicToMcStructureFast(inputFile, outputFile, gameVersion)
            return
        }

        val genericStruct = loadAsGeneric(inputFile, sourceFormat)
        val isSourceJava = (sourceFormat == StructureFormat.LITEMATIC || sourceFormat == StructureFormat.JAVA_NBT)

        when (targetFormat) {
            StructureFormat.MCSTRUCTURE -> {
                exportToMcStructure(genericStruct, outputFile, isSourceJava, gameVersion)
            }
            StructureFormat.JAVA_NBT -> {
                exportToJavaNbt(genericStruct, outputFile, !isSourceJava, gameVersion)
            }
            StructureFormat.LITEMATIC -> {
                exportToLitematic(genericStruct, outputFile, !isSourceJava, gameVersion)
            }
        }
    }

    private fun detectFormat(file: File): StructureFormat {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".mcstructure") -> StructureFormat.MCSTRUCTURE
            name.endsWith(".litematic") -> StructureFormat.LITEMATIC
            LitematicParser.isLitematic(file) -> StructureFormat.LITEMATIC
            McStructureExporter.validateMcStructure(file) == null -> StructureFormat.MCSTRUCTURE
            else -> StructureFormat.JAVA_NBT
        }
    }

    private fun loadAsGeneric(file: File, format: StructureFormat): GenericStructure {
        return when (format) {
            StructureFormat.MCSTRUCTURE -> {
                McStructureAdapter(McStructureExporter.load(file))
            }
            StructureFormat.LITEMATIC -> {
                val litematicFile = LitematicParser.parse(file)
                val region = litematicFile.regions.values.firstOrNull()
                    ?: error("Litematic 文件未包含任何有效区域 (Region)")
                LitematicAdapter(region)
            }
            StructureFormat.JAVA_NBT -> {
                JavaNbtAdapter(JavaStructureNbtParser.parse(file))
            }
        }
    }

    // ==========================================
    // 3. 极速、零 OOM 转换通道
    // ==========================================
    private fun convertLitematicToMcStructureFast(
        inputFile: File,
        outputFile: File,
        version: Triple<Int, Int, Int>
    ) {
        val litematicFile = LitematicParser.parse(inputFile)
        val region = litematicFile.regions.values.firstOrNull()
            ?: error("Litematic 文件未包含任何有效区域 (Region)")

        val width = region.width
        val height = region.height
        val depth = region.depth
        val volume = width.toLong() * height.toLong() * depth.toLong()
        require(volume in 0..Int.MAX_VALUE.toLong()) { "结构体积超出 Int 范围: $volume" }
        val volInt = volume.toInt()

        val javaPalette = region.palette
        val bedrockPaletteMap = LinkedHashMap<McStructureExporter.BlockStateKey, Int>()

        // 1. 预映射调色板，杜绝千万级方块循环中的高频对象分配与映射查表开销
        val mappedIndices = Array(javaPalette.size) { i ->
            val def = javaPalette[i]
            
            // Litematic 的 properties 是 Map<String, String>
            val isWaterlogged = def.properties["waterlogged"]?.equals("true", ignoreCase = true) == true

            var targetId = def.name
            var targetStates = LinkedHashMap<String, Any?>(def.properties)

            // 转换 Java 到 Bedrock
            mappingDb.convertJavaToBedrock(def.name, targetStates, version)?.let {
                targetId = it.first
                targetStates = LinkedHashMap(it.second)
            }

            // Layer 0 映射
            val key0 = McStructureExporter.BlockStateKey(normalizeId(targetId), targetStates)
            val idx0 = bedrockPaletteMap.getOrPut(key0) { bedrockPaletteMap.size }

            // Layer 1 (含水层) 映射
            var idx1 = -1
            if (isWaterlogged) {
                val key1 = McStructureExporter.BlockStateKey("minecraft:water", emptyMap())
                idx1 = bedrockPaletteMap.getOrPut(key1) { bedrockPaletteMap.size }
            }

            Pair(idx0, idx1)
        }

        // 2. 内存零装箱：使用最轻量的扁平原生一维整型数组
        val primaryIndices = IntArray(volInt) { -1 }
        val extraIndices = IntArray(volInt) { -1 }

        // 重排三维空间布局以适配 Bedrock 的 I/O 规则
        for (y in 0 until height) {
            val yLitOffset = y * depth * width
            for (z in 0 until depth) {
                val zLitOffset = z * width + yLitOffset
                val yBedOffset = y * depth + z
                for (x in 0 until width) {
                    val litIdx = x + zLitOffset
                    // 安全提取 Litematic 对应索引
                    val javaPaletteIdx = if (litIdx in 0 until region.blockIndices.size) {
                        region.blockIndices[litIdx]
                    } else {
                        0
                    }

                    val (idx0, idx1) = mappedIndices[javaPaletteIdx]

                    val bedIdx = x * height * depth + yBedOffset
                    primaryIndices[bedIdx] = idx0
                    extraIndices[bedIdx] = idx1
                }
            }
        }

        // 3. 流式刷入磁盘，内存开销立刻释放
        val bedrockPaletteList = bedrockPaletteMap.keys.toList()
        McStructureStreamingExporter.export(
            outputFile = outputFile,
            width = width,
            height = height,
            depth = depth,
            primaryIndices = primaryIndices,
            extraIndices = extraIndices,
            palette = bedrockPaletteList
        )
    }

    // ==========================================
    // 4. 原有的通用修复与辅助逻辑
    // ==========================================

    private fun normalizeId(raw: String): String {
        val s = raw.trim().lowercase()
        return if (s.contains(":")) s else "minecraft:$s"
    }

    private fun isAir(id: String): Boolean {
        return normalizeId(id) == "minecraft:air"
    }

    private fun isWaterlogged(states: Map<String, Any?>): Boolean {
        return states["waterlogged"]?.toString()?.equals("true", ignoreCase = true) == true
    }

    private fun prepareMcExportBlock(
        gb: GenericBlock,
        isSourceJava: Boolean,
        version: Triple<Int, Int, Int>
    ): Triple<String, Map<String, Any?>, String> {
        var targetId = gb.id
        var targetStates = LinkedHashMap(gb.states)
        var targetExtraId = gb.extraId

        if (isSourceJava) {
            val hasWaterlogged = isWaterlogged(targetStates)

            mappingDb.convertJavaToBedrock(gb.id, targetStates, version)?.let {
                targetId = it.first
                targetStates = LinkedHashMap(it.second)
            }

            if (hasWaterlogged && isAir(targetExtraId)) {
                targetExtraId = "minecraft:water"
            }

            if (!isAir(targetExtraId)) {
                mappingDb.convertJavaToBedrock(targetExtraId, emptyMap(), version)?.let {
                    targetExtraId = it.first
                }
            }
        }

        return Triple(targetId, targetStates, targetExtraId)
    }

    // ==========================================
    // 5. 各格式传统导出逻辑
    // ==========================================

    private fun exportToMcStructure(
        generic: GenericStructure,
        outputFile: File,
        isSourceJava: Boolean,
        version: Triple<Int, Int, Int>
    ) {
        val blocks = mutableListOf<BlockInput>()

        blocks.add(BlockInput(0, 0, 0, "minecraft:air"))
        blocks.add(BlockInput(generic.width - 1, generic.height - 1, generic.depth - 1, "minecraft:air"))

        for (x in 0 until generic.width) {
            for (y in 0 until generic.height) {
                for (z in 0 until generic.depth) {
                    val gb = generic.getBlockAt(x, y, z) ?: continue

                    if (gb.id == "minecraft:air" && (gb.extraId == "minecraft:air" || gb.extraId.isEmpty())) {
                        continue
                    }

                    val (targetId, targetStates, targetExtraId) =
                        prepareMcExportBlock(gb, isSourceJava, version)

                    if (targetId == "minecraft:air" && targetExtraId == "minecraft:air") {
                        continue
                    }

                    blocks.add(
                        BlockInput(x, y, z, targetId, targetStates, null, targetExtraId)
                    )
                }
            }
        }

        McStructureExporter.export(blocks, outputFile)
    }

    private fun exportToJavaNbt(
        generic: GenericStructure,
        outputFile: File,
        isSourceBedrock: Boolean,
        version: Triple<Int, Int, Int>
    ) {
        val paletteMap = LinkedHashMap<McStructureExporter.BlockStateKey, Int>()
        val blocksList = mutableListOf<McStructureExporter.NbtTag.CompoundTag>()

        for (x in 0 until generic.width) {
            for (y in 0 until generic.height) {
                for (z in 0 until generic.depth) {
                    val gb = generic.getBlockAt(x, y, z) ?: continue
                    var id = gb.id
                    var states = gb.states

                    if (isSourceBedrock) {
                        mappingDb.convertBedrockToJava(gb.id, gb.states, version)?.let {
                            id = it.first
                            states = it.second
                        }
                    }

                    val stringStates = states.mapValues { it.value.toString() }
                    val key = McStructureExporter.BlockStateKey(id, stringStates)
                    val paletteIdx = paletteMap.getOrPut(key) { paletteMap.size }

                    blocksList.add(
                        McStructureExporter.NbtTag.CompoundTag(
                            linkedMapOf(
                                "pos" to McStructureExporter.NbtTag.ListTag(
                                    listOf(
                                        McStructureExporter.NbtTag.IntTag(x),
                                        McStructureExporter.NbtTag.IntTag(y),
                                        McStructureExporter.NbtTag.IntTag(z)
                                    )
                                ),
                                "state" to McStructureExporter.NbtTag.IntTag(paletteIdx)
                            )
                        )
                    )
                }
            }
        }

        val paletteTagList = paletteMap.keys.map { k ->
            val propsMap = LinkedHashMap<String, McStructureExporter.NbtTag>()
            k.states.forEach { (sKey, sVal) ->
                propsMap[sKey] = McStructureExporter.NbtTag.StringTag(sVal.toString())
            }
            val entries = linkedMapOf<String, McStructureExporter.NbtTag>(
                "Name" to McStructureExporter.NbtTag.StringTag(k.name)
            )
            if (propsMap.isNotEmpty()) {
                entries["Properties"] = McStructureExporter.NbtTag.CompoundTag(propsMap)
            }
            McStructureExporter.NbtTag.CompoundTag(entries)
        }

        val root = McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
            "size" to McStructureExporter.NbtTag.ListTag(listOf(
                McStructureExporter.NbtTag.IntTag(generic.width),
                McStructureExporter.NbtTag.IntTag(generic.height),
                McStructureExporter.NbtTag.IntTag(generic.depth)
            )),
            "palette" to McStructureExporter.NbtTag.ListTag(paletteTagList),
            "blocks" to McStructureExporter.NbtTag.ListTag(blocksList),
            "entities" to McStructureExporter.NbtTag.ListTag(emptyList()),
            "DataVersion" to McStructureExporter.NbtTag.IntTag(3465)
        ))

        outputFile.parentFile?.mkdirs()
        java.util.zip.GZIPOutputStream(FileOutputStream(outputFile)).use { gzos ->
            DataOutputStream(gzos).use { dos ->
                JavaNbtWriter(dos).writeRoot("", root)
            }
        }
    }

    private fun exportToLitematic(
        generic: GenericStructure,
        outputFile: File,
        isSourceBedrock: Boolean,
        version: Triple<Int, Int, Int>
    ) {
        val volumeLong = generic.width.toLong() * generic.height.toLong() * generic.depth.toLong()
        require(volumeLong in 0..Int.MAX_VALUE.toLong()) {
            "结构体积过大或非法: $volumeLong"
        }

        val volume = volumeLong.toInt()
        val paletteMap = LinkedHashMap<McStructureExporter.BlockStateKey, Int>()
        val indices = IntArray(volume)

        for (y in 0 until generic.height) {
            for (z in 0 until generic.depth) {
                for (x in 0 until generic.width) {
                    val gb = generic.getBlockAt(x, y, z) ?: continue
                    var id = gb.id
                    var states = gb.states

                    if (isSourceBedrock) {
                        mappingDb.convertBedrockToJava(gb.id, gb.states, version)?.let {
                            id = it.first
                            states = it.second
                        }
                    }

                    val stringStates = states.mapValues { it.value.toString() }
                    val key = McStructureExporter.BlockStateKey(id, stringStates)
                    val paletteIdx = paletteMap.getOrPut(key) { paletteMap.size }

                    val idxLong =
                        x.toLong() +
                        generic.width.toLong() * (z.toLong() + generic.depth.toLong() * y.toLong())

                    require(idxLong in 0 until volumeLong) {
                        "索引越界: $idxLong / $volumeLong"
                    }

                    indices[idxLong.toInt()] = paletteIdx
                }
            }
        }

        val packedLongs = encodePaletteIndices(indices, paletteMap.size)
        val paletteTagList = paletteMap.keys.map { k ->
            val propsMap = LinkedHashMap<String, McStructureExporter.NbtTag>()
            k.states.forEach { (sKey, sVal) ->
                propsMap[sKey] = McStructureExporter.NbtTag.StringTag(sVal.toString())
            }
            val entries = linkedMapOf<String, McStructureExporter.NbtTag>(
                "Name" to McStructureExporter.NbtTag.StringTag(k.name)
            )
            if (propsMap.isNotEmpty()) {
                entries["Properties"] = McStructureExporter.NbtTag.CompoundTag(propsMap)
            }
            McStructureExporter.NbtTag.CompoundTag(entries)
        }

        val packedLongTags = packedLongs.map { McStructureExporter.NbtTag.LongTag(it) }

        val regionDetails = McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
            "Position" to McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
                "x" to McStructureExporter.NbtTag.IntTag(0),
                "y" to McStructureExporter.NbtTag.IntTag(0),
                "z" to McStructureExporter.NbtTag.IntTag(0)
            )),
            "Size" to McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
                "x" to McStructureExporter.NbtTag.IntTag(generic.width),
                "y" to McStructureExporter.NbtTag.IntTag(generic.height),
                "z" to McStructureExporter.NbtTag.IntTag(generic.depth)
            )),
            "BlockStatePalette" to McStructureExporter.NbtTag.ListTag(paletteTagList),
            "BlockStates" to McStructureExporter.NbtTag.ListTag(packedLongTags),
            "TileEntities" to McStructureExporter.NbtTag.ListTag(emptyList()),
            "Entities" to McStructureExporter.NbtTag.ListTag(emptyList()),
            "PendingBlockTicks" to McStructureExporter.NbtTag.ListTag(emptyList()),
            "PendingFluidTicks" to McStructureExporter.NbtTag.ListTag(emptyList())
        ))

        val metadata = McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
            "Author" to McStructureExporter.NbtTag.StringTag("PlusConverter"),
            "Description" to McStructureExporter.NbtTag.StringTag("Auto Generated"),
            "EnclosingSize" to McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
                "x" to McStructureExporter.NbtTag.IntTag(generic.width),
                "y" to McStructureExporter.NbtTag.IntTag(generic.height),
                "z" to McStructureExporter.NbtTag.IntTag(generic.depth)
            )),
            "RegionCount" to McStructureExporter.NbtTag.IntTag(1),
            "TotalBlocks" to McStructureExporter.NbtTag.IntTag(volume),
            "TotalVolume" to McStructureExporter.NbtTag.IntTag(volume)
        ))

        val root = McStructureExporter.NbtTag.CompoundTag(linkedMapOf(
            "Version" to McStructureExporter.NbtTag.IntTag(5),
            "MinecraftDataVersion" to McStructureExporter.NbtTag.IntTag(3465),
            "Metadata" to metadata,
            "Regions" to McStructureExporter.NbtTag.CompoundTag(linkedMapOf("Unnamed" to regionDetails))
        ))

        outputFile.parentFile?.mkdirs()
        java.util.zip.GZIPOutputStream(FileOutputStream(outputFile)).use { gzos ->
            DataOutputStream(gzos).use { dos ->
                JavaNbtWriter(dos).writeRoot("", root)
            }
        }
    }

    private fun encodePaletteIndices(indices: IntArray, paletteSize: Int): LongArray {
        if (indices.isEmpty()) return LongArray(0)
        val bitsPerBlock = max(2, ceil(ln(max(2, paletteSize).toDouble()) / ln(2.0)).toInt())
        val totalBits = indices.size.toLong() * bitsPerBlock
        val longCount = ceil(totalBits.toDouble() / 64.0).toInt()
        val packed = LongArray(longCount)
        val mask = (1L shl bitsPerBlock) - 1L

        for (i in indices.indices) {
            val value = indices[i].toLong() and mask
            val bitIndex = i.toLong() * bitsPerBlock
            val longIndex = (bitIndex ushr 6).toInt()
            val startBit = (bitIndex and 63L).toInt()

            if (startBit + bitsPerBlock <= 64) {
                packed[longIndex] = packed[longIndex] or (value shl startBit)
            } else {
                packed[longIndex] = packed[longIndex] or (value shl startBit)
                val highBits = startBit + bitsPerBlock - 64
                val highMask = (1L shl highBits) - 1L
                packed[longIndex + 1] = packed[longIndex + 1] or ((value ushr (bitsPerBlock - highBits)) and highMask)
            }
        }
        return packed
    }

}

// ==========================================
// 6. 三大格式结构数据桥接适配器
// ==========================================

class McStructureAdapter(private val loaded: LoadedStructure) : GenericStructure {
    override val width = loaded.width
    override val height = loaded.height
    override val depth = loaded.depth
    override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {
        val bi = McStructureExporter.getBlockAt(loaded, x, y, z) ?: return null
        return GenericBlock(bi.id, bi.states, bi.extraId)
    }
}

class LitematicAdapter(private val region: LitematicRegion) : GenericStructure {
    override val width = region.width
    override val height = region.height
    override val depth = region.depth
    override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {
        if (x !in 0 until width || y !in 0 until height || z !in 0 until depth) return null
        val ref = region.getBlock(x, y, z)
        val states = ref.state.properties.mapValues { it.value as Any? }
        return GenericBlock(ref.state.name, states)
    }
}

class JavaNbtAdapter(private val struct: StructureFile) : GenericStructure {
    override val width = struct.size?.x ?: 0
    override val height = struct.size?.y ?: 0
    override val depth = struct.size?.z ?: 0
    private val blockMap = struct.blocks.associateBy { it.pos }

    override fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? {
        val b = blockMap[Vec3i(x, y, z)] ?: return GenericBlock("minecraft:air", emptyMap())
        val palette = b.palette ?: return GenericBlock("minecraft:air", emptyMap())
        val states = palette.properties.mapValues { it.value as Any? }
        return GenericBlock(palette.name, states)
    }

}

// ==========================================
// 7. Java 版标准的 Big-Endian NBT 写入器
// ==========================================

private class JavaNbtWriter(private val out: DataOutputStream) {
    fun writeRoot(name: String, root: McStructureExporter.NbtTag.CompoundTag) {
        out.writeByte(root.id.toInt())
        out.writeUTF(name)
        writePayload(root)
    }

    private fun writePayload(tag: McStructureExporter.NbtTag) {
        when (tag) {
            is McStructureExporter.NbtTag.ByteTag -> out.writeByte(tag.value.toInt())
            is McStructureExporter.NbtTag.ShortTag -> out.writeShort(tag.value.toInt())
            is McStructureExporter.NbtTag.IntTag -> out.writeInt(tag.value)
            is McStructureExporter.NbtTag.LongTag -> out.writeLong(tag.value)
            is McStructureExporter.NbtTag.FloatTag -> out.writeFloat(tag.value)
            is McStructureExporter.NbtTag.DoubleTag -> out.writeDouble(tag.value)
            is McStructureExporter.NbtTag.ByteArrayTag -> {
                out.writeInt(tag.value.size)
                out.write(tag.value)
            }
            is McStructureExporter.NbtTag.StringTag -> out.writeUTF(tag.value)
            is McStructureExporter.NbtTag.ListTag -> {
                val type = tag.items.firstOrNull()?.id?.toInt() ?: 0
                out.writeByte(type)
                out.writeInt(tag.items.size)
                tag.items.forEach { writePayload(it) }
            }
            is McStructureExporter.NbtTag.CompoundTag -> {
                tag.value.forEach { (k, v) ->
                    out.writeByte(v.id.toInt())
                    out.writeUTF(k)
                    writePayload(v)
                }
                out.writeByte(0)
            }
            is McStructureExporter.NbtTag.IntArrayTag -> {
                out.writeInt(tag.value.size)
                tag.value.forEach { out.writeInt(it) }
            }
            else -> {}
        }
    }

}