package command.plus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

// 支持的抖动算法
enum class DitherAlgorithm {
NONE, FLOYD_STEINBERG, BAYER_2x2, BAYER_4x4, BAYER_8x8, ORDERED_3x3, ATKINSON, BURKES
}

// 支持的色彩空间
enum class ColorSpace {
RGB, HSV, LAB
}

// 生成器运行模式
enum class GeneratorMode {
TEXTURE_PATCH,  // 传统模式：读取贴图文件并计算平均色
MAP_MAPPING     // 地图模式：基于 HEX 文本进行纯色匹配
}

// 颜色数据类
private class BlockData(
val id: String,
val bitmap: Bitmap?,
val rgb: FloatArray,
val hsv: FloatArray,
val lab: DoubleArray,
var pureColorHex: String? = null // 允许后续动态注入或修改 HEX 颜色值
)

class PixelArtGenerator private constructor(
private val mode: GeneratorMode,                 // 运行模式
private val inputImageFile: File?,
private val inputImageStream: InputStream?,
private val outputDir: File,
private val textureFiles: List<File>,
private val mapMappingText: String?,             // 纯色预览所需的文本映射
private val similarityThreshold: Float,          // 地图模式：相似度筛选阈值
private val generatePureColorPreview: Boolean,    // 是否输出纯色拼接预览
private val width: Int,
private val height: Int,
private val colorSpace: ColorSpace,
private val ditherAlgorithm: DitherAlgorithm,
private val emptyBlockId: String,
private val Thex: Int,
private val They: Int,
private val Thez: Int,
private val TheMirrorh: Boolean,
private val TheMirrorv: Boolean,
private val TheObj: String,
private val TheName: String,
private val TheLimit: Int,
private val TheinnerStep: Int,
private val TheinnerAxis: McCommandGenerator.Axis,
private val TheouterStep: Int,
private val TheouterAxis: McCommandGenerator.Axis,
private val StartOffset: Int,
private val ForbiddenScores: Set<Int>,
private val Multiplier: Int,
private val isAddTxt: Boolean,
private val callback: Callback?
) {

interface Callback {
fun onProgress(message: String)
fun onSuccess(resultImage: File, resultTxt: File, resultTotal: String)
fun onError(e: Exception)
}

class Builder {
private var mode: GeneratorMode = GeneratorMode.TEXTURE_PATCH
private var inputImageFile: File? = null
private var inputImageStream: InputStream? = null
private var outputDir: File? = null
private var textureFiles: List<File> = emptyList()
private var mapMappingText: String? = null
private var similarityThreshold: Float = 1.0f
private var generatePureColorPreview: Boolean = false
private var width: Int = 128
private var height: Int = 128
private var colorSpace: ColorSpace = ColorSpace.RGB
private var ditherAlgorithm: DitherAlgorithm = DitherAlgorithm.NONE
private var emptyBlockId: String = "air"
private var Thex: Int = 0
private var They: Int = 0
private var Thez: Int = 0
private var TheMirrorh: Boolean = false
private var TheMirrorv: Boolean = false
private var TheObj: String = "n"
private var TheName: String = "C"
private var TheLimit: Int = 0
private var TheinnerStep: Int = 1
private var TheinnerAxis: McCommandGenerator.Axis = McCommandGenerator.Axis.长
private var TheouterStep: Int = 1
private var TheouterAxis: McCommandGenerator.Axis = McCommandGenerator.Axis.宽
private var StartOffset: Int = 0
private var ForbiddenScores: Set<Int> = emptySet()
private var Multiplier: Int = 0
private var isAddTxt: Boolean = false
private var callback: Callback? = null

fun setGeneratorMode(mode: GeneratorMode) = apply { this.mode = mode }    
fun setInputImageFile(file: File) = apply { this.inputImageFile = file }    
fun setInputImageStream(file: InputStream) = apply { this.inputImageStream = file }    
fun setOutputDir(dir: File) = apply { this.outputDir = dir }    
fun setTextureFiles(files: List<File>) = apply { this.textureFiles = files }    
    
fun setMapModeConfig(similarity: Float) = apply {    
    this.mode = GeneratorMode.MAP_MAPPING    
    this.similarityThreshold = similarity    
}    

fun setDimensions(width: Int, height: Int) = apply {    
    this.width = width    
    this.height = height    
}    
fun setColorSpace(space: ColorSpace) = apply { this.colorSpace = space }    
fun setDitherAlgorithm(algo: DitherAlgorithm) = apply { this.ditherAlgorithm = algo }    
fun setEmptyBlockId(id: String) = apply { this.emptyBlockId = id }    
fun setThex(num: Int) = apply { this.Thex = num }    
fun setThey(num: Int) = apply { this.They = num }    
fun setThez(num: Int) = apply { this.Thez = num }    
fun setTheMirrorh(num: Boolean) = apply { this.TheMirrorh = num }    
fun setTheMirrorv(num: Boolean) = apply { this.TheMirrorv = num }    
fun setTheObj(num: String) = apply { this.TheObj = num }    
fun setTheName(num: String) = apply { this.TheName = num }    
fun setTheLimit(num: Int) = apply { this.TheLimit = num }    
fun setTheinnerStep(num: Int) = apply { this.TheinnerStep = num }    
fun setTheouterStep(num: Int) = apply { this.TheouterStep = num }    
fun setTheinnerAxis(num: McCommandGenerator.Axis) = apply { this.TheinnerAxis = num }    
fun setTheouterAxis(num: McCommandGenerator.Axis) = apply { this.TheouterAxis = num }    
fun setStartOffset(num: Int) = apply { this.StartOffset = num }    
fun setForbiddenScores(num: Set<Int>) = apply { this.ForbiddenScores = num }    
fun setMultiplier(num: Int) = apply { this.Multiplier = num }    
fun setisAddTxt(bl: Boolean) = apply { this.isAddTxt = bl }    
fun setGeneratePureColorPreview(enable: Boolean) = apply { this.generatePureColorPreview = enable }    
fun setMapMappingText(text: String) = apply { this.mapMappingText = text } // 新增：允许在传统模式下也传入映射文本    
fun setCallback(callback: Callback) = apply { this.callback = callback }    

fun build(): PixelArtGenerator {    
    require(inputImageFile == null || inputImageStream == null) { "必须设置输入图片源" }    
    requireNotNull(outputDir) { "必须设置输出文件夹" }    
    if (mode == GeneratorMode.TEXTURE_PATCH) {    
        require(textureFiles.isNotEmpty()) { "传统贴图模式下，方块贴图文件列表不能为空" }    
        if (generatePureColorPreview) {    
            require(!mapMappingText.isNullOrBlank()) { "开启纯色预览时，映射文本不能为空" }    
        }    
    } else {    
        require(!mapMappingText.isNullOrBlank()) { "地图映射模式下，映射文本不能为空" }    
    }    
    return PixelArtGenerator(    
        mode, inputImageFile, inputImageStream, outputDir!!, textureFiles, mapMappingText,    
        similarityThreshold, generatePureColorPreview, width, height, colorSpace, ditherAlgorithm, emptyBlockId,    
        Thex, They, Thez, TheMirrorh, TheMirrorv, TheObj, TheName, TheLimit, TheinnerStep, TheinnerAxis,    
        TheouterStep, TheouterAxis, StartOffset, ForbiddenScores, Multiplier, isAddTxt, callback    
    )    
}

}

private val executor = Executors.newSingleThreadExecutor()
private val mainHandler = Handler(Looper.getMainLooper())

private fun notifyProgress(msg: String) { mainHandler.post { callback?.onProgress(msg) } }
private fun notifySuccess(img: File, txt: File, total: String) { mainHandler.post { callback?.onSuccess(img, txt, total) } }
private fun notifyError(e: Exception) { mainHandler.post { callback?.onError(e) } }

companion object {
private val BAYER_2X2 = arrayOf(floatArrayOf(0f/4f, 2f/4f), floatArrayOf(3f/4f, 1f/4f))
private val BAYER_4X4 = arrayOf(
floatArrayOf( 0f/16f,  8f/16f,  2f/16f, 10f/16f),
floatArrayOf(12f/16f,  4f/16f, 14f/16f,  6f/16f),
floatArrayOf( 3f/16f, 11f/16f,  1f/16f,  9f/16f),
floatArrayOf(15f/16f,  7f/16f, 13f/16f,  5f/16f)
)
private val BAYER_8X8 = arrayOf(
floatArrayOf( 0f/64, 32f/64,  8f/64, 40f/64,  2f/64, 34f/64, 10f/64, 42f/64),
floatArrayOf(48f/64, 16f/64, 56f/64, 24f/64, 50f/64, 18f/64, 58f/64, 26f/64),
floatArrayOf(12f/64, 44f/64,  4f/64, 36f/64, 14f/64, 46f/64,  6f/64, 38f/64),
floatArrayOf(60f/64, 28f/64, 52f/64, 20f/64, 62f/64, 30f/64, 54f/64, 22f/64),
floatArrayOf( 3f/64, 35f/64, 11f/64, 43f/64,  1f/64, 33f/64,  9f/64, 41f/64),
floatArrayOf(51f/64, 19f/64, 59f/64, 27f/64, 49f/64, 17f/64, 57f/64, 25f/64),
floatArrayOf(15f/64, 47f/64,  7f/64, 39f/64, 13f/64, 45f/64,  5f/64, 37f/64),
floatArrayOf(63f/64, 31f/64, 55f/64, 23f/64, 61f/64, 29f/64, 53f/64, 21f/64)
)
private val ORDERED_3X3 = arrayOf(floatArrayOf(7f/9f, 2f/9f, 6f/9f), floatArrayOf(4f/9f, 0f/9f, 1f/9f), floatArrayOf(3f/9f, 8f/9f, 5f/9f))
}

fun generate() {
executor.execute {
try {
if (!outputDir.exists()) outputDir.mkdirs()

// 1. 根据设定的运行模式加载基础色彩库    
        val blockDataList = if (mode == GeneratorMode.MAP_MAPPING) {    
            notifyProgress("正在解析地图映射文本...")    
            parseMapMappingText(mapMappingText ?: "")    
        } else {    
            notifyProgress("正在加载方块贴图...")    
            val list = loadBlockTextures()    
            // 【核心修改】：在传统模式下，若开启了纯色预览且传入了映射文本，则解析文本并注入对应的 HEX 颜色    
            if (generatePureColorPreview && !mapMappingText.isNullOrBlank()) {    
                notifyProgress("正在注入纯色预览映射表...")    
                injectHexColorsFromText(list, mapMappingText)    
            }    
            list    
        }    
        if (blockDataList.isEmpty()) throw IllegalStateException("未能成功加载任何调色板色彩数据")    

        // 2. 加载并缩放原图    
        notifyProgress("正在处理原图...")    
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }    
        val origBitmap = when {    
            inputImageStream != null -> inputImageStream.use { BitmapFactory.decodeStream(it, null, options) }    
            inputImageFile != null -> BitmapFactory.decodeFile(inputImageFile.absolutePath, options)    
            else -> null    
        } ?: throw IllegalArgumentException("无法解码输入图片")    

        val resizedBitmap = Bitmap.createScaledBitmap(origBitmap, width, height, true)    

        // 3. 核心色彩量化匹配    
        notifyProgress("正在执行 ${colorSpace.name} 匹配 (Algorithm=${ditherAlgorithm.name})...")    
        val (chosenGrid, blockUsage) = processImage(resizedBitmap, blockDataList)    

        // 4. 拼装大图（支持贴图拼接或纯色扁平拼接）    
        notifyProgress("正在生成效果预览图...")    
        val isPurePreview = generatePureColorPreview     
        val suffix = if (isPurePreview) "pure_map" else colorSpace.name.lowercase()    
        val resultImageFile = File(outputDir, "${inputImageFile?.nameWithoutExtension ?: "output"}_$suffix.png")    
        assembleAndSaveImage(chosenGrid, blockDataList, resultImageFile, isPurePreview)    

        // 5. 保存 TXT 数据及命令生成    
        notifyProgress("正在生成 TXT 蓝图...")    
        val resultTxtFile = File(outputDir, "Command_${inputImageFile?.nameWithoutExtension ?: "output"}_${colorSpace.name.lowercase()}.txt")    
        val inputData = generateTextData(chosenGrid)    
        if (isAddTxt) saveTextData(chosenGrid, File(outputDir, "Block_${inputImageFile?.nameWithoutExtension ?: "output"}_${colorSpace.name.lowercase()}.txt"))    
            
        var commandsSize = 0    
        McCommandGenerator.Builder()    
            .setInputData(inputData)    
            .setStartCoords(长 = Thex, 深 = They, 宽 = Thez)    
            .setAxisConfig(innerAxis = TheinnerAxis, innerStep = TheinnerStep, outerAxis = TheouterAxis, outerStep = TheouterStep)    
            .setMirror(horizontal = TheMirrorh, vertical = TheMirrorv)    
            .setScoreboardObj(TheObj).setEntityName(TheName).setCharLimit(TheLimit).setOutputFile(resultTxtFile)    
            .setStartScoreOffset(StartOffset).setForbiddenScores(ForbiddenScores).setGenerationMultiplier(Multiplier)    
            .setCallback { generatedCommands ->    
                commandsSize = generatedCommands.count { it.contains("/") }    
            }.build().generate()    

        val resultTotal = buildString {    
            appendLine("[工作模式] ${mode.name}")    
            appendLine("[统计] 命令方块数量: $commandsSize")    
            appendLine("[统计] 使用量最多的方块:")    
            blockUsage.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}: ${it.value}") }    
        }    
        notifySuccess(resultImageFile, resultTxtFile, resultTotal)    
    } catch (e: Exception) {    
        e.printStackTrace()    
        notifyError(e)    
    } finally {    
        executor.shutdown()    
    }    
}

}

