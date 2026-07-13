package command.plus

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// ==========================================
// 公共数据模型 (使用独立命名避免与 JavaNbt 重名)
// ==========================================

data class LitematicFile(
    val version: Int,
    val minecraftDataVersion: Int?,
    val metadata: LitematicMetadata?,
    val regions: Map<String, LitematicRegion>
)

data class LitematicMetadata(
    val name: String? = null,
    val author: String? = null,
    val description: String? = null,
    val timeCreated: Long? = null,
    val timeModified: Long? = null,
    val totalBlocks: Int? = null,
    val totalVolume: Int? = null,
    val regionCount: Int? = null,
    val enclosingSize: LitematicVec3i? = null
)

data class LitematicVec3i(val x: Int, val y: Int, val z: Int)

/**
 * Litematic 的 Region：
 * - size 可以为负数
 * - position 是 region 的“起点角”
 * - size 的符号表示沿哪个方向延伸
 */
data class LitematicRegion(
    val name: String,
    val position: LitematicVec3i,
    val size: LitematicVec3i,
    val palette: List<BlockStateDef>,
    val blocks: Array<BlockStateRef>,
    val tileEntities: List<Map<String, LitematicNbtTag>>,
    val entities: List<Map<String, LitematicNbtTag>>,
    val pendingBlockTicks: List<Map<String, LitematicNbtTag>>,
    val pendingFluidTicks: List<Map<String, LitematicNbtTag>>
) {
    val width: Int = abs(size.x)
    val height: Int = abs(size.y)
    val depth: Int = abs(size.z)

    private val xStep: Int = if (size.x >= 0) 1 else -1
    private val yStep: Int = if (size.y >= 0) 1 else -1
    private val zStep: Int = if (size.z >= 0) 1 else -1

    // region 在世界坐标中的包围盒（包含端点）
    val minWorldX: Int = if (size.x >= 0) position.x else position.x + size.x + 1
    val minWorldY: Int = if (size.y >= 0) position.y else position.y + size.y + 1
    val minWorldZ: Int = if (size.z >= 0) position.z else position.z + size.z + 1

    val maxWorldX: Int = minWorldX + width - 1
    val maxWorldY: Int = minWorldY + height - 1
    val maxWorldZ: Int = minWorldZ + depth - 1

    fun containsWorld(x: Int, y: Int, z: Int): Boolean {
        return x in minWorldX..maxWorldX &&
                y in minWorldY..maxWorldY &&
                z in minWorldZ..maxWorldZ
    }

    /**
     * 世界坐标 -> region 局部坐标
     * 局部坐标始终是 0..width-1 / 0..height-1 / 0..depth-1
     */
    fun worldToLocal(x: Int, y: Int, z: Int): LitematicVec3i? {
    if (!containsWorld(x, y, z)) return null
    return LitematicVec3i(
        x - minWorldX,
        y - minWorldY,
        z - minWorldZ
    )
}

    /**
     * 这里保持你原来的存储顺序：x fastest, z middle, y slowest
     */
    fun index(x: Int, y: Int, z: Int): Int {
        require(x in 0 until width) { "x out of bounds: $x / $width" }
        require(y in 0 until height) { "y out of bounds: $y / $height" }
        require(z in 0 until depth) { "z out of bounds: $z / $depth" }
        return x + width * (z + depth * y)
    }

    fun getBlock(x: Int, y: Int, z: Int): BlockStateRef {
        val idx = index(x, y, z)
        return blocks.getOrElse(idx) { BlockStateRef(0, BlockStateDef("minecraft:air")) }
    }

    fun getBlockAtWorld(x: Int, y: Int, z: Int): BlockStateRef? {
        val local = worldToLocal(x, y, z) ?: return null
        return getBlock(local.x, local.y, local.z)
    }
}

data class BlockStateDef(
    val name: String,
    val properties: Map<String, String> = emptyMap()
) {
    fun toBlockIdString(): String {
        if (properties.isEmpty()) return name
        return buildString {
            append(name)
            append('[')
            append(properties.entries.joinToString(",") { "${it.key}=${it.value}" })
            append(']')
        }
    }
}

data class BlockStateRef(
    val paletteIndex: Int,
    val state: BlockStateDef
)

// ==========================================
// NBT 数据模型 (加前缀防冲突)
// ==========================================

