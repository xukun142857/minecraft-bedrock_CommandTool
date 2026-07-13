package command.plus

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * DataManager - 解析新版文件格式并维护当前索引。
 *
 * 文件格式示例：
 * $1,0,1
 * /first line content
 * /second line content
 * $
 * $2,1,0
 * /another
 * $
 */
object DataManager {

    class Block(
        val type: Int,
        val configA: Int,
        val configB: Int,
        val configC: Int,
        val startIndex: Int
    ) {
        var endIndex: Int = startIndex

        companion object {
            const val TYPE_CONFIG = 0 // 原来的 $A,B,C
            const val TYPE_TASK = 1   // 新增的 $T,A
        }

        // 辅助方法：判断是否是循环模式
        fun isLoopTask(): Boolean {
            return type == TYPE_TASK && configA == 1
        }

        override fun toString(): String {
            val typeStr = if (type == TYPE_TASK) "Task(T)" else "Config"
            return "Block{$typeStr, A=$configA, B=$configB, C=$configC}"
        }
    }

    class DataItem(
        val raw: String?,        // 原始行，保留前导 '/'
        val block: Block?,       // 所属块（可能为 null，如果文件中有孤立行）
        val globalIndex: Int,    // 在全局 dataSet 中的位置（从0开始）
        val indexInBlock: Int    // 在所属块内的位置，从0开始
    ) {
        fun getText(): String? {
            // 返回要输入的字符串：去掉前导 '/'（如果需要）
            return raw
        }

        override fun toString(): String {
            return "DataItem{text='${getText()}', idx=$globalIndex, inBlock=$indexInBlock, block=${block?.toString() ?: "null"}}"
        }
    }

    private val dataSet: MutableList<DataItem> = ArrayList()
    private val blocks: MutableList<Block> = ArrayList()
    private var currentIndex: Int = 0

    /**
     * 解析新版文件格式
     *
     * 支持多个块：
     *  $A,B,C    // 头行（A:0/1/2 ; B:0/1 ; C:0/1）
     *  /...
     *  /...
     *  $
     *
     *  每个 $...$ 里面的 / 行将被加入全局序列，并且记录所属 block 与 block 内的索引。
     */
    @Throws(Exception::class)
    fun loadFromFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) throw Exception("文件不存在: $filePath")

        dataSet.clear()
        blocks.clear()
        currentIndex = 0

        file.bufferedReader().use { br ->
            var currentBlock: Block? = null
            var nextGlobalIndex = 0
            var indexInCurrentBlock = 0

            while (true) {
                // 1. 先获取行，并安全判空
                val rawLine = br.readLine() ?: break
                // 2. 移除 BOM 头后再进行 trim
                val line = rawLine.removePrefix("\uFEFF")
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("$")) {
                    val remainder = trimmed.substring(1).trim()

                    // --- 新增：处理 $T,数字A ---
                    if (remainder.startsWith("T,")) {
                        try {
                            val tValue = remainder.substring(2).trim().toInt()
                            val newBlock = Block(Block.TYPE_TASK, tValue, 0, 0, nextGlobalIndex)
                            currentBlock = newBlock
                            blocks.add(newBlock) // 避免使用 !!
                            indexInCurrentBlock = 0
                            continue
                        } catch (e: Exception) {
                            // 格式错误，回退到下面的原有逻辑
                        }
                    }

                    // --- 原有逻辑：处理 $A,B,C ---
                    val parts = remainder.split(",")
                    if (parts.size >= 3) {
                        try {
                            val a = parts[0].trim().toInt()
                            val b = parts[1].trim().toInt()
                            val c = parts[2].trim().toInt()
                            val newBlock = Block(Block.TYPE_CONFIG, a, b, c, nextGlobalIndex)
                            currentBlock = newBlock
                            blocks.add(newBlock) // 避免使用 !!
                            indexInCurrentBlock = 0
                            continue
                        } catch (_: NumberFormatException) {
                            currentBlock = null
                        }
                    } else if (trimmed == "$") { // 处理结束符号
                        if (currentBlock != null) {
                            currentBlock.endIndex = nextGlobalIndex - 1
                            currentBlock = null
                        }
                        continue
                    }
                }

                // 处理内容行
                if (trimmed.startsWith("/")) {
                    val item = DataItem(trimmed, currentBlock, nextGlobalIndex, indexInCurrentBlock)
                    dataSet.add(item)
                    nextGlobalIndex++
                    indexInCurrentBlock++

                    if (currentBlock != null) {
                        currentBlock.endIndex = nextGlobalIndex - 1
                    }
                }
            }
        }

        reset()
    }

    fun reset() {
        currentIndex = 0
    }
    
    fun resetAll() {
    dataSet.clear()
    blocks.clear()
    currentIndex = 0
}

    fun getCurrentItem(): DataItem? {
        if (dataSet.isEmpty()) return null
        return if (currentIndex < dataSet.size) dataSet[currentIndex] else null
    }

    fun getCurrentText(): String? {
        val it = getCurrentItem()
        return it?.getText()
    }

    fun getCurrentIndex(): Int = currentIndex

    fun getTotalSize(): Int = dataSet.size

    fun next() {
        if (currentIndex < dataSet.size) currentIndex++
    }

    fun back() {
        if (currentIndex > 0) currentIndex--
    }

    fun jumpTo(index: Int) {
        if (index in 0..dataSet.size) {
            currentIndex = index
        }
    }

    fun getBlocks(): List<Block> = blocks

    fun getAllItems(): List<DataItem> = dataSet
}