/**

解析文本并为已加载的 Block 注入映射好的纯色值（用于传统模式纯色预览）
*/
private fun injectHexColorsFromText(blocks: List<BlockData>, text: String) {
val blockMap = blocks.associateBy { it.id }
text.lines().forEach { rawLine ->
val line = rawLine.trim()
if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) return@forEach
val parts = line.split("=", limit = 2)
if (parts.size < 2) return@forEach
val id = parts[0].trim()
val hexColor = parts[1].trim()

// 仅对当前加载的贴图列表中存在的方块进行 HEX 注入    
 blockMap[id]?.let { block ->    
     try {    
         Color.parseColor(hexColor) // 验证 HEX 颜色是否合法    
         block.pureColorHex = hexColor    
     } catch (e: Exception) {    
         // 忽略格式错误的颜色    
     }    
 }

}
}


/**

【地图模式】解析纯色映射文本（仅保留已选 textureFiles 中存在的有效方块）
*/
private fun parseMapMappingText(text: String): List<BlockData> {
val list = mutableListOf<BlockData>()
val textureMap = textureFiles.associateBy { it.nameWithoutExtension }

text.lines().forEach { rawLine ->
val line = rawLine.trim()
if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) return@forEach
val parts = line.split("=", limit = 2)
if (parts.size < 2) return@forEach
val id = parts[0].trim()
val hexColor = parts[1].trim()

val matchedFile = textureMap[id] ?: return@forEach    

 try {    
     val bitmap = BitmapFactory.decodeFile(matchedFile.absolutePath) ?: return@forEach    

     val colorInt = Color.parseColor(hexColor)    
     val rgb = floatArrayOf(Color.red(colorInt) / 255f, Color.green(colorInt) / 255f, Color.blue(colorInt) / 255f)    
     val hsv = FloatArray(3).apply { Color.colorToHSV(colorInt, this); this[0] /= 360f }    
     val lab = DoubleArray(3).apply { ColorUtils.colorToLAB(colorInt, this) }    

     list.add(BlockData(id, bitmap, rgb, hsv, lab, hexColor))    
 } catch (e: Exception) {    
     // 自动忽略配置错误行或无法解析的颜色值    
 }

}
return list
}


