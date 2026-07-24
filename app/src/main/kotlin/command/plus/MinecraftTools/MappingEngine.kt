package command.plus

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

// ============================================================
//  Converter data model
// ============================================================

// 新增/扩展通用方块中间体，支持含水层 extraId
data class GenericBlock(
    val id: String,
    val states: Map<String, Any?>,
    val extraId: String = "minecraft:air"
)

interface GenericStructure { 
    val width: Int
    val height: Int
    val depth: Int
    fun getBlockAt(x: Int, y: Int, z: Int): GenericBlock? 
}
data class TypePair(val input: Any?, val output: Any?)

data class TypeEntry(
    val name: String,
    val pairs: List<TypePair>,
    val forward: Map<String, List<Any?>>,
    val reverse: Map<String, List<Any?>>
)

data class GroupEntry(
    val name: String,
    val states: List<Map<String, Any?>>,
    val defaultInputs: Map<String, Any?>,
    val defaultOutputs: Map<String, Any?>,
    val combinedOutputs: List<Map<String, Any?>>,
    val combinedInputs: List<Map<String, Any?>>
)

data class BlockSideEntry(
    val identifier: String,
    val chunkerType: String,
    val groupRef: String?,
    val groupKey: String?,
    val extraStates: List<Map<String, Any?>>?
)

data class BlockEntry(
    val chunkerType: String,
    val java: MutableList<BlockSideEntry>,
    val bedrock: MutableList<BlockSideEntry>
)

// ============================================================
//  Utils
// ============================================================

object Utils {
    fun normalizeDeep(raw: Any?): Any? {
        return when (raw) {
            null -> null
            is Map<*, *> -> raw.mapKeys { normalizeDeep(it.key) }.mapValues { normalizeDeep(it.value) }
            is List<*> -> raw.map { normalizeDeep(it) }
            is Set<*> -> raw.map { normalizeDeep(it) }
            else -> normalizeValue(raw)
        }
    }

    fun valueKey(raw: Any?): String {
        return when (val v = normalizeDeep(raw)) {
            null -> "null"
            is List<*> -> "[" + v.joinToString(",") { valueKey(it) } + "]"
            is Map<*, *> -> v.entries.joinToString(",", prefix = "{", postfix = "}") { (k, vv) ->
                "${valueKey(k)}=${valueKey(vv)}"
            }
            is Boolean -> if (v) "true" else "false"
            is Number -> v.toString()
            else -> v.toString().lowercase(Locale.ROOT)
        }
    }

    fun deepEquals(a: Any?, b: Any?): Boolean = valueKey(a) == valueKey(b)

    fun normalizeId(raw: Any?): String? {
        if (raw == null) return null
        var s = raw.toString().trim()
        if (s.isEmpty()) return null
        if (s.startsWith("class ")) s = s.substring(6).trim()
        if (!s.contains(":")) s = "minecraft:$s"
        return s.lowercase(Locale.ROOT)
    }

    fun normalizeRef(raw: Any?): String? {
        if (raw == null) return null
        var s = raw.toString().trim()
        if (s.isEmpty()) return null
        if (s.startsWith("class ")) s = s.substring(6).trim()
        if (s.contains(".")) s = s.substringAfterLast(".").trim()
        if (s.startsWith("minecraft:", ignoreCase = true)) return s.lowercase(Locale.ROOT)
        if (s.startsWith("_") && s.substring(1).all { it.isDigit() }) return s.substring(1)
        return s
    }

    fun normalizeKey(raw: Any?): String? = normalizeRef(raw)?.lowercase(Locale.ROOT)

    fun normalizeValue(raw: Any?): Any? {
        if (raw == null) return null
        if (raw is Boolean)
    return raw

if (raw is Number) {
    val d = raw.toDouble()

    return if (d == d.toInt().toDouble()) {
        d.toInt()
    } else {
        d
    }
}

        var s = raw.toString().trim()
        if (s.isEmpty()) return ""

        if (s.startsWith("class ")) s = s.substring(6).trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)

        val low = s.lowercase(Locale.ROOT)
        if (low == "true") return true
        if (low == "false") return false
        if (low == "null") return null
        if (s.startsWith("_") && s.substring(1).all { it.isDigit() }) return s.substring(1).toIntOrNull() ?: s.substring(1)
        if (s.all { it.isDigit() }) return s.toIntOrNull() ?: s