sealed class LitematicNbtTag {
    data class ByteTag(val value: Byte) : LitematicNbtTag()
    data class ShortTag(val value: Short) : LitematicNbtTag()
    data class IntTag(val value: Int) : LitematicNbtTag()
    data class LongTag(val value: Long) : LitematicNbtTag()
    data class FloatTag(val value: Float) : LitematicNbtTag()
    data class DoubleTag(val value: Double) : LitematicNbtTag()
    data class ByteArrayTag(val value: ByteArray) : LitematicNbtTag()
    data class StringTag(val value: String) : LitematicNbtTag()
    data class ListTag(val elementType: Int, val value: List<LitematicNbtTag>) : LitematicNbtTag()
    data class CompoundTag(val value: Map<String, LitematicNbtTag>) : LitematicNbtTag()
    data class IntArrayTag(val value: IntArray) : LitematicNbtTag()
    data class LongArrayTag(val value: LongArray) : LitematicNbtTag()
}

typealias LitematicNbtCompound = Map<String, LitematicNbtTag>

// ==========================================
// 解析器
// ==========================================

class LitematicParser private constructor() {

    companion object {

        fun isLitematic(file: File): Boolean {
            return try {
                FileInputStream(file).use(::isLitematic)
            } catch (_: Exception) {
                false
            }
        }

        fun isLitematic(input: InputStream): Boolean {
            return try {
                val nbt = readGzippedNbt(input)
                val version = nbt.int("Version")
                val regions = nbt.compound("Regions")
                version != null && regions != null
            } catch (_: Exception) {
                false
            }
        }

        fun parse(file: File): LitematicFile {
            FileInputStream(file).use { fis ->
                return parse(fis)
            }
        }

        fun parse(inputStream: InputStream): LitematicFile {
            val nbt = readGzippedNbt(inputStream)

            val version = nbt.int("Version") ?: error("Missing Version")
            val minecraftDataVersion = nbt.int("MinecraftDataVersion")
            val metadataTag = nbt.compound("Metadata")
            val regionsTag = nbt.compound("Regions") ?: error("Missing Regions")

            val metadata = metadataTag?.toMetadata()
            val regions = linkedMapOf<String, LitematicRegion>()

            for ((regionName, regionTag) in regionsTag) {
                val regionCompound = regionTag.asCompoundOrNull() ?: continue
                regions[regionName] = parseRegion(regionName, regionCompound)
            }

            return LitematicFile(
                version = version,
                minecraftDataVersion = minecraftDataVersion,
                metadata = metadata,
                regions = regions
            )
        }

        private fun parseRegion(name: String, region: LitematicNbtCompound): LitematicRegion {
            val position = region.readVec3i("Position") ?: LitematicVec3i(0, 0, 0)
            val size = region.readVec3i("Size") ?: LitematicVec3i(0, 0, 0)

            val paletteTag = region.list("BlockStatePalette")
                ?: error("Region $name missing BlockStatePalette")

            val palette = paletteTag.value.map { it.asCompound().toBlockStateDef() }

            val blockStatesLongs = region.longArray("BlockStates")
                ?: error("Region $name missing BlockStates")

            val totalBlocks = abs(size.x) * abs(size.y) * abs(size.z)
            val blockIndices = decodePaletteIndices(blockStatesLongs, palette.size, totalBlocks)

            val blocks = Array(totalBlocks) { i ->
                val paletteIndex = blockIndices.getOrElse(i) { 0 }
                val state = palette.getOrNull(paletteIndex) ?: BlockStateDef("minecraft:air")
                BlockStateRef(paletteIndex, state)
            }

            val tileEntities = region.list("TileEntities")?.value?.mapNotNull { it.asCompoundOrNull() } ?: emptyList()
            val entities = region.list("Entities")?.value?.mapNotNull { it.asCompoundOrNull() } ?: emptyList()
            val pendingBlockTicks = region.list("PendingBlockTicks")?.value?.mapNotNull { it.asCompoundOrNull() } ?: emptyList()
            val pendingFluidTicks = region.list("PendingFluidTicks")?.value?.mapNotNull { it.asCompoundOrNull() } ?: emptyList()

            return LitematicRegion(
                name = name,
                position = position,
                size = size,
                palette = palette,
                blocks = blocks,
                tileEntities = tileEntities,
                entities = entities,
                pendingBlockTicks = pendingBlockTicks,
                pendingFluidTicks = pendingFluidTicks
            )
        }

        private fun decodePaletteIndices(
            packed: LongArray,
            paletteSize: Int,
            expectedCount: Int
        ): IntArray {
            val out = IntArray(expectedCount)
            if (expectedCount <= 0) return out
            if (paletteSize <= 1) return out

            val bitsPerBlock = max(2, ceil(ln(paletteSize.toDouble()) / ln(2.0)).toInt())
            val mask = (1L shl bitsPerBlock) - 1L

            for (i in 0 until expectedCount) {
                val bitIndex = i * bitsPerBlock
                val longIndex = bitIndex ushr 6
                val startBit = bitIndex and 63

                val value = if (startBit + bitsPerBlock <= 64) {
                    (packed.getOrElse(longIndex) { 0L } ushr startBit) and mask
                } else {
                    val low = packed.getOrElse(longIndex) { 0L } ushr startBit
                    val highBits = startBit + bitsPerBlock - 64
                    val highMask = (1L shl highBits) - 1L
                    val high = packed.getOrElse(longIndex + 1) { 0L } and highMask
                    low or (high shl (bitsPerBlock - highBits))
                }

                val idx = value.toInt()
                require(idx in 0 until paletteSize) {
                    "Decoded palette index out of range: $idx / $paletteSize"
                }
                out[i] = idx
            }

            return out
        }

        // ==========================================
        // NBT 读取底层逻辑
        // ==========================================

        private fun readGzippedNbt(input: InputStream): LitematicNbtCompound {
            GZIPInputStream(input).use { gis ->
                DataInputStream(gis).use { dis ->
                    val rootType = dis.readUnsignedByte()
                    require(rootType == 10) { "Root tag must be a Compound (10), got $rootType" }
                    dis.readUTF()
                    return readCompoundPayload(dis)
                }
            }
        }

        private fun readTagPayload(dis: DataInputStream, type: Int): LitematicNbtTag {
            return when (type) {
                1 -> LitematicNbtTag.ByteTag(dis.readByte())
                2 -> LitematicNbtTag.ShortTag(dis.readShort())
                3 -> LitematicNbtTag.IntTag(dis.readInt())
                4 -> LitematicNbtTag.LongTag(dis.readLong())
                5 -> LitematicNbtTag.FloatTag(dis.readFloat())
                6 -> LitematicNbtTag.DoubleTag(dis.readDouble())
                7 -> {
                    val len = dis.readInt()
                    require(len >= 0) { "Negative byte array length: $len" }
                    val arr = ByteArray(len)
                    dis.readFully(arr)
                    LitematicNbtTag.ByteArrayTag(arr)
                }
                8 -> LitematicNbtTag.StringTag(dis.readUTF())
                9 -> {
                    val elementType = dis.readUnsignedByte()
                    val len = dis.readInt()
                    require(len >= 0) { "Negative list length: $len" }
                    val list = ArrayList<LitematicNbtTag>(len)
                    repeat(len) {
                        list += readTagPayload(dis, elementType)
                    }
                    LitematicNbtTag.ListTag(elementType, list)
                }
                10 -> LitematicNbtTag.CompoundTag(readCompoundPayload(dis))
                11 -> {
                    val len = dis.readInt()
                    require(len >= 0) { "Negative int array length: $len" }
                    val arr = IntArray(len)
                    for (i in 0 until len) arr[i] = dis.readInt()
                    LitematicNbtTag.IntArrayTag(arr)
                }
                12 -> {
                    val len = dis.readInt()
                    require(len >= 0) { "Negative long array length: $len" }
                    val arr = LongArray(len)
                    for (i in 0 until len) arr[i] = dis.readLong()
                    LitematicNbtTag.LongArrayTag(arr)
                }
                0 -> error("Unexpected TAG_End in payload")
                else -> error("Unsupported NBT tag type: $type")
            }
        }

        private fun readCompoundPayload(dis: DataInputStream): LitematicNbtCompound {
            val map = linkedMapOf<String, LitematicNbtTag>()
            while (true) {
                val type = dis.readUnsignedByte()
                if (type == 0) break
                val name = dis.readUTF()
                val value = readTagPayload(dis, type)
                map[name] = value
            }
            return map
        }
    }
}

