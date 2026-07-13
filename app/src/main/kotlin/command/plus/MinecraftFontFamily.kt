package command.plus

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 1. 全局单例的 Minecraft 字体族
val MinecraftFontFamily = FontFamily(
    Font(R.font.minecraft, FontWeight.Normal),
    Font(R.font.minecraft, FontWeight.Bold) // 如果你有粗体版，没有的话都指向同一个文件即可
)

// 2. 自定义 M3 字形系统，将默认的所有文本样式都强制指定为像素字体
val MinecraftTypography = Typography(
    displayLarge = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    
    headlineLarge = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    
    titleLarge = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Bold),
    
    bodyLarge = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Normal),
    
    labelLarge = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = MinecraftFontFamily, fontWeight = FontWeight.Medium)
)