        return low
    }

    fun valueToText(v: Any?): String {
        if (v == null) return "null"
        if (v is Boolean) return if (v) "true" else "false"
        if (v is Number) {
            val d = v.toDouble()
            return if (d == d.toInt().toDouble()) d.toInt().toString() else v.toString()
        }
        return v.toString().lowercase(Locale.ROOT)
    }

    fun splitTopLevelCommas(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inStr = false
        var inChar = false
        var escape = false

        for (ch in text) {
            if (escape) {
                current.append(ch)
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                current.append(ch)
                continue
            }
            if (ch == '"' && !inChar) {
                inStr = !inStr
                current.append(ch)
                continue
            }
            if (ch == '\'' && !inStr) {
                inChar = !inChar
                current.append(ch)
                continue
            }

            if (!inStr && !inChar) {
                if (ch in "([<{") depth++
                else if (ch in ")]>}") depth--
                else if (ch == ',' && depth == 0) {
                    val p = current.toString().trim()
                    if (p.isNotEmpty()) parts.add(p)
                    current.setLength(0)
                    continue
                }
            }

            current.append(ch)
        }

        val p = current.toString().trim()
        if (p.isNotEmpty()) parts.add(p)
        return parts
    }

    fun versionTuple(v: String?): Triple<Int, Int, Int> {
        if (v == null) return Triple(0, 0, 0)
        val s = v.trim()
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(s)
        if (m != null) return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())

        val parts = s.split(Regex("[^\\d]+")).filter { it.isNotEmpty() }
        val nums = parts.take(3).map { it.toInt() }.toMutableList()
        while (nums.size < 3) nums.add(0)
        return Triple(nums[0], nums[1], nums[2])
    }

    fun simpleName(ref: Any?): String? {
        if (ref == null) return null
        val s = ref.toString().trim()
        if (s.contains(".")) return s.substringAfterLast(".").trim()
        return s
    }

    fun formatBlockString(blockId: String, states: Map<String, Any?>): String {
        if (states.isEmpty()) return blockId
        val inner = states.entries.joinToString(",") { (k, v) ->
            "\"$k\"=${valueToText(v)}"
        }
        return "$blockId [$inner]"
    }

    fun parseBlockString(text: String): Pair<String, Map<String, Any?>> {
        val s = text.trim()
        val matcher = Regex("^\\s*(.*?)\\s*(?:\\[\\s*(.*?)\\s*\\])?\\s*$").find(s)
            ?: throw IllegalArgumentException("Invalid block string: $text")

        val blockId = normalizeId(matcher.groupValues[1])
            ?: throw IllegalArgumentException("Invalid block id: $text")

        val statesRaw = matcher.groupValues.getOrNull(2)
        val states = mutableMapOf<String, Any?>()

        if (!statesRaw.isNullOrBlank()) {
            for (item in splitTopLevelCommas(statesRaw)) {
                if (!item.contains("=")) continue
                val parts = item.split("=", limit = 2)
                val key = normalizeKey(parts[0])
                val value = normalizeValue(parts[1])
                if (key != null) states[key] = value
            }
        }

        return blockId to states
    }
}

operator fun Triple<Int, Int, Int>.compareTo(other: Triple<Int, Int, Int>): Int {
    if (this.first != other.first) return this.first.compareTo(other.first)
    if (this.second != other.second) return this.second.compareTo(other.second)
    return this.third.compareTo(other.third)
}

// ============================================================
//  Mapping database
// ============================================================

class MappingDatabase(private val raw: Map<String, Any?>) {
    private val blockMappings = (raw["block_mappings"] as? Map<*, *>) ?: emptyMap<Any, Any>()
    private val stateGroups = (raw["state_groups"] as? Map<*, *>) ?: emptyMap<Any, Any>()
    private val stateTypes = (raw["state_types"] as? Map<*, *>) ?: emptyMap<Any, Any>()

    private val typeIndexJava = buildTypeIndex("java")
    private val typeIndexBedrock = buildTypeIndex("bedrock")
    private val groupIndexJava = buildGroupIndex("java")
    private val groupIndexBedrock = buildGroupIndex("bedrock")
    private val blockIndex = buildBlockIndex()

