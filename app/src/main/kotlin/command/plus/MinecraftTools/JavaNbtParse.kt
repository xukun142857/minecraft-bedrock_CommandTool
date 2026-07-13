package command.plus

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

// ============================================================
// Minecraft Java Edition Structure NBT Parser (Highly Optimized)
// ============================================================

sealed class NbtTag

object NbtEnd : NbtTag()

// 对高频基础类型引入享元模式（对象池化缓存），避免频繁申请堆内存
data class NbtByte(val value: Byte) : NbtTag() {
    companion object {
        private val CACHE = Array(256) { NbtByte((it - 128).toByte()) }
        fun of(value: Byte): NbtByte = CACHE[value.toInt() + 128]
    }
}

data class NbtShort(val value: Short) : NbtTag() {
    companion object {
        private const val MIN = -128
        private const val MAX = 255
        private val CACHE = Array(MAX - MIN + 1) { NbtShort((it + MIN).toShort()) }
        fun of(value: Short): NbtShort {
            return if (value in MIN..MAX) CACHE[value - MIN] else NbtShort(value)
        }
    }
}

data class NbtInt(val value: Int) : NbtTag() {
    companion object {
        private const val MIN = -128
        private const val MAX = 512 // 扩充缓存至 512，覆盖绝大多数方块坐标与调色板索引
        private val CACHE = Array(MAX - MIN + 1) { NbtInt(it + MIN) }
        fun of(value: Int): NbtInt {
            return if (value in MIN..MAX) CACHE[value - MIN] else NbtInt(value)
        }
    }
}

data class NbtLong(val value: Long) : NbtTag()
data class NbtFloat(val value: Float) : NbtTag()
data class NbtDouble(val value: Double) : NbtTag()
data class NbtByteArray(val value: ByteArray) : NbtTag()
data class NbtString(val value: String) : NbtTag()
data class NbtList(val elementType: Byte, val elements: List<NbtTag>) : NbtTag()
data class NbtCompound(val values: LinkedHashMap<String, NbtTag>) : NbtTag()
data class NbtIntArray(val value: IntArray) : NbtTag()
data class NbtLongArray(val value: LongArray) : NbtTag()

data class Vec3i(val x: Int, val y: Int, val z: Int)
data class Vec3d(val x: Double, val y: Double, val z: Double)

data class PaletteEntry(
    val name: String,
    val properties: Map<String, String>
) {
    fun fullName(): String {
        if (properties.isEmpty()) return name
        val sb = StringBuilder(name)
        sb.append('[')
        var first = true
        for ((k, v) in properties) {
            if (!first) sb.append(',')
            first = false
            sb.append(k).append('=').append(v)
        }
        sb.append(']')
        return sb.toString()
    }
}

data class StructureBlock(
    val pos: Vec3i,
    val stateIndex: Int,
    val palette: PaletteEntry?,
    val blockEntityNbt: NbtCompound?
)

data class StructureEntity(
    val pos: Vec3d?,
    val blockPos: Vec3i?,
    val nbt: NbtCompound
)

data class StructureFile(
    val rootName: String,
    val dataVersion: Int?,
    val author: String?,
    val size: Vec3i?,
    val palette: List<PaletteEntry>,
    val blocks: List<StructureBlock>,
    val entities: List<StructureEntity>,
    val metadata: NbtCompound?,
    val rawRoot: NbtCompound
) {
    fun resolveBlock(index: Int): PaletteEntry? {
        if (index < 0 || index >= palette.size) return null
        return palette[index]
    }
}

class NbtParseException(message: String) : RuntimeException(message)

private class NbtReader(private val input: DataInputStream) {
    // 局部的字符串缓存，对同一文件内数百万个重复的 NBT 键名和属性进行去重
    private val stringCache = HashMap<String, String>(256)

    private fun cacheString(s: String): String {
        return stringCache.getOrPut(s) { s }
    }

    fun readRoot(): Pair<String, NbtCompound> {
        val type = input.readUnsignedByte()
        if (type != 10) {
            throw NbtParseException("Root tag must be Compound(10), but got type=$type")
        }
        val name = readString()
        val root = readCompoundPayload()
        return name to root
    }