/**

【传统模式】加载物理方块贴图计算颜色
*/
private fun loadBlockTextures(): List<BlockData> {
val list = mutableListOf<BlockData>()
for (file in textureFiles) {
if (!file.exists() || !file.isFile) continue
val id = file.nameWithoutExtension
val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue

var rSum = 0f; var gSum = 0f; var bSum = 0f; var validPixels = 0    
 for (y in 0 until bitmap.height) {    
     for (x in 0 until bitmap.width) {    
         val color = bitmap.getPixel(x, y)    
         if (Color.alpha(color) > 12) {    
             rSum += Color.red(color) / 255f    
             gSum += Color.green(color) / 255f    
             bSum += Color.blue(color) / 255f    
             validPixels++    
         }    
     }    
 }    
 if (validPixels > 0) {    
     val rgb = floatArrayOf(rSum / validPixels, gSum / validPixels, bSum / validPixels)    
     val colorInt = Color.rgb((rgb[0]*255).toInt(), (rgb[1]*255).toInt(), (rgb[2]*255).toInt())    
     val hsv = FloatArray(3).apply { Color.colorToHSV(colorInt, this); this[0] /= 360f }    
     val lab = DoubleArray(3).apply { ColorUtils.colorToLAB(colorInt, this) }    

     // 传统模式下的默认保底 HEX 颜色（由平均色计算得出）    
     val defaultHexColor = String.format("#%06X", 0xFFFFFF and colorInt)    

     list.add(BlockData(id, bitmap, rgb, hsv, lab, defaultHexColor))    
 }

}
return list
}