    companion object {
        fun load(path: String): MappingDatabase {
            val jsonStr = File(path).readText(Charsets.UTF_8)
            // 替代写法，不需要依靠创建 TypeToken 匿名子类
val type: java.lang.reflect.Type = com.google.gson.internal.`$Gson$Types`.newParameterizedTypeWithOwner(
    null, 
    Map::class.java, 
    String::class.java, 
    Any::class.java
)
val parsed: Map<String, Any?> = Gson().fromJson(jsonStr, type)
            return MappingDatabase(parsed)
        }

        fun fromString(jsonContent: String): MappingDatabase {
    // 替代 TypeToken 的写法：动态构建 Map<String, Any?> 的 Type
    val type: java.lang.reflect.Type = com.google.gson.internal.`$Gson$Types`.newParameterizedTypeWithOwner(
        null, 
        Map::class.java, 
        String::class.java, 
        Any::class.java
    )
    val parsed: Map<String, Any?> = Gson().fromJson(jsonContent, type)
    return MappingDatabase(parsed)
}
    }
    
    private fun candidateConditions(extraStates: List<Map<String, Any?>>?): Map<String, Any?> {
    if (extraStates.isNullOrEmpty()) return emptyMap()

    val out = linkedMapOf<String, Any?>()
    val rawOnly = mutableListOf<Any?>()

    for (item in extraStates) {
        val key = Utils.normalizeRef(
            item["chunker_state"] ?: item["chunkerState"] ?: item["key"] ?: item["state"]
        )

        if (key != null && item.containsKey("value")) {
            out[key] = Utils.normalizeValue(item["value"])
        } else if (item.containsKey("raw")) {
            rawOnly.add(item["raw"])
        }
    }

    var i = 0
    while (i + 1 < rawOnly.size) {
        val key = Utils.normalizeRef(rawOnly[i]) ?: Utils.normalizeKey(rawOnly[i])
        val value = Utils.normalizeValue(rawOnly[i + 1])
        if (key != null) out[key] = value
        i += 2
    }

    return out
}
    
    private fun normalizeExtraStates(extraStatesRaw: List<*>?): List<Map<String, Any?>>? {
    if (extraStatesRaw == null) return null

    val out = mutableListOf<Map<String, Any?>>()
    var i = 0

    while (i < extraStatesRaw.size) {
        val cur = extraStatesRaw[i] as? Map<*, *>
        if (cur == null) {
            i++
            continue
        }

        val curChunker = cur["chunker_state"] ?: cur["chunkerState"] ?: cur["state"]
        val curValue = cur["value"]

        if (curChunker != null && curValue != null) {
            out.add(
                mapOf(
                    "chunker_state" to Utils.normalizeRef(curChunker),
                    "value" to Utils.normalizeValue(curValue)
                )
            )
            i++
            continue
        }

        val curRaw = cur["raw"]
        if (curRaw != null && i + 1 < extraStatesRaw.size) {
            val next = extraStatesRaw[i + 1] as? Map<*, *>
            val nextRaw = next?.get("raw")
            val nextStructured = next?.containsKey("chunker_state") == true ||
                    next?.containsKey("chunkerState") == true ||
                    next?.containsKey("value") == true

            if (next != null && nextRaw != null && !nextStructured) {
                out.add(
                    mapOf(
                        "chunker_state" to Utils.normalizeRef(curRaw),
                        "value" to Utils.normalizeValue(nextRaw)
                    )
                )
                i += 2
                continue
            }
        }

        if (curRaw != null) {
            out.add(mapOf("raw" to Utils.normalizeValue(curRaw)))
        }
        i++
    }

    return if (out.isEmpty()) null else out
}