    private fun readTagPayload(type: Int): NbtTag {
        return when (type) {
            0 -> NbtEnd
            1 -> NbtByte.of(input.readByte())   // 使用享元缓存
            2 -> NbtShort.of(input.readShort()) // 使用享元缓存
            3 -> NbtInt.of(input.readInt())     // 使用享元缓存
            4 -> NbtLong(input.readLong())
            5 -> NbtFloat(input.readFloat())
            6 -> NbtDouble(input.readDouble())
            7 -> NbtByteArray(readByteArray())
            8 -> NbtString(readString())
            9 -> readListPayload()
            10 -> readCompoundPayload()
            11 -> NbtIntArray(readIntArray())
            12 -> NbtLongArray(readLongArray())
            else -> throw NbtParseException("Unknown NBT tag type: $type")
        }
    }

    private fun readCompoundPayload(): NbtCompound {
        val map = LinkedHashMap<String, NbtTag>()
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) break
            val name = readString()
            map[name] = readTagPayload(type)
        }
        return NbtCompound(map)
    }

    private fun readListPayload(): NbtList {
        val elementType = input.readByte()
        val length = input.readInt()
        if (length < 0) throw NbtParseException("Negative NBT list length: $length")

        val list = ArrayList<NbtTag>(length)
        for (i in 0 until length) {
            list.add(readTagPayload(elementType.toInt()))
        }
        return NbtList(elementType, list)
    }

    private fun readByteArray(): ByteArray {
        val length = input.readInt()
        if (length < 0) throw NbtParseException("Negative byte array length: $length")
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    private fun readIntArray(): IntArray {
        val length = input.readInt()
        if (length < 0) throw NbtParseException("Negative int array length: $length")
        val data = IntArray(length)
        for (i in 0 until length) data[i] = input.readInt()
        return data
    }

    private fun readLongArray(): LongArray {
        val length = input.readInt()
        if (length < 0) throw NbtParseException("Negative long array length: $length")
        val data = LongArray(length)
        for (i in 0 until length) data[i] = input.readLong()
        return data
    }

    private fun readString(): String {
        return cacheString(input.readUTF())
    }
}

object JavaStructureNbtParser {

    fun parse(file: File): StructureFile {
        return file.inputStream().use { parse(it) }
    }

    fun parse(inputStream: InputStream): StructureFile {
        // 1. 流式解压：直接对流操作，避免载入整个 ByteArray 到内存
        val decodedStream = decodeIfCompressed(inputStream)
        val dis = DataInputStream(decodedStream)
        val reader = NbtReader(dis)

        val (rootName, root) = reader.readRoot()

        val dataVersion = root.int("DataVersion")
        val author = root.string("author")
        val size = readVec3i(root.get("size"))
        val metadata = root.compound("metadata")

        val palette = parsePalette(root.get("palette"))
        val blocks = parseBlocks(root.get("blocks"), palette)
        val entities = parseEntities(root.get("entities"))

        // 2. 内存裁剪：移除 rawRoot 中庞大的 blocks 和 entities 节点，允许垃圾回收及时释放其内存
        root.values.remove("blocks")
        root.values.remove("entities")

        return StructureFile(
            rootName = rootName,
            dataVersion = dataVersion,
            author = author,
            size = size,
            palette = palette,
            blocks = blocks,
            entities = entities,
            metadata = metadata,
            rawRoot = root
        )
    }

    // 兼容原有的字节数组解析入口
    fun parseBytes(bytes: ByteArray): StructureFile {
        return parse(ByteArrayInputStream(bytes))
    }

    /**
     * 无损流式自适应解压
     * 通过 peek 头部魔数判断压缩格式，不消耗额外堆空间
     */
    private fun decodeIfCompressed(inputStream: InputStream): InputStream {
        val bis = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        bis.mark(2)
        val head = ByteArray(2)
        val read = bis.read(head)
        bis.reset()

        if (read < 2) return bis

        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF

        // GZIP 魔数: 0x1F8B
        if (b0 == 0x1F && b1 == 0x8B) {
            return GZIPInputStream(bis)
        }

        // ZLIB 默认压缩格式魔数特征（以 0x78 开头，且满足特定校验和）
        if (b0 == 0x78 && (b0 * 256 + b1) % 31 == 0) {
            return InflaterInputStream(bis)
        }

        return bis
    }