private fun safeAddError(errBuff: Array<Array<FloatArray>>, y: Int, x: Int, error: FloatArray, weight: Float) {
if (y < errBuff.size && x >= 0 && x < errBuff[0].size) {
for (i in 0..2) errBuff[y][x][i] += error[i] * weight
}
}

private fun processImage(bitmap: Bitmap, blocks: List<BlockData>): Pair<Array<Array<String>>, Map<String, Int>> {
val grid = Array(height) { Array(width) { "" } }
val usageMap = mutableMapOf<String, Int>()
val imgData = Array(height) { Array(width) { FloatArray(3) } }
val alphaMask = Array(height) { BooleanArray(width) }

for (y in 0 until height) {    
    for (x in 0 until width) {    
        val pixel = bitmap.getPixel(x, y)    
        alphaMask[y][x] = Color.alpha(pixel) > 12    
        if (alphaMask[y][x]) {    
            when (colorSpace) {    
                ColorSpace.RGB -> {    
                    imgData[y][x][0] = Color.red(pixel) / 255f    
                    imgData[y][x][1] = Color.green(pixel) / 255f    
                    imgData[y][x][2] = Color.blue(pixel) / 255f    
                }    
                ColorSpace.HSV -> {    
                    val hsv = FloatArray(3).apply { Color.colorToHSV(pixel, this) }    
                    imgData[y][x][0] = hsv[0] / 360f    
                    imgData[y][x][1] = hsv[1]    
                    imgData[y][x][2] = hsv[2]    
                }    
                ColorSpace.LAB -> {    
                    val lab = DoubleArray(3).apply { ColorUtils.colorToLAB(pixel, this) }    
                    imgData[y][x][0] = lab[0].toFloat()    
                    imgData[y][x][1] = lab[1].toFloat()    
                    imgData[y][x][2] = lab[2].toFloat()    
                }    
            }    
        }    
    }    
}    

val errBuff = Array(height) { Array(width) { FloatArray(3) } }    
val bayerSpread = 0.15f    

for (y in 0 until height) {    
    for (x in 0 until width) {    
        if (!alphaMask[y][x]) {    
            grid[y][x] = emptyBlockId    
            continue    
        }    

        var bayerOffset = 0f    
        when (ditherAlgorithm) {    
            DitherAlgorithm.BAYER_2x2 -> bayerOffset = (BAYER_2X2[y % 2][x % 2] - 0.5f) * bayerSpread    
            DitherAlgorithm.BAYER_4x4 -> bayerOffset = (BAYER_4X4[y % 4][x % 4] - 0.5f) * bayerSpread    
            DitherAlgorithm.BAYER_8x8 -> bayerOffset = (BAYER_8X8[y % 8][x % 8] - 0.5f) * bayerSpread    
            DitherAlgorithm.ORDERED_3x3 -> bayerOffset = (ORDERED_3X3[y % 3][x % 3] - 0.5f) * bayerSpread    
            else -> {}    
        }    

        val currentVal = FloatArray(3)    
        for (c in 0..2) {    
            currentVal[c] = imgData[y][x][c] + errBuff[y][x][c] + bayerOffset    
            if (colorSpace == ColorSpace.RGB || (colorSpace == ColorSpace.HSV && c > 0)) {    
                currentVal[c] = currentVal[c].coerceIn(0f, 1f)    
            }    
        }    

        val bestBlock = findNearestBlock(currentVal, blocks)    
        grid[y][x] = bestBlock.id    
        usageMap[bestBlock.id] = usageMap.getOrDefault(bestBlock.id, 0) + 1    

        if (ditherAlgorithm in listOf(DitherAlgorithm.FLOYD_STEINBERG, DitherAlgorithm.ATKINSON, DitherAlgorithm.BURKES)) {    
            val blockVal = when (colorSpace) {    
                ColorSpace.RGB -> bestBlock.rgb    
                ColorSpace.HSV -> bestBlock.hsv    
                ColorSpace.LAB -> floatArrayOf(bestBlock.lab[0].toFloat(), bestBlock.lab[1].toFloat(), bestBlock.lab[2].toFloat())    
            }    
            val err = FloatArray(3) { c -> (imgData[y][x][c] + errBuff[y][x][c]) - blockVal[c] }    
            when (ditherAlgorithm) {    
                DitherAlgorithm.FLOYD_STEINBERG -> {    
                    safeAddError(errBuff, y, x + 1, err, 7 / 16f)    
                    safeAddError(errBuff, y + 1, x - 1, err, 3 / 16f)    
                    safeAddError(errBuff, y + 1, x, err, 5 / 16f)    
                    safeAddError(errBuff, y + 1, x + 1, err, 1 / 16f)    
                }    
                DitherAlgorithm.ATKINSON -> {    
                    safeAddError(errBuff, y, x + 1, err, 1 / 8f)    
                    safeAddError(errBuff, y, x + 2, err, 1 / 8f)    
                    safeAddError(errBuff, y + 1, x - 1, err, 1 / 8f)    
                    safeAddError(errBuff, y + 1, x, err, 1 / 8f)    
                    safeAddError(errBuff, y + 1, x + 1, err, 1 / 8f)    
                    safeAddError(errBuff, y + 2, x, err, 1 / 8f)    
                }    
                DitherAlgorithm.BURKES -> {    
                    safeAddError(errBuff, y, x + 1, err, 8 / 32f); safeAddError(errBuff, y, x + 2, err, 4 / 32f)    
                    safeAddError(errBuff, y + 1, x - 2, err, 2 / 32f); safeAddError(errBuff, y + 1, x - 1, err, 4 / 32f)    
                    safeAddError(errBuff, y + 1, x, err, 8 / 32f); safeAddError(errBuff, y + 1, x + 1, err, 4 / 32f)    
                    safeAddError(errBuff, y + 1, x + 2, err, 2 / 32f)    
                }    
                else -> {}    
            }    
        }    
    }    
}    
return Pair(grid, usageMap)

}

