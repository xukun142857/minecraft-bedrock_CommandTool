package command.plus

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import android.app.Application
import android.net.Uri
import android.content.Context
import android.content.SharedPreferences
data class ConfigInfo(
    val prefsName: String,
    val displayName: String
)

class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    
    // 创建一个事件流，用于通知 UI 层
    private val _importResultEvent = MutableSharedFlow<String>()
    val importResultEvent = _importResultEvent.asSharedFlow()

    val configFiles = listOf(
        ConfigInfo("music_prefs", "指令音符盒配置"),
        ConfigInfo("ImageConvertPrefs", "图片转换配置"),
        ConfigInfo("StructureGeneratorPrefs", "结构生成器配置"),
        ConfigInfo("McStructure3DCommandPrefs", "结构转命令配置")
    )

    fun exportConfig(uri: Uri, fileName: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val sourceFile = File(
                context.applicationInfo.dataDir,
                "shared_prefs/$fileName.xml"
            )

            context.contentResolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

    fun importConfig(uri: Uri, targetName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 清除旧缓存
                val sp = context.getSharedPreferences(targetName, android.content.Context.MODE_PRIVATE)
                sp.edit().clear().commit()

                val targetFile = File(context.applicationInfo.dataDir, "shared_prefs/$targetName.xml")
                targetFile.parentFile?.mkdirs()

                // 2. 复制物理文件
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 3. 成功后，向 UI 层发送成功消息
                _importResultEvent.emit("导入成功！请重启应用以应用新配置")

            } catch (e: Exception) {
                e.printStackTrace()
                // 失败时发送错误消息
                _importResultEvent.emit("导入失败: ${e.localizedMessage}")
            }
        }
    }
}

