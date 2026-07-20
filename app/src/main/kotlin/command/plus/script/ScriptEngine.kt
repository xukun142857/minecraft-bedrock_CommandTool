package command.plus

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.min

class ScriptEngine {
    private var plistener: ScriptListener? = null
    var variableProvider: VariableProvider? = null

    // ---------------- 内部接口 ----------------
    interface VariableProvider {
        /**
         * @param name 变量名（不带 $）
         * @return 值（可为 Boolean/String/Number 等），如果不存在请返回 null
         */
        fun getVariable(name: String): Any?
    }

    interface ActionExecutor {
        fun exeAction(x: Int, y: Int, x1: Int, y1: Int, timeMs: Long)
    }

    interface TextSetter {
        /**
         * @return true 成功，false 未找到或失败
         */
        fun setText(text: String): Boolean
    }

    interface Command {
        @Throws(RestartException::class, StopException::class, VariableMissingException::class)
        fun execute(ctx: ExecutionContext, rawArgs: String)
    }

    // ---------------- 异常类 ----------------
    class RestartException(val keepVars: List<String> = emptyList()) : Exception("restart")
    class StopException : Exception("stop")
    class VariableMissingException(varName: String) : Exception("variable missing: $varName")

    // ---------------- ExecutionContext ----------------
    class ExecutionContext(
        val vars: MutableMap<String, Any?>,
        val actionExecutor: ActionExecutor?,
        val textSetter: TextSetter?,
        val listener: ScriptListener?,
        val scriptId: String,
        private val variableProvider: VariableProvider?
    ) {
        private val stopped = AtomicBoolean(false)

        val isStopped: Boolean get() = stopped.get()
        fun stop() { stopped.set(true) }

        @Throws(VariableMissingException::class)
        fun resolveVar(name: String?): Any {
            if (name == null) throw VariableMissingException("null")
            val cleanName = name.removePrefix("$")
            
            if (vars.containsKey(cleanName)) {
                val v = vars[cleanName]
                if (v == null) {
                    listener?.onVariableError(scriptId, cleanName)
                    throw VariableMissingException(cleanName)
                }
                return v
            }
            if (variableProvider != null) {
                val v = variableProvider.getVariable(cleanName)
                if (v == null) {
                    listener?.onVariableError(scriptId, cleanName)
                    throw VariableMissingException(cleanName)
                }
                return v
            }
            listener?.onVariableError(scriptId, cleanName)
            throw VariableMissingException(cleanName)
        }
    }

    // ---------------- 变量 & 正则 ----------------
    private val commandRegistry = HashMap<String, Command>()
    private val commandNamePattern = Pattern.compile("^\\.([a-zA-Z0-9_]+)(\\(|\\s|$)")
    private val parenArgsPattern = Pattern.compile("^\\.([a-zA-Z0-9_]+)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL)

    init {
        registerBuiltinCommands()
    }

    fun registerCommand(name: String, cmd: Command) {
        commandRegistry[name.lowercase()] = cmd
    }

    // ---------------- 内置命令 ----------------
    private fun registerBuiltinCommands() {
        // exeAction(x,y,x1,y1,time)
        registerCommand("exeAction", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val parts = splitArgs(rawArgs)
                if (parts.size < 5) return
                try {
                    val x = evalInt(parts[0], ctx)
                    val y = evalInt(parts[1], ctx)
                    val x1 = evalInt(parts[2], ctx)
                    val y1 = evalInt(parts[3], ctx)
                    val time = evalLong(parts[4], ctx)
                    
                    ctx.actionExecutor?.exeAction(x, y, x1, y1, time)
                } catch (ignore: Exception) {}
            }
        })

