package command.plus

import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.InputStream

/**
 * 优化后的 MCPack 生成器
 * @param sourceDirOrFile 包含一个或多个生成的 .mcstructure 文件的目录或单个文件
 * @param limitX 切分时使用的单片最大 X 跨度，用于在 mcfunction 中计算一键密铺的偏移坐标
 * @param limitY 切分时使用的单片最大 Y 跨度
 */
class MCPackGenerator(
    private val sourceDirOrFile: File,
    private val iconStream: InputStream? = null,
    private val limitX: Int = 64,
    private val limitZ: Int = 64,
    private val author: String = "KotlinGenerator"
) {
    // 基础包名采用父目录或文件的名称
    private val packName = sourceDirOrFile.nameWithoutExtension.ifEmpty { "Generated_Pack" }
    private val uuidHeader = UUID.randomUUID().toString()
    private val uuidModule = UUID.randomUUID().toString()
    
    fun generate(cacheDir: File): File? {
        val buildDir = File(cacheDir, "temp_pack_${System.currentTimeMillis()}")
        // 输出目的地改到源文件的同级目录下
        val targetParent = sourceDirOrFile.parentFile ?: cacheDir
        val outputFile = File(targetParent, "$packName.mcpack")

        try {
            if (buildDir.exists()) buildDir.deleteRecursively()
            val structuresDir = File(buildDir, "structures").apply { mkdirs() }
            val functionsDir = File(buildDir, "functions").apply { mkdirs() }

            // 1. 收集并搬运所有的结构文件
            val structureFiles = mutableListOf<File>()
            if (sourceDirOrFile.isDirectory) {
                sourceDirOrFile.listFiles { _, name -> name.endsWith(".mcstructure") }?.forEach {
                    structureFiles.add(it)
                }
            } else if (sourceDirOrFile.exists() && sourceDirOrFile.name.endsWith(".mcstructure")) {
                structureFiles.add(sourceDirOrFile)
            }

            if (structureFiles.isEmpty()) return null

            // 复制到 mcpack 内部工作区
            for (file in structureFiles) {
                file.copyTo(File(structuresDir, file.name), overwrite = true)
            }


            // 2. 动态生成 load.mcfunction，实现水平面(X与Z)一键多结构密铺
            val sbFunction = StringBuilder()
            sbFunction.append("# Auto-generated structure placement function\n")
            
            for (file in structureFiles) {
                val name = file.nameWithoutExtension
                // 正则捕获切片名中的 x 和 z 索引 (例如 part_x1_z2)
                val regex = ".*_x(\\d+)_z(\\d+)".toRegex()
                val matchResult = regex.find(name)
                
                if (matchResult != null) {
                    val cx = matchResult.groupValues[1].toInt()
                    val cz = matchResult.groupValues[2].toInt()
                    
                    // 计算世界中的水平相对偏移
                    // 对应指令参数: structure load <名称> <x> <y> <z>
                    val offsetX = cx * limitX
                    val offsetZ = cz * limitZ
                    // 高度 Y 统一为 ~ (相对执行者脚下高度)，或者固定高度 ~0 
                    sbFunction.append("structure load \"$name\" ~$offsetX ~ ~$offsetZ\n")
                } else {
                    sbFunction.append("structure load \"$name\" ~ ~2 ~\n")
                }
            }
            
            File(functionsDir, "load.mcfunction").writeText(sbFunction.toString())

            // 3. 生成符合 1.20+ 游戏版本的行为包 manifest.json
            val manifestJson = """
            {
                "format_version": 2,
                "header": {
                    "description": "Pack for §a§l$packName §r- Created by $author",
                    "name": "$packName",
                    "uuid": "$uuidHeader",
                    "version": [1, 0, 0],
                    "min_engine_version": [1, 20, 0]
                },
                "modules": [
                    {
                        "description": "Functions and Structures",
                        "type": "data",
                        "uuid": "$uuidModule",
                        "version": [1, 0, 0]
                    }
                ]
            }
            """.trimIndent()
            File(buildDir, "manifest.json").writeText(manifestJson)
            
            // 4. 处理高清图标
            iconStream?.use { input ->
                val iconFile = File(buildDir, "pack_icon.png")
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }

            // 5. 压缩为 .mcpack 并清理痕迹
            zipPack(buildDir, outputFile)

            // 清理临时生成的零散本地结构文件以及编译工作区
            if (sourceDirOrFile.isDirectory) {
                sourceDirOrFile.deleteRecursively()
            } else {
                sourceDirOrFile.delete()
            }
            buildDir.deleteRecursively()

            return outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun zipPack(sourceDir: File, outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            sourceDir.walkTopDown().forEach { file ->
                if (!file.isDirectory) {
                    val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}