package command.plus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // 引入边缘到边缘支持
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.round
import androidx.compose.runtime.snapshots.SnapshotStateList
import android.util.Log
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import java.io.File
import java.io.IOException
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image // 👈 显式导入这个组件

import android.content.ClipData
import android.content.ClipboardManager
// ---------- Minecraft 风格配色方案 ----------
object McColors {
    val BackgroundDark = Color(0xFF1E1E1E) // 类似黑曜石/暗色背景
    val ButtonGray = Color(0xFF8B8B8B)     // 经典主菜单按钮灰
    val ButtonLightBorder = Color(0xFFFFFFA0) // 按钮高亮黄（悬停/选中）
    val ButtonDarkBorder = Color(0xFF555555)  // 按钮阴影边框
    val SlotBackground = Color(0xFF8F8F8F)    // 物品栏格子底色
    val TextShadow = Color(0xFF3F3F3F)        // 文本像素阴影
    val TextGreen = Color(0xFF55FF55)         // 绿宝石/在线绿色
    val TextRed = Color(0xFFFF5555)           // 红石/警告红
}

data class FeatureItem(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val route: String
)

data class BannerItem(
    val id: Int,
    val title: String,
    val subtitle: String,
    val titleColor: Color = Color.Yellow,
    val subtitleColor: Color = Color.White,
    val imageResId: Int? = null,      
    val backgroundColor: Color = Color(0xFF4F4F4F) // 如果没有图片，使用的兜底色
)