    private fun buildTypeIndex(side: String): Map<String, TypeEntry> {
        val out = mutableMapOf<String, TypeEntry>()
        val src = (stateTypes[side] as? Map<*, *>) ?: return out

        for ((k, v) in src) {
            val keyStr = k.toString()
            val vMap = v as? Map<*, *> ?: continue
            val name = Utils.normalizeRef(vMap["name"]) ?: Utils.normalizeRef(keyStr) ?: keyStr

            val pairs = mutableListOf<TypePair>()
            val pairsRaw = vMap["pairs"] as? List<*> ?: emptyList<Any>()
            for (item in pairsRaw) {
                val pm = item as? Map<*, *> ?: continue
                val input = Utils.normalizeDeep(pm["input"])
                val output = Utils.normalizeDeep(pm["output"])
                pairs.add(TypePair(input, output))
            }

            val forward = mutableMapOf<String, MutableList<Any?>>()
            val reverse = mutableMapOf<String, MutableList<Any?>>()

            for (p in pairs) {
                val inKey = Utils.valueKey(p.input)
                val outKey = Utils.valueKey(p.output)
                forward.getOrPut(inKey) { mutableListOf() }.add(p.output)
                reverse.getOrPut(outKey) { mutableListOf() }.add(p.input)
            }

            if (pairs.isEmpty()) {
                val forwardRaw = (vMap["forward"] as? Map<*, *>) ?: emptyMap<Any, Any>()
                val reverseRaw = (vMap["reverse"] as? Map<*, *>) ?: emptyMap<Any, Any>()

                for ((a, b) in forwardRaw) {
                    val ka = Utils.normalizeKey(a) ?: continue
                    forward.getOrPut(ka) { mutableListOf() }.add(Utils.normalizeDeep(b))
                }
                for ((a, b) in reverseRaw) {
                    val ka = Utils.normalizeKey(a) ?: continue
                    reverse.getOrPut(ka) { mutableListOf() }.add(Utils.normalizeDeep(b))
                }
            }

            val entry = TypeEntry(
                name = name,
                pairs = pairs,
                forward = forward.mapValues { it.value.toList() },
                reverse = reverse.mapValues { it.value.toList() }
            )

            Utils.normalizeRef(keyStr)?.let { out[it] = entry }
            Utils.simpleName(keyStr)?.let { out[it] = entry }
            out[name] = entry
            out[name.lowercase(Locale.ROOT)] = entry
        }

        return out
    }

    private fun buildGroupIndex(side: String): Map<String, Map<String, Any?>> {
        val out = mutableMapOf<String, Map<String, Any?>>()
        val src = (stateGroups[side] as? Map<*, *>) ?: return out

        for ((k, v) in src) {
            val keyStr = k.toString()
            val vMap = v as? Map<String, Any?> ?: continue
            Utils.normalizeRef(keyStr)?.let { out[it] = vMap }
            Utils.simpleName(keyStr)?.let { out[it] = vMap }

            val name = vMap["name"]?.toString()
            if (name != null) {
                Utils.normalizeRef(name)?.let { out[it] = vMap }
                Utils.simpleName(name)?.let { out[it] = vMap }
            }
        }

        return out
    }

    private fun buildBlockIndex(): Map<String, BlockEntry> {
        val out = mutableMapOf<String, BlockEntry>()

        for ((ctype, bucket) in blockMappings) {
            val ctypeStr = ctype.toString()
            val bMap = bucket as? Map<*, *> ?: continue
            val javaList = (bMap["java"] as? List<*>) ?: emptyList<Any>()
            val bedrockList = (bMap["bedrock"] as? List<*>) ?: emptyList<Any>()
            val chunkerType = Utils.normalizeRef(ctypeStr) ?: ctypeStr
            val entry = BlockEntry(chunkerType, mutableListOf(), mutableListOf())

            for (item in javaList) {
                if (item is Map<*, *>) (entry.java as MutableList).add(parseBlockSide(item as Map<String, Any?>, ctypeStr))
            }
            for (item in bedrockList) {
                if (item is Map<*, *>) (entry.bedrock as MutableList).add(parseBlockSide(item as Map<String, Any?>, ctypeStr))
            }

            out[entry.chunkerType] = entry
        }

        return out
    }