private fun findNearestBlock(target: FloatArray, blocks: List<BlockData>): BlockData {
var minDistance = Double.MAX_VALUE
val bestMatches = mutableListOf<BlockData>()

for (block in blocks) {    
    val dist: Double = when (colorSpace) {    
        ColorSpace.RGB -> {    
            val dr = target[0].toDouble() - block.rgb[0]    
            val dg = target[1].toDouble() - block.rgb[1]    
            val db = target[2].toDouble() - block.rgb[2]    
            dr * dr + dg * dg + db * db    
        }    
        ColorSpace.HSV -> {    
            val dh = target[0].toDouble() - block.hsv[0]    
            val ds = target[1].toDouble() - block.hsv[1]    
            val dv = target[2].toDouble() - block.hsv[2]    
            dh * dh + ds * ds + dv * dv    
        }    
        ColorSpace.LAB -> {    
            val dl = target[0].toDouble() - block.lab[0]    
            val da = target[1].toDouble() - block.lab[1]    
            val db = target[2].toDouble() - block.lab[2]    
            dl * dl + da * da + db * db    
        }    
    }    

    if (dist < minDistance) {    
        minDistance = dist    
        bestMatches.clear()    
        bestMatches.add(block)    
    } else if (dist == minDistance) {    
        bestMatches.add(block)    
    }    
}    

if (mode == GeneratorMode.MAP_MAPPING) {

val primaryMatch = bestMatches.first()
val targetHex = primaryMatch.pureColorHex
val identicalColorBlocks = blocks.filter { it.pureColorHex == targetHex }

if (similarityThreshold == -1f) {
return identicalColorBlocks[Random.nextInt(identicalColorBlocks.size)]
} else {
// 直接在所有同色方块中排序，挑出相似度最接近 similarityThreshold 的那一个
return identicalColorBlocks.minByOrNull { block ->
if (block.bitmap == null) {
// 没有贴图的纯色方块，相似度默认为 1.0f
val score = 1.0f
kotlin.math.abs(score - similarityThreshold)
} else {
val score = calculateTextureSimilarityToPure(block, block.pureColorHex)
// 计算当前方块的相似度与目标阈值的“距离”，越小说明越接近目标值
kotlin.math.abs(score - similarityThreshold)
}
} ?: primaryMatch
}

}

return bestMatches.first()
}

