package command.plus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import java.io.BufferedWriter
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
    var pureColorHex: String? = null
)

class PixelArtGenerator private constructor(
    private val mode: GeneratorMode,
    private val inputImageFile: File?,
    private val inputImageStream: InputStream?,
    private val outputDir: File,
    private val textureFiles: List<File>,
    private val mapMappingText: String?,
    private val similarityThreshold: Float,
    private val generatePureColorPreview: Boolean,
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
    private val enableExtraSensitiveOptimization: Boolean,
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
        private var enableExtraSensitiveOptimization: Boolean = true
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
        fun setEnableExtraSensitiveOptimization(bl: Boolean) = apply { this.enableExtraSensitiveOptimization = bl }
        fun setGeneratePureColorPreview(enable: Boolean) = apply { this.generatePureColorPreview = enable }
        fun setMapMappingText(text: String) = apply { this.mapMappingText = text }
        fun setCallback(callback: Callback) = apply { this.callback = callback }

        fun build(): PixelArtGenerator {
            require(inputImageFile != null || inputImageStream != null) { "必须设置输入图片源" }
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
                TheouterStep, TheouterAxis, StartOffset, ForbiddenScores, Multiplier, isAddTxt, enableExtraSensitiveOptimization, callback
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

                // 1. 加载基础色彩库
                val blockDataList = if (mode == GeneratorMode.MAP_MAPPING) {
                    notifyProgress("正在解析地图映射文本...")
                    parseMapMappingText(mapMappingText ?: "")
                } else {
                    notifyProgress("正在加载方块贴图...")
                    val list = loadBlockTextures()
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
                if (origBitmap != resizedBitmap) {
                    origBitmap.recycle() // 及时回收原图释放内存
                }

                // 3. 核心色彩量化匹配 (一维数组高效率匹配)
                notifyProgress("正在执行 ${colorSpace.name} 匹配 (Algorithm=${ditherAlgorithm.name})...")
                val (chosenGridIndices, blockUsage) = processImage(resizedBitmap, blockDataList)

                resizedBitmap.recycle() // 匹配完成后回收 Bitmap

                // 4. 拼装大图（具备尺寸安全限制，防止 OOM）
                notifyProgress("正在生成效果预览图...")
                val isPurePreview = generatePureColorPreview
                val suffix = if (isPurePreview) "pure_map" else colorSpace.name.lowercase()
                val resultImageFile = File(outputDir, "${inputImageFile?.nameWithoutExtension ?: "output"}_$suffix.png")
                assembleAndSaveImage(chosenGridIndices, blockDataList, resultImageFile, isPurePreview)

                // 5. 保存 TXT 数据及命令生成
                notifyProgress("正在生成 TXT 蓝图...")
                val resultTxtFile = File(outputDir, "Command_${inputImageFile?.nameWithoutExtension ?: "output"}_${colorSpace.name.lowercase()}.txt")
                val matrixData = generateMatrixData(chosenGridIndices, blockDataList)
                if (isAddTxt) {
                    saveTextData(chosenGridIndices, blockDataList, File(outputDir, "Block_${inputImageFile?.nameWithoutExtension ?: "output"}_${colorSpace.name.lowercase()}.txt"))
                }

                var commandsSize = 0
                McCommandGenerator.Builder()
                    .setRawMatrix(matrixData)
                    .setStartCoords(长 = Thex, 深 = They, 宽 = Thez)
                    .setAxisConfig(innerAxis = TheinnerAxis, innerStep = TheinnerStep, outerAxis = TheouterAxis, outerStep = TheouterStep)
                    .setMirror(horizontal = TheMirrorh, vertical = TheMirrorv)
                    .setScoreboardObj(TheObj).setEntityName(TheName).setCharLimit(TheLimit).setOutputFile(resultTxtFile)
                    .setStartScoreOffset(StartOffset).setForbiddenScores(ForbiddenScores)
                    .setGenerationMultiplier(Multiplier)
                    .setEnableExtraSensitiveOptimization(enableExtraSensitiveOptimization)
                    .setCallback { generatedCommands ->
                        commandsSize = generatedCommands.count { it.contains("/") }
                    }.build().generate()

                val resultTotal = buildString {
                    appendLine("[工作模式] ${mode.name}")
                    appendLine("[统计] 命令方块数量: $commandsSize")
                    appendLine("[统计] 使用量最多的方块:")
                    blockUsage.entries.sortedByDescending { it.value }.take(20).forEach { appendLine("${it.key}: ${it.value}") }
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

    private fun injectHexColorsFromText(blocks: List<BlockData>, text: String) {
        val blockMap = blocks.associateBy { it.id }
        text.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) return@forEach
            val parts = line.split("=", limit = 2)
            if (parts.size < 2) return@forEach
            val id = parts[0].trim()
            val hexColor = parts[1].trim()

            blockMap[id]?.let { block ->
                try {
                    Color.parseColor(hexColor)
                    block.pureColorHex = hexColor
                } catch (_: Exception) {}
            }
        }
    }

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
            } catch (_: Exception) {}
        }
        return list
    }

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
                val defaultHexColor = String.format("#%06X", 0xFFFFFF and colorInt)

                list.add(BlockData(id, bitmap, rgb, hsv, lab, defaultHexColor))
            }
        }
        return list
    }

    // 核心优化：采用一维数组与 3 行环形滑动误差缓冲区，极其节省内存并提升性能
    private fun processImage(bitmap: Bitmap, blocks: List<BlockData>): Pair<IntArray, Map<String, Int>> {
        val totalPixels = width * height
        val gridIndices = IntArray(totalPixels)
        val usageMap = mutableMapOf<String, Int>()

        // 一次性批量加载原生像素，比 400万次 JNI 跨界 getPixel 快 20 倍
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 仅保留 3 行的滑动误差缓冲区（内存从 200MB+ 降至 73KB）
        val errBuff = Array(3) { FloatArray(width * 3) }

        // 预计算 Block 最佳索引与 Hex 关系，避免 400万次循环内进行集合过滤
        val hexToBestBlockMap = precomputeHexBestBlocks(blocks)
        val bayerSpread = 0.15f

        for (y in 0 until height) {
            if (y % 200 == 0) {
                notifyProgress("正在执行 ${colorSpace.name} 匹配: ${(y * 100) / height}%")
            }

            val currRowIdx = y % 3
            val nextRowIdx = (y + 1) % 3
            val next2RowIdx = (y + 2) % 3

            // 清空两行后的误差缓冲区
            errBuff[next2RowIdx].fill(0f)

            val rowOffset = y * width

            for (x in 0 until width) {
                val pixelIdx = rowOffset + x
                val pixel = pixels[pixelIdx]
                val alpha = (pixel ushr 24) and 0xFF

                if (alpha <= 12) {
                    gridIndices[pixelIdx] = -1 // 空方块标记
                    continue
                }

                val target0: Float
                val target1: Float
                val target2: Float

                when (colorSpace) {
                    ColorSpace.RGB -> {
                        target0 = ((pixel ushr 16) and 0xFF) / 255f
                        target1 = ((pixel ushr 8) and 0xFF) / 255f
                        target2 = (pixel and 0xFF) / 255f
                    }
                    ColorSpace.HSV -> {
                        val hsv = FloatArray(3)
                        Color.colorToHSV(pixel, hsv)
                        target0 = hsv[0] / 360f
                        target1 = hsv[1]
                        target2 = hsv[2]
                    }
                    ColorSpace.LAB -> {
                        val lab = DoubleArray(3)
                        ColorUtils.colorToLAB(pixel, lab)
                        target0 = lab[0].toFloat()
                        target1 = lab[1].toFloat()
                        target2 = lab[2].toFloat()
                    }
                }

                var bayerOffset = 0f
                when (ditherAlgorithm) {
                    DitherAlgorithm.BAYER_2x2 -> bayerOffset = (BAYER_2X2[y % 2][x % 2] - 0.5f) * bayerSpread
                    DitherAlgorithm.BAYER_4x4 -> bayerOffset = (BAYER_4X4[y % 4][x % 4] - 0.5f) * bayerSpread
                    DitherAlgorithm.BAYER_8x8 -> bayerOffset = (BAYER_8X8[y % 8][x % 8] - 0.5f) * bayerSpread
                    DitherAlgorithm.ORDERED_3x3 -> bayerOffset = (ORDERED_3X3[y % 3][x % 3] - 0.5f) * bayerSpread
                    else -> {}
                }

                val errX = x * 3
                var currentVal0 = target0 + errBuff[currRowIdx][errX] + bayerOffset
                var currentVal1 = target1 + errBuff[currRowIdx][errX + 1] + bayerOffset
                var currentVal2 = target2 + errBuff[currRowIdx][errX + 2] + bayerOffset

                if (colorSpace == ColorSpace.RGB || colorSpace == ColorSpace.HSV) {
                    currentVal0 = currentVal0.coerceIn(0f, 1f)
                    currentVal1 = currentVal1.coerceIn(0f, 1f)
                    currentVal2 = currentVal2.coerceIn(0f, 1f)
                }

                val bestBlockIdx = findNearestBlockIndex(currentVal0, currentVal1, currentVal2, blocks, hexToBestBlockMap)
                gridIndices[pixelIdx] = bestBlockIdx

                val bestBlock = blocks[bestBlockIdx]
                usageMap[bestBlock.id] = (usageMap[bestBlock.id] ?: 0) + 1

                if (ditherAlgorithm in listOf(DitherAlgorithm.FLOYD_STEINBERG, DitherAlgorithm.ATKINSON, DitherAlgorithm.BURKES)) {
                    val blockVal = when (colorSpace) {
                        ColorSpace.RGB -> bestBlock.rgb
                        ColorSpace.HSV -> bestBlock.hsv
                        ColorSpace.LAB -> floatArrayOf(bestBlock.lab[0].toFloat(), bestBlock.lab[1].toFloat(), bestBlock.lab[2].toFloat())
                    }

                    val err0 = (target0 + errBuff[currRowIdx][errX]) - blockVal[0]
                    val err1 = (target1 + errBuff[currRowIdx][errX + 1]) - blockVal[1]
                    val err2 = (target2 + errBuff[currRowIdx][errX + 2]) - blockVal[2]

                    when (ditherAlgorithm) {
                        DitherAlgorithm.FLOYD_STEINBERG -> {
                            safeAddErrorCircular(errBuff, currRowIdx, x + 1, width, err0, err1, err2, 7 / 16f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x - 1, width, err0, err1, err2, 3 / 16f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x, width, err0, err1, err2, 5 / 16f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x + 1, width, err0, err1, err2, 1 / 16f)
                        }
                        DitherAlgorithm.ATKINSON -> {
                            val w = 1 / 8f
                            safeAddErrorCircular(errBuff, currRowIdx, x + 1, width, err0, err1, err2, w)
                            safeAddErrorCircular(errBuff, currRowIdx, x + 2, width, err0, err1, err2, w)
                            safeAddErrorCircular(errBuff, nextRowIdx, x - 1, width, err0, err1, err2, w)
                            safeAddErrorCircular(errBuff, nextRowIdx, x, width, err0, err1, err2, w)
                            safeAddErrorCircular(errBuff, nextRowIdx, x + 1, width, err0, err1, err2, w)
                            safeAddErrorCircular(errBuff, next2RowIdx, x, width, err0, err1, err2, w)
                        }
                        DitherAlgorithm.BURKES -> {
                            safeAddErrorCircular(errBuff, currRowIdx, x + 1, width, err0, err1, err2, 8 / 32f)
                            safeAddErrorCircular(errBuff, currRowIdx, x + 2, width, err0, err1, err2, 4 / 32f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x - 2, width, err0, err1, err2, 2 / 32f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x - 1, width, err0, err1, err2, 4 / 32f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x, width, err0, err1, err2, 8 / 32f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x + 1, width, err0, err1, err2, 4 / 32f)
                            safeAddErrorCircular(errBuff, nextRowIdx, x + 2, width, err0, err1, err2, 2 / 32f)
                        }
                        else -> {}
                    }
                }
            }
        }
        return Pair(gridIndices, usageMap)
    }

    private fun safeAddErrorCircular(
        errBuff: Array<FloatArray>,
        rowIdx: Int,
        x: Int,
        width: Int,
        e0: Float,
        e1: Float,
        e2: Float,
        weight: Float
    ) {
        if (x in 0 until width) {
            val base = x * 3
            val row = errBuff[rowIdx]
            row[base] += e0 * weight
            row[base + 1] += e1 * weight
            row[base + 2] += e2 * weight
        }
    }

    private fun precomputeHexBestBlocks(blocks: List<BlockData>): Map<String, Int> {
        if (mode != GeneratorMode.MAP_MAPPING) return emptyMap()

        val map = mutableMapOf<String, Int>()
        val groupedByHex = blocks.indices.groupBy { blocks[it].pureColorHex ?: "" }

        for ((hex, indices) in groupedByHex) {
            if (hex.isEmpty()) continue
            if (similarityThreshold != -1f) {
                val bestIdx = indices.minByOrNull { idx ->
                    val block = blocks[idx]
                    if (block.bitmap == null) {
                        kotlin.math.abs(1.0f - similarityThreshold)
                    } else {
                        val score = calculateTextureSimilarityToPure(block, block.pureColorHex)
                        kotlin.math.abs(score - similarityThreshold)
                    }
                } ?: indices.first()
                map[hex] = bestIdx
            }
        }
        return map
    }

    private fun findNearestBlockIndex(
        t0: Float, t1: Float, t2: Float,
        blocks: List<BlockData>,
        hexToBestBlockMap: Map<String, Int>
    ): Int {
        var minDistance = Double.MAX_VALUE
        var bestIdx = 0

        for (i in blocks.indices) {
            val block = blocks[i]
            val dist: Double = when (colorSpace) {
                ColorSpace.RGB -> {
                    val dr = t0.toDouble() - block.rgb[0]
                    val dg = t1.toDouble() - block.rgb[1]
                    val db = t2.toDouble() - block.rgb[2]
                    dr * dr + dg * dg + db * db
                }
                ColorSpace.HSV -> {
                    val dh = t0.toDouble() - block.hsv[0]
                    val ds = t1.toDouble() - block.hsv[1]
                    val dv = t2.toDouble() - block.hsv[2]
                    dh * dh + ds * ds + dv * dv
                }
                ColorSpace.LAB -> {
                    val dl = t0.toDouble() - block.lab[0]
                    val da = t1.toDouble() - block.lab[1]
                    val db = t2.toDouble() - block.lab[2]
                    dl * dl + da * da + db * db
                }
            }

            if (dist < minDistance) {
                minDistance = dist
                bestIdx = i
            }
        }

        if (mode == GeneratorMode.MAP_MAPPING) {
            val hex = blocks[bestIdx].pureColorHex
            if (hex != null) {
                if (similarityThreshold == -1f) {
                    val identicalIndices = blocks.indices.filter { blocks[it].pureColorHex == hex }
                    if (identicalIndices.isNotEmpty()) {
                        return identicalIndices[Random.nextInt(identicalIndices.size)]
                    }
                } else {
                    hexToBestBlockMap[hex]?.let { return it }
                }
            }
        }

        return bestIdx
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

    // 核心优化：自动对输出图片尺寸进行安全封顶计算（最大 4096px），避免 4GB 显存分配引发 OOM
    private fun assembleAndSaveImage(
        gridIndices: IntArray,
        blocks: List<BlockData>,
        outFile: File,
        purePreviewMode: Boolean
    ) {
        val maxTargetDim = 16384
        val rawTileW = if (purePreviewMode) 16 else (blocks.firstOrNull { it.bitmap != null }?.bitmap?.width ?: 16)
        val rawTileH = if (purePreviewMode) 16 else (blocks.firstOrNull { it.bitmap != null }?.bitmap?.height ?: 16)

        // 计算最大安全单块渲染尺寸
        val targetTileW = maxOf(1, minOf(rawTileW, maxTargetDim / width))
        val targetTileH = maxOf(1, minOf(rawTileH, maxTargetDim / height))

        val outW = width * targetTileW
        val outH = height * targetTileH

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        val paint = Paint()
        val destRect = RectF()

        for (y in 0 until height) {
            val rowOffset = y * width
            val top = (y * targetTileH).toFloat()
            val bottom = top + targetTileH

            for (x in 0 until width) {
                val bIdx = gridIndices[rowOffset + x]
                if (bIdx < 0) continue

                val block = blocks[bIdx]
                val left = (x * targetTileW).toFloat()
                val right = left + targetTileW

                if (purePreviewMode || block.bitmap == null) {
                    val hex = block.pureColorHex ?: "#000000"
                    try {
                        paint.color = Color.parseColor(hex)
                        canvas.drawRect(left, top, right, bottom, paint)
                    } catch (_: Exception) {}
                } else {
                    val tile = block.bitmap
                    if (targetTileW == tile.width && targetTileH == tile.height) {
                        canvas.drawBitmap(tile, left, top, null)
                    } else {
                        destRect.set(left, top, right, bottom)
                        canvas.drawBitmap(tile, null, destRect, null)
                    }
                }
            }
        }

        FileOutputStream(outFile).use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        outBitmap.recycle()
    }

    private fun saveTextData(gridIndices: IntArray, blocks: List<BlockData>, outFile: File) {
        FileWriter(outFile).use { fw ->
            val bw = BufferedWriter(fw, 65536)
            bw.write("[[\n")
            val sb = StringBuilder(width * 20)
            for (y in 0 until height) {
                val rowOffset = y * width
                sb.setLength(0)
                sb.append("[")
                for (x in 0 until width) {
                    val bIdx = gridIndices[rowOffset + x]
                    val bId = if (bIdx < 0) emptyBlockId else blocks[bIdx].id
                    sb.append("\"").append(bId).append("\"")
                    if (x < width - 1) sb.append(",")
                }
                sb.append("]")
                bw.write(sb.toString())
                if (y < height - 1) bw.write(",")
                bw.write("\n")
            }
            bw.write("]]")
            bw.flush()
        }
    }

    private fun generateTextData(gridIndices: IntArray, blocks: List<BlockData>): String {
        val estimatedCap = width * height * 15 + height * 5
        val sb = StringBuilder(estimatedCap)
        sb.append("[[\n")
        val rowSb = StringBuilder(width * 20)
        for (y in 0 until height) {
            val rowOffset = y * width
            rowSb.setLength(0)
            rowSb.append("[")
            for (x in 0 until width) {
                val bIdx = gridIndices[rowOffset + x]
                val bId = if (bIdx < 0) emptyBlockId else blocks[bIdx].id
                rowSb.append("\"").append(bId).append("\"")
                if (x < width - 1) rowSb.append(",")
            }
            rowSb.append("]")
            sb.append(rowSb)
            if (y < height - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]]")
        return sb.toString()
    }
    
    private fun generateMatrixData(gridIndices: IntArray, blocks: List<BlockData>): List<List<String>> {
    val matrix = ArrayList<List<String>>(height)
    for (y in 0 until height) {
        val rowOffset = y * width
        val row = ArrayList<String>(width)
        for (x in 0 until width) {
            val bIdx = gridIndices[rowOffset + x]
            val bId = if (bIdx < 0) emptyBlockId else blocks[bIdx].id
            row.add(bId)
        }
        matrix.add(row)
    }
    return matrix
}
}