    private fun parseBlockSide(item: Map<String, Any?>, ctype: String): BlockSideEntry {
    val extraStatesRaw = (item["extra_states"] ?: item["extraStates"]) as? List<*>
    val extraStates = mutableListOf<Map<String, Any?>>()
    val rawOnly = mutableListOf<Any?>()

    extraStatesRaw?.forEach { x ->
        if (x is Map<*, *>) {
            val xm = x as Map<String, Any?>
            val key = Utils.normalizeRef(xm["chunker_state"] ?: xm["chunkerState"] ?: xm["key"] ?: xm["state"])
            val value = if (xm.containsKey("value")) Utils.normalizeValue(xm["value"]) else null

            if (key != null && value != null) {
                extraStates.add(mapOf("chunker_state" to key, "value" to value))
            } else if (xm.containsKey("raw")) {
                rawOnly.add(xm["raw"])
            }
        }
    }

    var i = 0
    while (i + 1 < rawOnly.size) {
        val key = Utils.normalizeRef(rawOnly[i]) ?: Utils.normalizeKey(rawOnly[i])
        val value = Utils.normalizeValue(rawOnly[i + 1])
        if (key != null) {
            extraStates.add(mapOf("chunker_state" to key, "value" to value))
        }
        i += 2
    }

    return BlockSideEntry(
        identifier = Utils.normalizeId(item["identifier"] ?: item["id"] ?: "") ?: "",
        chunkerType = Utils.normalizeRef(item["chunker_type"] ?: item["chunkerType"] ?: ctype)
            ?: Utils.normalizeRef(ctype)
            ?: ctype,
        groupRef = Utils.normalizeRef(item["group_ref"] ?: item["group"] ?: item["stateGroup"] ?: item["state_group"]),
        groupKey = Utils.simpleName(item["group_key"] ?: item["group_ref"] ?: item["group"]),
        extraStates = if (extraStates.isEmpty()) null else extraStates
    )
}
private fun candidateMatchesCanonical(
    candidate: BlockSideEntry,
    canonical: Map<String, Any?>
): Boolean {
    val exList = candidate.extraStates ?: return true
    if (exList.isEmpty()) return true

    for (ex in exList) {
        val key = Utils.normalizeRef(ex["chunker_state"] ?: ex["chunkerState"] ?: ex["state"])
        val expected = if (ex.containsKey("value")) Utils.normalizeValue(ex["value"]) else null

        if (key != null && expected != null) {
            val actual = canonical[key] ?: return false
            if (!Utils.deepEquals(actual, expected)) return false
        }
    }

    return true
}

    fun resolveBlockSide(side: String, blockId: String): BlockSideEntry? {
        val normalized = Utils.normalizeId(blockId) ?: return null
        for (entry in blockIndex.values) {
            val list = if (side == "java") entry.java else entry.bedrock
            for (sideEntry in list) {
                if (sideEntry.identifier == normalized) return sideEntry
            }
        }
        return null
    }

    fun resolveGroup(side: String, refOrKey: String?, version: Triple<Int, Int, Int>): GroupEntry? {
        if (refOrKey == null) return null

        val index = if (side == "java") groupIndexJava else groupIndexBedrock
        val rawGroup = index[Utils.normalizeRef(refOrKey)] ?: index[Utils.simpleName(refOrKey)] ?: return null

        val isVersioned = rawGroup["is_versioned"] as? Boolean ?: false
        if (isVersioned) {
            val defaults = (rawGroup["defaults"] as? Map<String, Any?>) ?: emptyMap()
            val versions = (rawGroup["versions"] as? List<*>) ?: emptyList<Any>()

            var chosen: Map<String, Any?>? = null
            var chosenVer = Triple(-1, -1, -1)

            for (item in versions) {
                val itemMap = item as? Map<*, *> ?: continue
                val ver = Utils.versionTuple(itemMap["version"]?.toString())
                if (ver <= version && ver >= chosenVer) {
                    chosen = itemMap["group"] as? Map<String, Any?>
                    chosenVer = ver
                }
            }

            val chain = normalizeGroupChain(chosen ?: defaults)
            return groupEntryFromChain(refOrKey, chain)
        }

        val groupData = (rawGroup["group"] ?: rawGroup["data"] ?: rawGroup) as? Map<String, Any?> ?: emptyMap()
        return groupEntryFromChain(refOrKey, normalizeGroupChain(groupData))
    }