// ==========================================
// NBT 拓展函数
// ==========================================

private fun LitematicNbtCompound.int(key: String): Int? = (this[key] as? LitematicNbtTag.IntTag)?.value
private fun LitematicNbtCompound.long(key: String): Long? = (this[key] as? LitematicNbtTag.LongTag)?.value
private fun LitematicNbtCompound.string(key: String): String? = (this[key] as? LitematicNbtTag.StringTag)?.value
private fun LitematicNbtCompound.list(key: String): LitematicNbtTag.ListTag? = this[key] as? LitematicNbtTag.ListTag
private fun LitematicNbtCompound.longArray(key: String): LongArray? = (this[key] as? LitematicNbtTag.LongArrayTag)?.value
private fun LitematicNbtCompound.compound(key: String): LitematicNbtCompound? = (this[key] as? LitematicNbtTag.CompoundTag)?.value

private fun LitematicNbtTag.asCompoundOrNull(): LitematicNbtCompound? = (this as? LitematicNbtTag.CompoundTag)?.value
private fun LitematicNbtTag.asCompound(): LitematicNbtCompound = asCompoundOrNull() ?: emptyMap()

private fun LitematicNbtCompound.readVec3i(key: String): LitematicVec3i? {
    val tag = this[key] ?: return null
    return tag.toVec3iOrNull()
}

