package command.plus

interface ScriptListener {
    fun onScriptEnd(scriptId: String, finalVars: Map<String, Any?>)
    fun onInputFieldNotFound(scriptId: String, attemptedText: String)
    fun onScriptStopped(scriptId: String)
    fun onScriptError(scriptId: String, e: Exception)
    /**
     * 当变量提供器返回 null（变量不存在或为 null）时回调
     */
    fun onVariableError(scriptId: String, varName: String)
    fun onNextItem()
    fun onFloatingToast(text: String)
}