        // sleep(ms)
        registerCommand("sleep", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val s = rawArgs.trim()
                if (s.isEmpty()) return
                try {
                    val ms = evalLong(s, ctx)
                    
                    var waited: Long = 0
                    val chunk: Long = 100
                    while (waited < ms) {
                        if (ctx.isStopped) throw StopException()
                        val to = min(chunk, ms - waited)
                        try { Thread.sleep(to) } 
                        catch (ie: InterruptedException) { 
                            Thread.currentThread().interrupt()
                            throw StopException() 
                        }
                        waited += to
                    }
                } catch (ignore: Exception) {}
            }
        })

        // setText(expr)
        registerCommand("setText", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val text = ConcatExprEvaluator.evaluate(rawArgs, ctx)
                if (ctx.isStopped) throw StopException()
                var ok = false
                try {
                    ok = ctx.textSetter?.setText(text) ?: false
                } catch (e: Exception) {
                    ctx.listener?.onScriptError(ctx.scriptId, e)
                }
                if (!ok) {
                    ctx.listener?.onInputFieldNotFound(ctx.scriptId, text)
                    ctx.stop() 
                    throw StopException() 
                }
            }
        })

        // setVar(name, expr)
        registerCommand("setVar", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val parts = splitArgs(rawArgs)
                if (parts.size < 2) return
                val name = parts[0].trim().removePrefix("$")
                val comma = rawArgs.indexOf(',')
                if (comma < 0) return
                val expr = rawArgs.substring(comma + 1)
                val strVal = ConcatExprEvaluator.evaluate(expr, ctx)
                
                val low = strVal.trim().lowercase()
                val finalVal: Any = when (low) {
                    "true" -> true
                    "false" -> false
                    else -> strVal
                }
                
                if (!ctx.vars.containsKey(name) || ctx.vars[name] == null) {
                    ctx.vars[name] = finalVal
                }
            }
        })

        // addVar(name, num)
        registerCommand("addVar", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val parts = splitArgs(rawArgs)
                if (parts.size < 2) return
                val name = parts[0].trim().removePrefix("$")
                val comma = rawArgs.indexOf(',')
                if (comma < 0) return
                val expr = rawArgs.substring(comma + 1)
                
                val addStr = ConcatExprEvaluator.evaluate(expr, ctx)
                val numToAdd = addStr.trim().toDoubleOrNull() ?: 0.0
                
                val currentVal = try {
                    ctx.resolveVar(name)
                } catch (e: VariableMissingException) {
                    0.0
                }
                val currentNum = currentVal.toString().toDoubleOrNull() ?: 0.0
                
                val result = currentNum + numToAdd
                ctx.vars[name] = if (result % 1.0 == 0.0) result.toLong() else result
            }
        })

        // reStart(var1, var2...)
        registerCommand("reStart", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val keepVars = splitArgs(rawArgs).map { it.trim().removePrefix("$") }.filter { it.isNotEmpty() }
                throw RestartException(keepVars)
            }
        })

        // nextItem
        registerCommand("nextItem", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                plistener?.onNextItem()
            }
        })
        
        registerCommand("fToast", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val text = ConcatExprEvaluator.evaluate(rawArgs, ctx)
                plistener?.onFloatingToast(if(text == null) "" else text)
            }
        })
        
        registerCommand("adbCmd", object : Command {
            override fun execute(ctx: ExecutionContext, rawArgs: String) {
                val text = ConcatExprEvaluator.evaluate(rawArgs, ctx)
                ShizukuManager.runWhenReady(if(text == null) "" else text)
            }
        })
    }

    // ---------------- 脚本执行控制 ----------------
    class ScriptHandle(val scriptId: String, val thread: Thread, val ctx: ExecutionContext) {
        fun stop() {
            ctx.stop()
            thread.interrupt()
        }
    }

    fun runScriptAsync(
        scriptId: String?,
        lines: List<String>?,
        initialVars: Map<String, Any>?,
        actionExecutor: ActionExecutor?,
        textSetter: TextSetter?,
        listener: ScriptListener?
    ): ScriptHandle {
        plistener = listener
        val id = if (scriptId.isNullOrEmpty()) UUID.randomUUID().toString() else scriptId
        val vars: MutableMap<String, Any?> =
            initialVars?.mapValues { it.value }?.toMutableMap() ?: mutableMapOf()
        val ctx = ExecutionContext(vars, actionExecutor, textSetter, listener, id, variableProvider)

        val task = Runnable {
            runningContexts[id] = ctx
            runningThreads[id] = Thread.currentThread()
            try {
                // 在传入核心引擎前，先将所有行中的行尾注释去除
                val cleanLines = lines?.map { removeComments(it) }
                runScriptInternal(cleanLines, ctx)
                if (!ctx.isStopped) {
                    listener?.onScriptEnd(id, HashMap(ctx.vars))
                } else {
                    listener?.onScriptStopped(id)
                }
            } catch (vme: VariableMissingException) {
                listener?.onScriptStopped(id)
            } catch (se: StopException) {
                listener?.onScriptStopped(id)
            } catch (re: RestartException) {
                listener?.onScriptEnd(id, HashMap(ctx.vars))
            } catch (e: Exception) {
                listener?.onScriptError(id, e)
            } finally {
                runningContexts.remove(id)
                runningThreads.remove(id)
            }
        }

        val th = Thread(task, "ScriptEngine-$id")
        th.start()
        return ScriptHandle(id, th, ctx)
    }

    // ---------------- 核心执行引擎 ----------------
    @Throws(RestartException::class, StopException::class, VariableMissingException::class)
    private fun runScriptInternal(lines: List<String>?, ctx: ExecutionContext) {
        if (lines == null) return
        val ifMap = buildIfMap(lines)

        var iterationGuard = 0
        outer@ while (true) {
            iterationGuard++
            if (iterationGuard > 1000000) break
            val frameStack = ArrayDeque<IfFrame>()
            var i = 0
            
            while (i < lines.size) {
                if (ctx.isStopped) throw StopException()
                val raw = lines[i]
                if (raw == null) {
                    i++
                    continue
                }
                
                val line = raw.trim()
                if (!line.startsWith(".")) { 
                    i++ 
                    continue 
                }

                val cmdName = parseCommandName(line)
                if (cmdName == null) {
                    i++
                    continue
                }
                val cmdLower = cmdName.lowercase()

                val executionEnabled = if (frameStack.isEmpty()) true else frameStack.peek().executing

                if (cmdLower == "if" || line.startsWith(".if(")) {
                    if (!ifMap.ifToEnd.containsKey(i)) { i++; continue }
                    val cond = extractParenContent(line)
                    var condValue = false
                    try {
                        val parser = BoolExprParser(cond, ctx)
                        condValue = parser.parseExpr()
                    } catch (vme: VariableMissingException) {
                        throw vme
                    } catch (e: Exception) {
                        condValue = false
                    }
                    val newExec = executionEnabled && condValue
                    frameStack.push(IfFrame(newExec, executionEnabled, condValue))
                    i++
                    continue
                } else if (cmdLower == "else" || line.startsWith(".else")) {
                    if (!ifMap.elseToIf.containsKey(i)) { i++; continue }
                    if (frameStack.isNotEmpty()) {
                        val top = frameStack.peek()
                        top.executing = top.parentActive && (!top.conditionResult)
                    }
                    i++
                    continue
                } else if (cmdLower == "end" || line.startsWith(".end")) {
                    if (frameStack.isNotEmpty()) frameStack.pop()
                    i++
                    continue
                } else {
                    if (!executionEnabled) { i++; continue }

                    var args = ""
                    val m = parenArgsPattern.matcher(line)
                    if (m.matches()) {
                        args = m.group(2).trim()
                    } else {
                        val spaceIdx = line.indexOf(' ')
                        if (spaceIdx >= 0) args = line.substring(spaceIdx + 1).trim()
                    }

                    val cmd = commandRegistry[cmdLower]
                    if (cmd != null) {
                        try {
                            cmd.execute(ctx, args)
                        } catch (vme: VariableMissingException) {
                            throw vme
                        } catch (re: RestartException) {
                            val preserved = mutableMapOf<String, Any?>()
                            for (varName in re.keepVars) {
                                try {
                                    preserved[varName] = ctx.resolveVar(varName)
                                } catch (e: VariableMissingException) {
                                    // ignore
                                }
                            }
                            ctx.vars.clear()
                            ctx.vars.putAll(preserved)
                            i = 0
                            continue@outer
                        } catch (se: StopException) {
                            throw se
                        } catch (ex: Exception) {
                            ctx.listener?.onScriptError(ctx.scriptId, ex)
                        }
                        if (ctx.isStopped) throw StopException()
                    } else {
                        if (cmdLower == "exeaction") {
                            val parts = splitArgs(args)
                            if (parts.size >= 6) {
                                try {
                                    val x = parts[0].toInt()
                                    val y = parts[1].toInt()
                                    val x1 = parts[2].toInt()
                                    val y1 = parts[3].toInt()
                                    val time = parts[4].toLong()
                                    val delay = parts[5].toLong()
                                    commandRegistry["exeaction"]?.execute(ctx, "$x,$y,$x1,$y1,$time")
                                    commandRegistry["sleep"]?.execute(ctx, delay.toString())
                                } catch (nfe: NumberFormatException) {}
                            }
                        }
                    }
                    i++
                }
            } // inner while
            break
        } // outer while
    }

    // ---------------- 辅助类与解析器 ----------------
    class IfMap {
        val ifToEnd = HashMap<Int, Int>()
        val ifToElse = HashMap<Int, Int>()
        val elseToIf = HashMap<Int, Int>()
    }

    class IfFrame(var executing: Boolean, val parentActive: Boolean, val conditionResult: Boolean)

    private fun buildIfMap(lines: List<String>): IfMap {
        val m = IfMap()
        val stack = ArrayDeque<Int>()
        for (i in lines.indices) {
            val raw = lines[i].trim()
            if (!raw.startsWith(".")) continue
            var cmd = parseCommandNameStatic(raw) ?: continue
            cmd = cmd.lowercase()
            
            if (cmd == "if" || cmd.startsWith("if(")) {
                stack.push(i)
            } else if (cmd == "else" || cmd.startsWith("else(")) {
                if (stack.isNotEmpty()) {
                    val ifIdx = stack.peek()
                    m.ifToElse[ifIdx] = i
                    m.elseToIf[i] = ifIdx
                }
            } else if (cmd == "end" || cmd.startsWith("end(")) {
                if (stack.isNotEmpty()) {
                    val ifIdx = stack.pop()
                    m.ifToEnd[ifIdx] = i
                }
            }
        }
        val clean = HashMap<Int, Int>()
        for ((ifIdx, elseIdx) in m.ifToElse) {
            if (m.ifToEnd.containsKey(ifIdx)) {
                clean[ifIdx] = elseIdx
                m.elseToIf[elseIdx] = ifIdx
            }
        }
        m.ifToElse.clear()
        m.ifToElse.putAll(clean)
        return m
    }

    private fun parseCommandName(rawLine: String): String? {
        val m = commandNamePattern.matcher(rawLine)
        return if (m.find()) m.group(1) else null
    }

    // ---------------- BoolExprParser ----------------
    private class BoolExprParser(expr: String, private val ctx: ExecutionContext) {
        enum class TokType {
            AND, OR, LPAREN, RPAREN, IDENT, TRUE, FALSE,
            EQ, NE, LT, GT, LTE, GTE, NUMBER, STRING, END
        }

        class Token(val type: TokType, val text: String)

        private val tokens: List<Token> = tokenize(expr)
        private var pos = 0

        private fun tokenize(s: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c == '(') { out.add(Token(TokType.LPAREN, "(")); i++; continue }
                if (c == ')') { out.add(Token(TokType.RPAREN, ")")); i++; continue }

                if (s.startsWith("&&", i)) { out.add(Token(TokType.AND, "&&")); i += 2; continue }
                if (s.startsWith("||", i)) { out.add(Token(TokType.OR, "||")); i += 2; continue }
                if (s.startsWith("==", i)) { out.add(Token(TokType.EQ, "==")); i += 2; continue }
                if (s.startsWith("!=", i)) { out.add(Token(TokType.NE, "!=")); i += 2; continue }
                if (s.startsWith("<=", i)) { out.add(Token(TokType.LTE, "<=")); i += 2; continue }
                if (s.startsWith(">=", i)) { out.add(Token(TokType.GTE, ">=")); i += 2; continue }
                if (c == '<') { out.add(Token(TokType.LT, "<")); i++; continue }
                if (c == '>') { out.add(Token(TokType.GT, ">")); i++; continue }

                if (c == '"') {
                    var j = i + 1
                    val sb = java.lang.StringBuilder()
                    while (j < s.length && s[j] != '"') {
                        if (s[j] == '\\' && j + 1 < s.length) {
                            sb.append(s[j + 1]); j += 2
                        } else {
                            sb.append(s[j]); j++
                        }
                    }
                    out.add(Token(TokType.STRING, sb.toString()))
                    i = j + 1
                    continue
                }

                if (c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit())) {
                    var j = i + 1
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out.add(Token(TokType.NUMBER, s.substring(i, j)))
                    i = j
                    continue
                }

                if (c == '$' || c.isLetter()) {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '$')) j++
                    val word = s.substring(i, j)
                    when (word.lowercase()) {
                        "true" -> out.add(Token(TokType.TRUE, "true"))
                        "false" -> out.add(Token(TokType.FALSE, "false"))
                        else -> out.add(Token(TokType.IDENT, word))
                    }
                    i = j
                    continue
                }
                i++
            }
            out.add(Token(TokType.END, ""))
            return out
        }

        private fun peek(): Token = tokens[pos]
        private fun consume(): Token = tokens[pos++]

        @Throws(VariableMissingException::class)
        fun parseExpr(): Boolean = parseOr()

        @Throws(VariableMissingException::class)
        private fun parseOr(): Boolean {
            var v = parseAnd()
            while (peek().type == TokType.OR) { consume(); v = v || parseAnd() }
            return v
        }

        @Throws(VariableMissingException::class)
        private fun parseAnd(): Boolean {
            var v = parseComparison()
            while (peek().type == TokType.AND) { consume(); v = v && parseComparison() }
            return v
        }

        @Throws(VariableMissingException::class)
        private fun parseComparison(): Boolean {
            val left = evaluateValue()
            val op = peek().type
            if (op in listOf(TokType.EQ, TokType.NE, TokType.LT, TokType.GT, TokType.LTE, TokType.GTE)) {
                consume()
                val right = evaluateValue()
                return compare(left, right, op)
            }
            return toBoolean(left)
        }

        @Throws(VariableMissingException::class)
        private fun evaluateValue(): Any? {
            val t = peek()
            if (t.type == TokType.LPAREN) {
                consume()
                val res = parseExpr()
                if (peek().type == TokType.RPAREN) consume()
                return res
            }
            consume()
            return when (t.type) {
                TokType.TRUE -> true
                TokType.FALSE -> false
                TokType.NUMBER -> t.text.toDouble()
                TokType.STRING -> t.text
                TokType.IDENT -> {
                    val key = if (t.text.startsWith("$")) t.text.substring(1) else t.text
                    ctx.resolveVar(key)
                }
                else -> null
            }
        }

        private fun compare(left: Any?, right: Any?, op: TokType): Boolean {
            if (left == null || right == null) {
                return when (op) {
                    TokType.EQ -> left == right
                    TokType.NE -> left != right
                    else -> false
                }
            }

            if (left is Number || right is Number) {
                val l = left.toString().toDoubleOrNull() ?: 0.0
                val r = right.toString().toDoubleOrNull() ?: 0.0
                return when (op) {
                    TokType.EQ -> l == r
                    TokType.NE -> l != r
                    TokType.LT -> l < r
                    TokType.GT -> l > r
                    TokType.LTE -> l <= r
                    TokType.GTE -> l >= r
                    else -> false
                }
            }

            val s1 = left.toString()
            val s2 = right.toString()
            val cmp = s1.compareTo(s2)
            return when (op) {
                TokType.EQ -> s1 == s2
                TokType.NE -> s1 != s2
                TokType.LT -> cmp < 0
                TokType.GT -> cmp > 0
                TokType.LTE -> cmp <= 0
                TokType.GTE -> cmp >= 0
                else -> false
            }
        }

        private fun toBoolean(v: Any?): Boolean {
            if (v is Boolean) return v
            if (v is String) return v.equals("true", ignoreCase = true)
            if (v is Number) return v.toDouble() != 0.0
            return v != null
        }
    }

    // ---------------- ConcatExprEvaluator ----------------
    private object ConcatExprEvaluator {
        private fun formatResult(value: Any?): String {
            if (value is Double) {
                return if (value % 1.0 == 0.0) {
                    value.toLong().toString()
                } else {
                    "%.2f".format(java.util.Locale.US, value)
                }
            }
            return value?.toString() ?: ""
        }

        @Throws(VariableMissingException::class)
        fun evaluate(expr: String?, ctx: ExecutionContext): String {
            if (expr == null || expr.isBlank()) return ""
            
            val trimmed = expr.trim()
            val isMathProbable = !trimmed.contains("\"") && 
                (trimmed.contains(Regex("[\\+\\-\\*/\\(\\)]")) || trimmed.toDoubleOrNull() != null)

            if (isMathProbable) {
                try {
                    val result = ArithmeticParser(trimmed, ctx).parse()
                    return formatResult(result)
                } catch (e: Exception) {
                    // Fallback to string concatenation
                }
            }

            val parts = ArrayList<String>()
            var i = 0
            while (i < expr.length) {
                val c = expr[i]
                if (c.isWhitespace() || c == '+') { i++; continue }
                if (c == '"') {
                    val sb = java.lang.StringBuilder()
                    i++
                    while (i < expr.length) {
                        val cc = expr[i]
                        if (cc == '\\' && i + 1 < expr.length) {
                            sb.append(expr[i+1]); i += 2
                        } else if (cc == '"') {
                            i++; break
                        } else {
                            sb.append(cc); i++
                        }
                    }
                    parts.add(sb.toString())
                } else if (c == '$' || c.isLetter()) {
                    var j = i
                    while (j < expr.length && (expr[j].isLetterOrDigit() || expr[j] == '_' || expr[j] == '$')) j++
                    val id = expr.substring(i, j)
                    val key = if (id.startsWith("$")) id.substring(1) else id
                    val vv = ctx.resolveVar(key)
                    parts.add(formatResult(vv))
                    i = j
                } else {
                    var j = i
                    while (j < expr.length && expr[j] != '+') j++
                    val lit = expr.substring(i, j).trim()
                    if (lit.isNotEmpty()) parts.add(lit)
                    i = j
                }
            }
            return parts.joinToString("")
        }
    }

    private class ArithmeticParser(private val expr: String, private val ctx: ExecutionContext) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < expr.length) expr[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < expr.length) throw Exception("Unexpected: " + ch.toChar())
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                if (eat('+'.code)) x += parseTerm()
                else if (eat('-'.code)) x -= parseTerm()
                else return x
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                if (eat('*'.code)) x *= parseFactor()
                else if (eat('/'.code)) {
                    val next = parseFactor()
                    if (next == 0.0) throw ArithmeticException("Division by zero")
                    x /= next
                } else return x
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                eat(')'.code)
            } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
                while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
                x = expr.substring(startPos, pos).toDouble()
            } else if (ch == '$'.code || (ch.toChar().isLetter())) {
                while (ch == '$'.code || ch.toChar().isLetterOrDigit() || ch == '_'.code) nextChar()
                val name = expr.substring(startPos, pos).removePrefix("$")
                val value = ctx.resolveVar(name)
                x = value.toString().toDoubleOrNull() ?: 0.0
            } else {
                throw Exception("Unexpected: " + ch.toChar())
            }
            return x
        }
    }

    // ---------------- 全局伴生对象 ----------------
    companion object {
        private val runningThreads = ConcurrentHashMap<String, Thread>()
        private val runningContexts = ConcurrentHashMap<String, ExecutionContext>()

        fun stopAllScripts() {
            runningContexts.values.forEach { it.stop() }
            runningThreads.values.forEach { if (it.isAlive) it.interrupt() }
        }

        /**
         * 🔴 新增辅助方法：用于在解析前安全剔除行尾的 // 注释
         * 注意防范字符串字面量里的 "//" （如 .setText("http://baidu.com")）
         */
        private fun removeComments(line: String): String {
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (c == '/' && !inQuotes && i + 1 < line.length && line[i + 1] == '/') {
                    return line.substring(0, i) // 发现非字符串内的 //，直接截断
                }
                i++
            }
            return line
        }

        private fun splitArgs(rawArgs: String?): Array<String> {
            if (rawArgs == null) return emptyArray()
            val out = ArrayList<String>()
            val cur = java.lang.StringBuilder()
            var inQuotes = false
            for (i in rawArgs.indices) {
                val c = rawArgs[i]
                if (c == '"') {
                    inQuotes = !inQuotes
                    cur.append(c)
                } else if (c == ',' && !inQuotes) {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                } else {
                    cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString().trim())
            return out.toTypedArray()
        }

        private fun parseCommandNameStatic(rawLine: String): String? {
            val m = Pattern.compile("^\\.([a-zA-Z0-9_]+)(\\(|\\s|$)").matcher(rawLine)
            return if (m.find()) m.group(1) else null
        }

        private fun extractParenContent(line: String): String {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            return if (open >= 0 && close > open) line.substring(open + 1, close).trim() else ""
        }
    }
    
    private fun evalDouble(expr: String, ctx: ExecutionContext): Double {
        return try {
            val resultStr = ConcatExprEvaluator.evaluate(expr, ctx)
            resultStr.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun evalLong(expr: String, ctx: ExecutionContext): Long = evalDouble(expr, ctx).toLong()
    private fun evalInt(expr: String, ctx: ExecutionContext): Int = evalDouble(expr, ctx).toInt()
}