    private fun normalizeGroupChain(group: Map<String, Any?>): Map<String, Any?> {
        val states = mutableListOf<Map<String, Any?>>()
        val statesRaw = (group["states"] as? List<*>) ?: emptyList<Any>()

        for (item in statesRaw) {
            if (item is Map<*, *>) {
                val im = item as Map<*, *>
                states.add(
                    mapOf(
                        "key" to Utils.normalizeKey(im["key"] ?: im["javaKey"] ?: im["bedrockKey"]),
                        "chunker_state" to Utils.normalizeRef(
                            im["chunker_state"] ?: im["chunkerState"] ?: im["state"] ?: im["vanilla_state"] ?: im["vanillaState"]
                        ),
                        "type_mapper" to Utils.normalizeRef(im["type_mapper"] ?: im["typeMapper"] ?: im["type"])
                    )
                )
            }
        }

        val defaultInputs = mutableMapOf<String, Any?>()
        (group["default_inputs"] ?: group["defaultInputs"])?.let { raw ->
            val list = raw as? List<*>
            list?.forEach { item ->
                if (item is Map<*, *>) {
                    val k = Utils.normalizeKey(item["key"])
                    if (k != null) defaultInputs[k] = Utils.normalizeValue(item["value"])
                }
            }
        }

        val defaultOutputs = mutableMapOf<String, Any?>()
        (group["default_outputs"] ?: group["defaultOutputs"])?.let { raw ->
            val list = raw as? List<*>
            list?.forEach { item ->
                if (item is Map<*, *>) {
                    val st = Utils.normalizeRef(item["chunker_state"] ?: item["chunkerState"] ?: item["state"])
                    if (st != null) defaultOutputs[st] = Utils.normalizeValue(item["value"])
                }
            }
        }

        val combinedOutputs = mutableListOf<Map<String, Any?>>()
        (group["combined_outputs"] ?: group["combinedOutputs"])?.let { raw ->
            val list = raw as? List<*>
            list?.forEach { item ->
                if (item is Map<*, *>) {
                    val im = item as Map<*, *>
                    val statesList = ((im["states"] ?: im["inputs"]) as? List<*>)?.map { Utils.normalizeRef(it) } ?: emptyList<String?>()
                    combinedOutputs.add(
                        mapOf(
                            "key" to Utils.normalizeKey(im["key"] ?: im["output"] ?: im["name"]),
                            "states" to statesList,
                            "type_mapper" to Utils.normalizeRef(im["type_mapper"] ?: im["typeMapper"] ?: im["type"])
                        )
                    )
                }
            }
        }

        val combinedInputs = mutableListOf<Map<String, Any?>>()
        (group["combined_inputs"] ?: group["combinedInputs"])?.let { raw ->
            val list = raw as? List<*>
            list?.forEach { item ->
                if (item is Map<*, *>) {
                    val im = item as Map<*, *>
                    val rawArgs = (im["raw_args"] ?: im["rawArgs"]) as? List<*> ?: emptyList<Any>()
                    val normArgs = rawArgs.map {
                        if (it is String) Utils.normalizeRef(it) else Utils.normalizeValue(it)
                    }
                    combinedInputs.add(mapOf("raw_args" to normArgs))
                }
            }
        }

        return mapOf(
            "states" to states,
            "default_inputs" to defaultInputs,
            "default_outputs" to defaultOutputs,
            "combined_outputs" to combinedOutputs,
            "combined_inputs" to combinedInputs
        )
    }

    private fun groupEntryFromChain(name: String, chain: Map<String, Any?>): GroupEntry {
        return GroupEntry(
            name = Utils.simpleName(name) ?: Utils.normalizeRef(name) ?: name,
            states = (chain["states"] as? List<Map<String, Any?>>) ?: emptyList(),
            defaultInputs = (chain["default_inputs"] as? Map<String, Any?>) ?: emptyMap(),
            defaultOutputs = (chain["default_outputs"] as? Map<String, Any?>) ?: emptyMap(),
            combinedOutputs = (chain["combined_outputs"] as? List<Map<String, Any?>>) ?: emptyList(),
            combinedInputs = (chain["combined_inputs"] as? List<Map<String, Any?>>) ?: emptyList()
        )
    }

    fun typeEntry(side: String, typeRef: String?): TypeEntry? {
        if (typeRef == null) return null
        val idx = if (side == "java") typeIndexJava else typeIndexBedrock
        val nr = Utils.normalizeRef(typeRef) ?: return null
        return idx[nr] ?: idx[Utils.simpleName(typeRef)] ?: idx[nr.lowercase(Locale.ROOT)]
    }

    fun convertJavaToBedrock(
        blockId: String,
        states: Map<String, Any?>,
        version: Triple<Int, Int, Int>
    ): Pair<String, Map<String, Any?>>? {
        return convert("java", "bedrock", blockId, states, version)
    }

    fun convertBedrockToJava(
        blockId: String,
        states: Map<String, Any?>,
        version: Triple<Int, Int, Int>
    ): Pair<String, Map<String, Any?>>? {
        return convert("bedrock", "java", blockId, states, version)
    }

