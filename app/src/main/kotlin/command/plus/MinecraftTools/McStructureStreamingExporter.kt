package command.plus

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object McStructureStreamingExporter {

    fun export(
        outputFile: File,
        width: Int,
        height: Int,
        depth: Int,
        primaryIndices: IntArray,
        extraIndices: IntArray,
        palette: List<McStructureExporter.BlockStateKey>,
        blockVersion: Int = 17879555
    ) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            // 采用 1MB 缓冲区，大幅减少 I/O 次数提高速度
            BufferedOutputStream(fos, 1024 * 1024).use { bos ->
                val writer = LittleEndianNbtWriter(bos)

                // 1. Root TAG_Compound (id = 10)
                writer.writeByte(10)
                writer.writeString("") // 空的根名称

                // 2. format_version (TAG_Int = 3)
                writer.writeTagName(3, "format_version")
                writer.writeIntLE(1)

                // 3. size (TAG_List = 9)
                writer.writeTagName(9, "size")
                writer.writeByte(3) // 元素类型为 TAG_Int
                writer.writeIntLE(3) 
                writer.writeIntLE(width)
                writer.writeIntLE(height)
                writer.writeIntLE(depth)

                // 4. structure_world_origin (TAG_List = 9)
                writer.writeTagName(9, "structure_world_origin")
                writer.writeByte(3)
                writer.writeIntLE(3)
                writer.writeIntLE(0)
                writer.writeIntLE(0)
                writer.writeIntLE(0)

                // 5. structure (TAG_Compound = 10)
                writer.writeTagName(10, "structure")

                // 5.1 block_indices (TAG_List = 9)
                writer.writeTagName(9, "block_indices")
                writer.writeByte(9) // 元素类型为 TAG_List
                writer.writeIntLE(2) // 包含 Layer 0 (主层) 和 Layer 1 (含水层)

                // 5.1.1 Layer 0
                writer.writeByte(3) // 元素类型为 TAG_Int
                writer.writeIntLE(primaryIndices.size)
                for (i in primaryIndices.indices) {
                    writer.writeIntLE(primaryIndices[i])
                }

                // 5.1.2 Layer 1
                writer.writeByte(3)
                writer.writeIntLE(extraIndices.size)
                for (i in extraIndices.indices) {
                    writer.writeIntLE(extraIndices[i])
                }

                // 5.2 entities (TAG_List = 9, 大小为 0)
                writer.writeTagName(9, "entities")
                writer.writeByte(10)
                writer.writeIntLE(0)

                // 5.3 palette (TAG_Compound = 10)
                writer.writeTagName(10, "palette")

                // 5.3.1 default (TAG_Compound = 10)
                writer.writeTagName(10, "default")

                // 5.3.1.1 block_palette (TAG_List of TAG_Compound)
                writer.writeTagName(9, "block_palette")
                writer.writeByte(10) // 元素类型为 TAG_Compound
                writer.writeIntLE(palette.size)

                for (key in palette) {
                    // 列表中的 TAG_Compound 元素没有独立名称，直接写入其内容，并以 TAG_End 结尾
                    writer.writeByte(8) // TAG_String
                    writer.writeString("name")
                    writer.writeString(key.name)

                    writer.writeByte(10) // TAG_Compound
                    writer.writeString("states")
                    for ((sName, sVal) in key.states) {
                        writeDynamicTag(writer, sName, sVal)
                    }
                    writer.writeByte(0) // states compound 结束符

                    writer.writeByte(3) // TAG_Int
                    writer.writeString("version")
                    writer.writeIntLE(blockVersion)

                    writer.writeByte(0) // block compound 结束符
                }

                // 5.3.1.2 block_position_data (TAG_Compound = 10, 大小为空)
                writer.writeTagName(10, "block_position_data")
                writer.writeByte(0)

                writer.writeByte(0) // default 结束符
                writer.writeByte(0) // palette 结束符
                writer.writeByte(0) // structure 结束符
                writer.writeByte(0) // root compound 结束符
            }
        }
    }

    private fun writeDynamicTag(writer: LittleEndianNbtWriter, name: String, value: Any?) {
        when (value) {
            is Boolean -> {
                writer.writeTagName(1, name) // TAG_Byte
                writer.writeByte(if (value) 1 else 0)
            }
            is Byte -> {
                writer.writeTagName(1, name)
                writer.writeByte(value.toInt())
            }
            is Short -> {
                writer.writeTagName(2, name) // TAG_Short
                writer.writeShortLE(value.toInt())
            }
            is Int -> {
                writer.writeTagName(3, name) // TAG_Int
                writer.writeIntLE(value)
            }
            is Long -> {
                writer.writeTagName(4, name) // TAG_Long
                writer.writeLongLE(value)
            }
            is Float -> {
                writer.writeTagName(5, name) // TAG_Float
                writer.writeFloatLE(value)
            }
            is Double -> {
                writer.writeTagName(6, name) // TAG_Double
                writer.writeDoubleLE(value)
            }
            is String -> {
                writer.writeTagName(8, name) // TAG_String
                writer.writeString(value)
            }
            else -> {
                writer.writeTagName(8, name)
                writer.writeString(value.toString())
            }
        }
    }

    private class LittleEndianNbtWriter(private val out: OutputStream) {
        fun writeByte(v: Int) = out.write(v)

        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }

        fun writeLongLE(v: Long) {
            writeIntLE(v.toInt())
            writeIntLE((v ushr 32).toInt())
        }

        fun writeFloatLE(v: Float) = writeIntLE(v.toBits())
        fun writeDoubleLE(v: Double) = writeLongLE(v.toBits())

        fun writeString(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            writeShortLE(bytes.size)
            out.write(bytes)
        }

        fun writeTagName(type: Byte, name: String) {
            writeByte(type.toInt())
            writeString(name)
        }
    }
}