private fun calculateTextureSimilarityToPure(block: BlockData, hex: String?): Float {
if (block.bitmap == null || hex == null) return 1.0f
val pureColor = Color.parseColor(hex)
val pr = Color.red(pureColor) / 255f
val pg = Color.green(pureColor) / 255f
val pb = Color.blue(pureColor) / 255f
val dist = sqrt((block.rgb[0] - pr).pow(2) + (block.rgb[1] - pg).pow(2) + (block.rgb[2] - pb).pow(2))
return (1.0f - (dist / sqrt(3.0f))).coerceIn(0f, 1f)
}

private fun assembleAndSaveImage(grid: Array<Array<String>>, blocks: List<BlockData>, outFile: File, purePreviewMode: Boolean) {
val blockMap = blocks.associateBy { it.id }

if (purePreviewMode) {    
    val outBitmap = Bitmap.createBitmap(width * 16, height * 16, Bitmap.Config.ARGB_8888)    
    val canvas = Canvas(outBitmap)    
    val paint = Paint()    
        
    for (y in 0 until height) {    
        for (x in 0 until width) {    
            val bId = grid[y][x]    
            if (bId == "air" || bId == emptyBlockId) continue    
                
            // 获取注入或自带的有效 HEX，若都不存在则默认使用透明保底    
            val hex = blockMap[bId]?.pureColorHex ?: "#00000000"    
                
            try {    
                paint.color = Color.parseColor(hex)    
                canvas.drawRect((x * 16).toFloat(), (y * 16).toFloat(), ((x + 1) * 16).toFloat(), ((y + 1) * 16).toFloat(), paint)    
            } catch (e: Exception) {    
                // 防止 Hex 格式引发意外崩溃    
            }    
        }    
    }    
    FileOutputStream(outFile).use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }    
    outBitmap.recycle()    
} else {    
    val sampleBmp = blocks.firstOrNull { it.bitmap != null }?.bitmap    
    val blockW = sampleBmp?.width ?: 16    
    val blockH = sampleBmp?.height ?: 16    

    val outBitmap = Bitmap.createBitmap(width * blockW, height * blockH, Bitmap.Config.ARGB_8888)    
    val canvas = Canvas(outBitmap)    
    val paint = Paint()    

    for (y in 0 until height) {    
        for (x in 0 until width) {    
            val bId = grid[y][x]    
            if (bId == "air" || bId == emptyBlockId) continue    
                
            val tile = blockMap[bId]?.bitmap    
            if (tile != null) {    
                canvas.drawBitmap(tile, (x * blockW).toFloat(), (y * blockH).toFloat(), null)    
            } else {    
                val hex = blockMap[bId]?.pureColorHex ?: "#000000"    
                paint.color = Color.parseColor(hex)    
                canvas.drawRect((x * blockW).toFloat(), (y * blockH).toFloat(), ((x + 1) * blockW).toFloat(), ((y + 1) * blockH).toFloat(), paint)    
            }    
        }    
    }    
    FileOutputStream(outFile).use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }    
    outBitmap.recycle()    
}

}

private fun saveTextData(grid: Array<Array<String>>, outFile: File) {
FileWriter(outFile).use { writer ->
writer.write("[[\n")
for (y in 0 until height) {
val line = grid[y].joinToString(",") { "\"$it\"" }
writer.write("[$line]\n")
}
writer.write("]]")
}
}

private fun generateTextData(grid: Array<Array<String>>): String {
val lines = mutableListOf<String>()
lines.add("[[")
for (y in 0 until height) {
val line = grid[y].joinToString(",") { "\"$it\"" }
lines.add("[$line]")
}
lines.add("]]")
return lines.joinToString("\n")
}

}