    private fun convert(
    sourceSide: String,
    targetSide: String,
    blockId: String,
    inputStates: Map<String, Any?>,
    version: Triple<Int, Int, Int>
): Pair<String, Map<String, Any?>>? {
    val srcBlock = resolveBlockSide(sourceSide, blockId) ?: return null
    val chunkerType = Utils.normalizeRef(srcBlock.chunkerType) ?: srcBlock.chunkerType
    val blockBucket = blockIndex[chunkerType] ?: return null
    val targetCandidates = if (targetSide == "bedrock") blockBucket.bedrock else blockBucket.java
    if (targetCandidates.isEmpty()) return null

    val srcGroup = resolveGroup(sourceSide, srcBlock.groupRef ?: srcBlock.groupKey, version)
    val canonical = if (srcGroup != null) {
        buildCanonicalMap(sourceSide, srcGroup, inputStates.toMutableMap())
    } else {
        emptyMap()
    }

    val targetBlock = pickTargetCandidate(srcBlock, targetCandidates, canonical) ?: return null

    val tgtGroup = resolveGroup(targetSide, targetBlock.groupRef ?: targetBlock.groupKey, version)
    val outStates = if (tgtGroup != null) {
        renderTarget(targetSide, tgtGroup, canonical)
    } else {
        inputStates
    }

    val finalId = Utils.normalizeId(targetBlock.identifier) ?: targetBlock.identifier
    return finalId to outStates
}

    private fun pickTargetCandidate(
    srcBlock: BlockSideEntry,
    candidates: List<BlockSideEntry>,
    canonical: Map<String, Any?>
): BlockSideEntry? {
    if (candidates.isEmpty()) return null

    val srcGroupKey = Utils.simpleName(srcBlock.groupRef ?: srcBlock.groupKey)

    fun matchesCandidate(c: BlockSideEntry): Boolean {
        val conds = candidateConditions(c.extraStates)
        if (conds.isEmpty()) return true

        for ((k, expected) in conds) {
            val actual =
                canonical[Utils.normalizeRef(k) ?: k]
                    ?: canonical[Utils.normalizeKey(k) ?: k]

            if (actual == null || !Utils.deepEquals(actual, expected)) {
                return false
            }
        }
        return true
    }

    val matched = candidates.filter(::matchesCandidate)
    val pool = if (matched.isNotEmpty()) {
        matched
    } else {
        candidates.filter { candidateConditions(it.extraStates).isEmpty() }
            .ifEmpty { candidates }
    }

    var best: BlockSideEntry? = null
    var bestScore = Int.MIN_VALUE

    for (c in pool) {
        var score = 0

        if (Utils.simpleName(c.groupRef ?: c.groupKey) == srcGroupKey) score += 20
        if (c.identifier == srcBlock.identifier) score += 10

        val condCount = candidateConditions(c.extraStates).size
        if (condCount > 0) score += condCount * 50

        if (score > bestScore) {
            bestScore = score
            best = c
        }
    }

    return best ?: pool.firstOrNull() ?: candidates.firstOrNull()
}
    private fun buildCanonicalMap(
        sourceSide: String,
        group: GroupEntry,
        inputStates: MutableMap<String, Any?>
    ): Map<String, Any?> {
        val canonical = mutableMapOf<String, Any?>()

        group.defaultInputs.forEach { (k, v) ->
            if (!inputStates.containsKey(k)) inputStates[k] = v
        }

        for (st in group.states) {
            val inputKey = st["key"]?.toString() ?: continue
            val chunkerState = Utils.normalizeRef(st["chunker_state"]) ?: continue
            val typeRef = st["type_mapper"]?.toString()

            if (inputStates.containsKey(inputKey)) {
                val rawVal = inputStates[inputKey]
                val tEntry = typeEntry(sourceSide, typeRef)
                canonical[chunkerState] = lookupType(tEntry, rawVal, forward = true)
            }
        }

        for (item in group.combinedInputs) {
            val rawArgs = (item["raw_args"] as? List<*>) ?: continue
            if (rawArgs.size < 4) continue

            val sourceKeys = rawArgs.subList(0, rawArgs.size - 2).map { Utils.normalizeKey(it) }
            val outState = Utils.normalizeRef(rawArgs[rawArgs.size - 2]) ?: continue
            val typeRef = Utils.normalizeRef(rawArgs.last())

            val values = mutableListOf<Any?>()
            var ok = true
            for (k in sourceKeys) {
                if (k == null || !inputStates.containsKey(k)) {
                    ok = false
                    break
                }
                values.add(inputStates[k])
            }
            if (!ok) continue

            val tEntry = typeEntry(sourceSide, typeRef)
            canonical[outState] = lookupType(tEntry, values, forward = true)
        }

        group.defaultOutputs.forEach { (k, v) ->
            val nk = Utils.normalizeRef(k)
            if (nk != null && !canonical.containsKey(nk)) canonical[nk] = Utils.normalizeValue(v)
        }

        return canonical
    }