private const val TAG = "AssetUtils"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {  
        // 1. 开启全面屏沉浸式状态栏支持
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)  
        
        
        val noteDir = File(getExternalFilesDir(null), "note")  
        val scriptDir = File(getExternalFilesDir(null), "script")  
        val pngDir = File(getExternalFilesDir(null), "blockpng")  

        copyAssetsFolder(this, "note", noteDir)  
        if (!scriptDir.exists()) copyAssetsFolder(this, "script", scriptDir)  
        if (!pngDir.exists()) copyAssetsFolder(this, "blockpng", pngDir)  

        setContent {  
            // 2. 动态统一系统状态栏和导航栏的颜色（全面屏组件支持）
            val view = androidx.compose.ui.platform.LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    // 设置系统状态栏底色为 MC 暗色背景
                    window.statusBarColor = McColors.BackgroundDark.toArgb()
                    window.navigationBarColor = McColors.BackgroundDark.toArgb()
                    
                    // 让状态栏文字/图标变成浅色（因为背景是暗色）
                    val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    windowInsetsController.isAppearanceLightStatusBars = false
                }
            }

            MaterialTheme(
                typography = MinecraftTypography
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = McColors.BackgroundDark
                ) {  
                    val navController = rememberNavController()  

                    NavHost(  
                        navController = navController,  
                        startDestination = "featureSelection"  
                    ) {  
                        composable("featureSelection") {  
                            val defaultFeatures = listOf(  
                                FeatureItem("1", "【自动操作】", Icons.Default.Person, McColors.TextGreen, "scriptCommand"),  
                                FeatureItem("2", "【音符盒方块】", Icons.Default.Analytics, Color(0xFFFFCC00), "musicCommand"),  
                                FeatureItem("3", "【地图画转换】", Icons.Default.Analytics, Color(0xFF00AAAA), "ImageTo"),  
                                FeatureItem("4", "【结构生成器】", Icons.Default.Star, Color(0xFFAA00AA), "StructureGenerator"),  
                                FeatureItem("5", "【结构转命令】", Icons.Default.Star, Color(0xFFAA00AA), "McStructure3DCommand"),
                                FeatureItem("6", "【红石设置】", Icons.Default.Build, McColors.TextRed, "settings"),
                            )  

                            FeatureSelectionScreen(  
                                defaultFeatures = defaultFeatures,  
                                onNavigate = { route ->  
                                    when (route) {  
                                        "scriptCommand", "musicCommand", "settings", "ImageTo", "StructureGenerator", "McStructure3DCommand" -> {  
                                            navController.navigate(route) {  
                                                launchSingleTop = true  
                                                restoreState = true  
                                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        composable("scriptCommand") { ScriptCommandScreen(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                        composable("musicCommand") { musicCommand(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                        composable("ImageTo") { ImageConversionScreen(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                        composable("settings") { SettingsScreen(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                        composable("StructureGenerator") { StructureGeneratorScreen(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                        composable("McStructure3DCommand") { McStructure3DCommandScreen(onBack = { if (navController.currentBackStackEntry?.destination?.route != "featureSelection") navController.popBackStack() }) }
                    }  
                }
            }
        }  
    }  

    /**
     * 递归地拷贝 assets 下的 folder 到 targetDir（外部/内部文件目录下的实际文件夹）
     *
     * @param context 上下文
     * @param assetFolder assets 中的相对路径（例如 "note" 或 "script"）
     * @param targetDir 目标文件夹 File（会自动创建）
     */
    fun copyAssetsFolder(context: Context, assetFolder: String, targetDir: File) {
        try {
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    Log.e(TAG, "Failed to create target directory: ${targetDir.absolutePath}")
                    return
                }
            }

            val files = context.assets.list(assetFolder) ?: return

            for (name in files) {
                val assetPath = if (assetFolder.isEmpty()) name else "$assetFolder/$name"
                val outFile = File(targetDir, name)

                val subFiles = context.assets.list(assetPath) ?: emptyArray()

                if (subFiles.isNotEmpty()) {
                    // 是文件夹，递归
                    copyAssetsFolder(context, assetPath, outFile)
                } else {
                    // 是文件，拷贝
                    copyFile(context, assetPath, outFile)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException while copying assets from: $assetFolder", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while copying assets from: $assetFolder", e)
        }
    }

    /**
     * 从 assets 打开 assetPath 并写入到 outFile
     */
    private fun copyFile(context: Context, assetPath: String, outFile: File) {
        try {
            // 确保父目录存在
            outFile.parentFile?.let {
                if (!it.exists()) it.mkdirs()
            }

            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset file: $assetPath -> ${outFile.absolutePath}", e)
        }
    }
}
private val MinecraftFont = FontFamily(
    Font(R.font.minecraft)
)


// ---------- MC像素风格文本组件 ----------
@Composable
fun McText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: Float = 18f,
    textAlign: TextAlign = TextAlign.Center
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            color = McColors.TextShadow,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign,
            modifier = Modifier.offset(x = 2.dp, y = 2.dp)
        )
        Text(
            text = text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign
        )
    }
}

// ---------- MC风格按钮 ----------
@Composable
fun McButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAlert: Boolean = false
) {
    Box(
        modifier = modifier
            .background(McColors.ButtonGray)
            .border(2.dp, Color.Black)
            .padding(2.dp)
            .border(2.dp, if (isAlert) McColors.TextRed else Color.White.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        McText(text = text, color = if (isAlert) McColors.TextRed else Color.Yellow, fontSize = 16f)
    }
}

// ---------- 主界面 ----------
@Composable
fun FeatureSelectionScreen(defaultFeatures: List<FeatureItem>, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("feature_prefs", Context.MODE_PRIVATE) }

    // 在 FeatureSelectionScreen 内部：
val bannerItems = remember {
    listOf(
        BannerItem(
            id = 1,
            title = "",
            subtitle = "",
            titleColor = Color.Yellow,
            imageResId = R.drawable.board // 这里留空，会使用默认背景色
        ),
        BannerItem(
            id = 2,
            title = "不知道说什么呢",
            subtitle = "但是，你觉得这个UI界面怎么样？",
            titleColor = Color.Yellow,
            imageResId = null // 替换为你 res/drawable 文件夹下的图片名
        )
    )
}

    val itemsSaver = listSaver<SnapshotStateList<FeatureItem>, String>(  
        save = { list -> list.map { it.id } },  
        restore = { savedIds ->  
            val restoredList = defaultFeatures.sortedBy { item ->  
                val index = savedIds.indexOf(item.id)  
                if (index != -1) index else Int.MAX_VALUE  
            }  
            mutableStateListOf(*restoredList.toTypedArray())  
        }  
    )  

    val items = rememberSaveable(saver = itemsSaver) {  
        val savedStr = prefs.getString("feature_order", null)  
        val initialList = if (savedStr != null) {  
            val orderList = savedStr.split(",")  
            defaultFeatures.sortedBy {  
                val index = orderList.indexOf(it.id)  
                if (index != -1) index else Int.MAX_VALUE  
            }  
        } else {  
            defaultFeatures  
        }  
        mutableStateListOf(*initialList.toTypedArray())  
    }  

    var isEditMode by rememberSaveable { mutableStateOf(false) }  
    val spanCount = remember(prefs) { prefs.getInt("span_count", 2) }  
    val gridState = rememberLazyGridState()  
    val density = LocalDensity.current  

    val cardAspectRatio = remember(spanCount) {
        when (spanCount) {
            1 -> 3.2f
            2 -> 1.1f
            else -> 1.0f
        }
    }
  
    LaunchedEffect(Unit) {  
        val shouldEdit = prefs.getBoolean("trigger_edit_mode", false)  
        if (shouldEdit) {  
            isEditMode = true  
            prefs.edit().putBoolean("trigger_edit_mode", false).apply()  
        }
    }

    var draggingItem by remember { mutableStateOf<FeatureItem?>(null) }  
    var pointerOffset by remember { mutableStateOf(Offset.Zero) }  
    var dragItemOffset by remember { mutableStateOf(Offset.Zero) }  
    var draggedItemSize by remember { mutableStateOf(IntSize.Zero) }  

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {  
        // --- 1. 动态顶部栏 ---  
        AnimatedContent(targetState = isEditMode, label = "topBarAnimation") { editing ->  
            Row(  
                modifier = Modifier  
                    .fillMaxWidth()  
                    .padding(horizontal = 16.dp, vertical = 16.dp)  
                    .height(56.dp),  
                verticalAlignment = Alignment.CenterVertically,  
                horizontalArrangement = Arrangement.SpaceBetween  
            ) {  
                if (editing) {  
                    McButton(text = "/重置默认", onClick = {  
                        items.clear()  
                        items.addAll(defaultFeatures)  
                    }, isAlert = true)

                    McText(text = "高级配置...", color = Color.White, fontSize = 22f)  

                    McButton(text = "确认[E]", onClick = {  
                        prefs.edit().putString("feature_order", items.joinToString(",") { it.id }).apply()  
                        isEditMode = false  
                    })  
                } else {  
                    McText(  
                        text = "MINECRAFT // 指令助手",  
                        fontSize = 24f,
                        color = McColors.TextGreen
                    )  
                }  
            }  
        }  

        // --- 2. 主体拖拽与可滚动区域 ---  
        Box(  
            modifier = Modifier  
                .fillMaxSize()  
                .pointerInput(isEditMode) {  
                    if (isEditMode) {  
                        detectDragGesturesAfterLongPress(  
                            onDragStart = { offset ->  
                                val itemInfo = gridState.layoutInfo.visibleItemsInfo.find {  
                                    // 过滤掉公告栏（它的 key 或者是 index 为 0 且在非编辑下占位）
                                    it.key is String && IntRect(it.offset, it.size).contains(offset.round())  
                                }  
                                if (itemInfo != null) {  
                                    val associatedItem = items.find { it.id == itemInfo.key }
                                    if (associatedItem != null) {
                                        draggingItem = associatedItem
                                        pointerOffset = offset
                                        draggedItemSize = itemInfo.size  
                                        dragItemOffset = Offset(itemInfo.offset.x.toFloat(), itemInfo.offset.y.toFloat())  
                                    }
                                }  
                            },  
                            onDrag = { change, dragAmount ->  
                                change.consume()  
                                pointerOffset += dragAmount  
                                dragItemOffset += dragAmount  

                                val targetItemInfo = gridState.layoutInfo.visibleItemsInfo.find {  
                                    it.key is String && it.key != draggingItem?.id && IntRect(it.offset, it.size).contains(pointerOffset.round())  
                                }  

                                if (targetItemInfo != null && draggingItem != null) {  
                                    val fromIndex = items.indexOf(draggingItem)  
                                    // 映射网格实际的数据索引
                                    val toIndex = items.indexOfFirst { it.id == targetItemInfo.key }
                                    if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {  
                                        items.add(toIndex, items.removeAt(fromIndex))  
                                    }  
                                }  

                                val scrollThreshold = with(density) { 60.dp.toPx() }  
                                val viewportHeight = gridState.layoutInfo.viewportSize.height  
                                if (pointerOffset.y < scrollThreshold) {  
                                    coroutineScope.launch { gridState.scrollBy(-20f) }  
                                } else if (pointerOffset.y > viewportHeight - scrollThreshold) {  
                                    coroutineScope.launch { gridState.scrollBy(20f) }  
                                }  
                            },  
                            onDragEnd = { draggingItem = null },  
                            onDragCancel = { draggingItem = null }  
                        )  
                    }  
                }  
        ) {  
            LazyVerticalGrid(  
                columns = GridCells.Fixed(spanCount),  
                state = gridState,  
                contentPadding = PaddingValues(bottom = 16.dp),  
                verticalArrangement = Arrangement.spacedBy(16.dp),  
                horizontalArrangement = Arrangement.spacedBy(16.dp),  
                modifier = Modifier.fillMaxSize()  
            ) {  
                // 【核心修改点】将 McImageBanner 作为 Grid 的第一个 item，并设置跨满整行 (maxLineSpan)
                // 主网格 header 内部的调用
if (!isEditMode) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "mc_banner_header") {
        McImageBanner(
            bannerItems = bannerItems, // 传传入结构化后的数据源
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->  
                    val isDragging = draggingItem?.id == item.id  
                      
                    val infiniteTransition = rememberInfiniteTransition(label = "mc_float")  
                    val durationY = remember { (600..900).random() }  
                    val startOffsetY = remember { (0..200).random() }  

                    val translateY by infiniteTransition.animateFloat(  
                        initialValue = -3f,  
                        targetValue = 3f,  
                        animationSpec = infiniteRepeatable(  
                            animation = tween(durationY, easing = LinearOutSlowInEasing),  
                            repeatMode = RepeatMode.Reverse,  
                            initialStartOffset = StartOffset(startOffsetY)  
                        ),  
                        label = "float_y"  
                    )  

                    Box(  
                        modifier = Modifier  
                            .fillMaxWidth()  
                            // 左右留边，配合外部 Banner 对齐
                            .padding(
                                start = if (index % spanCount == 0) 16.dp else 0.dp,
                                end = if (index % spanCount == spanCount - 1) 16.dp else 0.dp
                            )
                            .aspectRatio(cardAspectRatio) 
                            .then(if (isDragging) Modifier.alpha(0.2f) else Modifier.alpha(1f))  
                            .animateItem()   
                            .graphicsLayer {  
                                if (isEditMode && !isDragging) {  
                                    translationY = translateY.dp.toPx()  
                                }  
                            }  
                    ) {  
                        McFeatureCardContent(  
                            item = item,  
                            index = index,  
                            isEditMode = isEditMode,  
                            onClick = { if (!isEditMode) onNavigate(item.route) }  
                        )  
                    }  
                }  
            }  

            // --- 浮动预览副本 ---  
            draggingItem?.let { item ->  
                val widthDp = with(density) { draggedItemSize.width.toDp() }  
                val heightDp = with(density) { draggedItemSize.height.toDp() }  

                Box(  
                    modifier = Modifier  
                        .size(widthDp, heightDp)  
                        .offset { IntOffset(dragItemOffset.x.roundToInt(), dragItemOffset.y.roundToInt()) }  
                        .zIndex(10f)  
                        .graphicsLayer {  
                            scaleX = 1.1f  
                            scaleY = 1.1f  
                            alpha = 0.9f  
                        }  
                ) {  
                    McFeatureCardContent(  
                        item = item,  
                        index = 0,  
                        isEditMode = isEditMode,  
                        onClick = {}  
                    )  
                }  
            }  
        }  
    }
}

// ---------- MC 风格方块卡片 ----------
@Composable
fun McFeatureCardContent(
    item: FeatureItem,
    index: Int,
    isEditMode: Boolean,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {  
        delay(index * 40L) 
        visible = true  
    }  
  
    AnimatedVisibility(  
        visible = visible,  
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200))  
    ) {  
        Box(  
            modifier = Modifier  
                .fillMaxSize()  
                .background(Color(0xFF8B8B8B))  
                .border(3.dp, Color.Black)  
                .padding(3.dp)
                .border(2.dp, Color(0xFF373737)) 
                .background(Color(0xFF4F4F4F)) 
                .clickable(  
                    interactionSource = remember { MutableInteractionSource() },  
                    indication = null, 
                    onClick = onClick  
                )  
        ) {  
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(item.color)
                            .align(Alignment.TopStart)
                    )
                }

                McText(  
                    text = item.title,  
                    fontSize = 22f,  
                    color = if (isEditMode) Color.Yellow else Color.White,
                    modifier = Modifier.weight(1f).wrapContentHeight()
                )  
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.3f))
                )
            }
        }  
    }
}