    private fun parsePalette(tag: NbtTag?): List<PaletteEntry> {
        val list = tag as? NbtList ?: return emptyList()
        val result = ArrayList<PaletteEntry>(list.elements.size)

        for (element in list.elements) {
            val compound = element as? NbtCompound ?: continue
            val name = compound.string("Name") ?: "minecraft:air"
            val properties = compound.compound("Properties")
                ?.values
                ?.mapNotNull { (k, v) ->
                    val s = v.asStringValue()
                    if (s != null) k to s else null
                }
                ?.toMap(LinkedHashMap())
                ?: emptyMap()

            result.add(PaletteEntry(name, properties))
        }
        return result
    }

    private fun parseBlocks(tag: NbtTag?, palette: List<PaletteEntry>): List<StructureBlock> {
        val list = tag as? NbtList ?: return emptyList()
        val result = ArrayList<StructureBlock>(list.elements.size)

        for (element in list.elements) {
            val compound = element as? NbtCompound ?: continue
            val pos = readVec3i(compound.get("pos")) ?: continue
            val stateIndex = compound.int("state") ?: 0
            val paletteEntry = if (stateIndex in palette.indices) palette[stateIndex] else null
            val blockEntityNbt = compound.compound("nbt")

            result.add(
                StructureBlock(
                    pos = pos,
                    stateIndex = stateIndex,
                    palette = paletteEntry,
                    blockEntityNbt = blockEntityNbt
                )
            )
        }

        return result
    }

    private fun parseEntities(tag: NbtTag?): List<StructureEntity> {
        val list = tag as? NbtList ?: return emptyList()
        val result = ArrayList<StructureEntity>(list.elements.size)

        for (element in list.elements) {
            val compound = element as? NbtCompound ?: continue
            val pos = readVec3d(compound.get("pos"))
            val blockPos = readVec3i(compound.get("blockPos"))
            result.add(StructureEntity(pos, blockPos, compound))
        }

        return result
    }

    private fun readVec3i(tag: NbtTag?): Vec3i? {
        return when (tag) {
            is NbtIntArray -> {
                if (tag.value.size >= 3) {
                    Vec3i(tag.value[0], tag.value[1], tag.value[2])
                } else null
            }
            is NbtList -> {
                if (tag.elements.size >= 3) {
                    val x = tag.elements[0].asIntValue()
                    val y = tag.elements[1].asIntValue()
                    val z = tag.elements[2].asIntValue()
                    if (x != null && y != null && z != null) Vec3i(x, y, z) else null
                } else null
            }
            else -> null
        }
    }

    private fun readVec3d(tag: NbtTag?): Vec3d? {
        val list = tag as? NbtList ?: return null
        if (list.elements.size < 3) return null
        val x = list.elements[0].asDoubleValue() ?: return null
        val y = list.elements[1].asDoubleValue() ?: return null
        val z = list.elements[2].asDoubleValue() ?: return null
        return Vec3d(x, y, z)
    }
}

// ============================================================
// NBT Helpers
// ============================================================

private fun NbtTag?.asStringValue(): String? {
    return when (this) {
        is NbtString -> value
        is NbtByte -> value.toString()
        is NbtShort -> value.toString()
        is NbtInt -> value.toString()
        is NbtLong -> value.toString()
        is NbtFloat -> value.toString()
        is NbtDouble -> value.toString()
        else -> null
    }
}

private fun NbtTag?.asIntValue(): Int? {
    return when (this) {
        is NbtByte -> value.toInt()
        is NbtShort -> value.toInt()
        is NbtInt -> value
        is NbtLong -> value.toInt()
        is NbtFloat -> value.toInt()
        is NbtDouble -> value.toInt()
        else -> null
    }
}

private fun NbtTag?.asDoubleValue(): Double? {
    return when (this) {
        is NbtByte -> value.toDouble()
        is NbtShort -> value.toDouble()
        is NbtInt -> value.toDouble()
        is NbtLong -> value.toDouble()
        is NbtFloat -> value.toDouble()
        is NbtDouble -> value
        else -> null
    }
}

private fun NbtCompound.get(key: String): NbtTag? = values[key]
private fun NbtCompound.string(key: String): String? = values[key].asStringValue()
private fun NbtCompound.int(key: String): Int? = values[key].asIntValue()
private fun NbtCompound.compound(key: String): NbtCompound? = values[key] as? NbtCompound