    private fun renderTarget(
        targetSide: String,
        group: GroupEntry,
        canonical: Map<String, Any?>
    ): Map<String, Any?> {
        val outStates = mutableMapOf<String, Any?>()

        for (st in group.states) {
            val targetKey = st["key"]?.toString() ?: continue
            val chunkerState = Utils.normalizeRef(st["chunker_state"]) ?: continue
            val typeRef = st["type_mapper"]?.toString()

            if (canonical.containsKey(chunkerState)) {
                val tEntry = typeEntry(targetSide, typeRef)
                outStates[targetKey] = lookupType(tEntry, canonical[chunkerState], forward = false)
            }
        }

        for (item in group.combinedOutputs) {
            val targetKey = item["key"]?.toString() ?: continue
            val states = (item["states"] as? List<*>) ?: continue
            val typeRef = item["type_mapper"]?.toString()

            val values = mutableListOf<Any?>()
            var ok = true
            for (s in states) {
                val key = Utils.normalizeRef(s)
                if (key == null || !canonical.containsKey(key)) {
                    ok = false
                    break
                }
                values.add(canonical[key])
            }
            if (!ok) continue

            val tEntry = typeEntry(targetSide, typeRef)
            outStates[targetKey] = lookupType(tEntry, values, forward = false)
        }

        for ((k, v) in group.defaultInputs) {
            if (!outStates.containsKey(k)) outStates[k] = Utils.normalizeValue(v)
        }

        return outStates.filterValues { it != null }
    }

    private fun lookupType(typeEntry: TypeEntry?, value: Any?, forward: Boolean): Any? {
        if (typeEntry == null) return Utils.normalizeDeep(value)

        val needle = Utils.normalizeDeep(value)

        val pairList = if (forward) {
            typeEntry.pairs
        } else {
            typeEntry.pairs.map { TypePair(it.output, it.input) }
        }

        for (pair in pairList) {
        // 增加转为字符串后的比较，防止 Int(2) 和 String("2") 或 Double(2.0) 比对失败
        if (Utils.deepEquals(pair.input, needle) || 
            Utils.valueToText(pair.input) == Utils.valueToText(needle)) {
            return Utils.normalizeDeep(pair.output)
        }
    }

        val table = if (forward) typeEntry.forward else typeEntry.reverse
        val keys = when (needle) {
            is List<*> -> comboCandidates(needle)
            else -> valueCandidates(needle)
        }

        for (key in keys) {
            val hit = table[key]?.firstOrNull()
            if (hit != null) return Utils.normalizeDeep(hit)
        }

        return Utils.normalizeDeep(value)
    }

    private fun valueCandidates(value: Any?): List<String> {
        val v = Utils.normalizeValue(value) ?: return emptyList()
        val cands = mutableListOf<String>()

        when (v) {
            is Boolean -> {
                cands.add(if (v) "true" else "false")
                cands.add(if (v) "TRUE" else "FALSE")
            }
            is Number -> {
                val i = v.toInt()
                cands.add(i.toString())
                cands.add("_$i")
            }
            else -> {
                val s = v.toString().trim()
                cands.add(s)
                cands.add(s.lowercase(Locale.ROOT))
                cands.add(s.uppercase(Locale.ROOT))
                Utils.normalizeRef(s)?.let { cands.add(it) }
            }
        }

        return cands.distinct()
    }

    private fun comboCandidates(values: List<*>): List<String> {
        val norm = values.map { Utils.normalizeValue(it) }
        return listOf(
            norm.joinToString(prefix = "[", postfix = "]", separator = ",") { Utils.valueKey(it) },
            norm.joinToString(prefix = "[", postfix = "]", separator = ",") { Utils.valueToText(it) },
            norm.joinToString(separator = ",") { Utils.valueToText(it) }
        ).distinct()
    }
}