// ---------- MC 风格公告轮播图 (手动滑动) ----------

@Composable
fun McImageBanner(
    bannerItems: List<BannerItem>, // 接收独立的数据模型列表
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { bannerItems.size })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp) 
                .background(McColors.ButtonGray)  
                .border(3.dp, Color.Black)      
                .padding(3.dp)
                .border(2.dp, Color(0xFF373737)) 
                .background(Color(0xFF4F4F4F))   
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = bannerItems[page]
                
                Box(modifier = Modifier.fillMaxSize()) {
                    
                    // --- 背景层：优先显示 Drawable 图片，没有则显示背景色 ---
                    if (item.imageResId != null) {
                        Image(
                            painter = painterResource(id = item.imageResId),
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds // 裁剪并填满容器
                        )
                        // 可选：给图片上方盖一层半透明黑色遮罩，防止白色文字看不清
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(item.backgroundColor)
                        )
                    }

                    // --- 文本层 ---
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        McText(
                            text = item.title,
                            color = item.titleColor,
                            fontSize = 18f
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        McText(
                            text = item.subtitle,
                            color = item.subtitleColor,
                            fontSize = 14f
                        )
                    }
                }
            }

            // --- 右下角页码指示器 (1/3) ---
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color(0xFF555555))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${bannerItems.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = MinecraftFont 
                )
            }
        }
    }
}

// 扩展拓展转Color的函数，用于给 StatusBar 赋值
private fun Color.toArgb(): Int {
    return (this.value shr 32).toInt()
}