private fun LitematicNbtTag.toVec3iOrNull(): LitematicVec3i? {
    return when (this) {
        is LitematicNbtTag.CompoundTag -> {
            val x = value["x"].asIntOrNull() ?: return null
            val y = value["y"].asIntOrNull() ?: return null
            val z = value["z"].asIntOrNull() ?: return null
            LitematicVec3i(x, y, z)
        }
        is LitematicNbtTag.IntArrayTag -> {
            if (value.size < 3) null else LitematicVec3i(value[0], value[1], value[2])
        }
        is LitematicNbtTag.ListTag -> {
            if (value.size < 3) return null
            val x = value.getOrNull(0).asIntOrNull() ?: return null
            val y = value.getOrNull(1).asIntOrNull() ?: return null
            val z = value.getOrNull(2).asIntOrNull() ?: return null
            LitematicVec3i(x, y, z)
        }
        else -> null
    }
}

private fun LitematicNbtTag?.asIntOrNull(): Int? {
    return when (this) {
        is LitematicNbtTag.ByteTag -> value.toInt()
        is LitematicNbtTag.ShortTag -> value.toInt()
        is LitematicNbtTag.IntTag -> value
        is LitematicNbtTag.LongTag -> value.toInt()
        else -> null
    }
}

private fun LitematicNbtCompound.toMetadata(): LitematicMetadata {
    val enclosing = compound("EnclosingSize")?.readVec3i("dummy") ?: run {
        val t = this["EnclosingSize"]
        when (t) {
            is LitematicNbtTag.IntArrayTag -> if (t.value.size >= 3) LitematicVec3i(t.value[0], t.value[1], t.value[2]) else LitematicVec3i(0, 0, 0)
            is LitematicNbtTag.CompoundTag -> {
                val x = t.value["x"].asIntOrNull() ?: 0
                val y = t.value["y"].asIntOrNull() ?: 0
                val z = t.value["z"].asIntOrNull() ?: 0
                LitematicVec3i(x, y, z)
            }
            else -> LitematicVec3i(0, 0, 0)
        }
    }

    return LitematicMetadata(
        name = string("Name"),
        author = string("Author"),
        description = string("Description"),
        timeCreated = long("TimeCreated"),
        timeModified = long("TimeModified"),
        totalBlocks = int("TotalBlocks"),
        totalVolume = int("TotalVolume"),
        regionCount = int("RegionCount"),
        enclosingSize = enclosing
    )
}

private fun LitematicNbtCompound.toBlockStateDef(): BlockStateDef {
    val name = string("Name") ?: "minecraft:air"
    val props = compound("Properties")
        ?.entries
        ?.mapNotNull { (k, v) ->
            val sv = (v as? LitematicNbtTag.StringTag)?.value ?: return@mapNotNull null
            k to sv
        }
        ?.toMap()
        ?: emptyMap()
    return BlockStateDef(name, props)
}

