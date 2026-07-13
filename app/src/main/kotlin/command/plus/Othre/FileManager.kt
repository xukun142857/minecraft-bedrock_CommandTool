package command.plus

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
                
// 格式化文件大小
fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// 格式化时间戳
fun formatFileDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun FilePickerDialog(
    initialPath: String = Environment.getExternalStorageDirectory().absolutePath,
    allowedExtensions: List<String> = emptyList(),
    isMultiSelect: Boolean = false,
    onDismiss: () -> Unit,
    onFilesSelected: (List<File>) -> Unit
) {
    var currentDirectory by remember { mutableStateOf(File(initialPath)) }
    var filesInDir by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // 关键：记住列表状态用于滚动条联动
    val listState = rememberLazyListState()

    LaunchedEffect(currentDirectory) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val allFiles = currentDirectory.listFiles() ?: emptyArray()
            val filtered = allFiles.filter { !it.isHidden }
                .filter { it.isDirectory || allowedExtensions.isEmpty() || allowedExtensions.contains(it.extension.lowercase()) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            withContext(Dispatchers.Main) {
                filesInDir = filtered
                isLoading = false
                listState.scrollToItem(0) // 切换目录时重置滚动位置
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp // 增加海拔感，UI更高级
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题
                Text(
                    text = currentDirectory.absolutePath.ifEmpty { "存储根目录" },
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        // 列表与滚动条并排
                        Row(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                if (currentDirectory.absolutePath != initialPath) {
                                    item {
                                        FileListItem(file = currentDirectory.parentFile!!, nameOverride = "..", isSelected = false) {
                                            currentDirectory = currentDirectory.parentFile!!
                                        }
                                    }
                                }
                                items(filesInDir, key = { it.absolutePath }) { file ->
                                    val isSelected = selectedFiles.contains(file)
                                    FileListItem(file = file, isSelected = isSelected) {
                                        if (file.isDirectory) {
                                            currentDirectory = file
                                        } else {
                                            if (isMultiSelect) {
                                                selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                            } else {
                                                onFilesSelected(listOf(file))
                                                onDismiss()
                                            }
                                        }
                                    }
                                }
                            }
                            // 渲染垂直滚动条
                            VerticalScrollbar(state = listState)
                        }
                    }
                }

                // 底部按钮栏
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    if (isMultiSelect) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onFilesSelected(selectedFiles.toList()); onDismiss() },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Text("确定 (${selectedFiles.size})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: File,
    nameOverride: String? = null, // 用于处理 ".." 返回上一级的情况
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nameOverride ?: file.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 文件信息展示 (YY-MM-DD HH:MM | 大小)
            if (nameOverride == null) {
                val info = if (file.isDirectory) {
                    "${formatFileDate(file.lastModified())} | 文件夹"
                } else {
                    "${formatFileDate(file.lastModified())} | ${formatFileSize(file.length())}"
                }
                Text(
                    text = info,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}


@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(30.dp) // 增加触摸热区
            .pointerInput(state.layoutInfo.totalItemsCount) {
                detectVerticalDragGestures { change, _ ->
                    val totalItems = state.layoutInfo.totalItemsCount
                    if (totalItems <= 0) return@detectVerticalDragGestures

                    // 计算手指在滚动条轨道上的位置比例 (0.0 ~ 1.0)
                    val dragY = change.position.y
                    val trackHeight = size.height.toFloat()
                    val positionRatio = (dragY / trackHeight).coerceIn(0f, 1f)

                    // 将比例映射到具体的 Item 索引
                    val targetIndex = (positionRatio * totalItems).toInt()
                    
                    coroutineScope.launch {
                        // 快速跳转到对应位置
                        state.scrollToItem(targetIndex.coerceAtMost(totalItems - 1))
                    }
                }
            }
    ) {
        val totalItems = state.layoutInfo.totalItemsCount
        val visibleItemsInfo = state.layoutInfo.visibleItemsInfo
        
        if (totalItems == 0 || visibleItemsInfo.isEmpty()) return@BoxWithConstraints

        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val viewPortHeight = size.height
            val firstVisibleIndex = state.firstVisibleItemIndex
            val visibleItemsCount = visibleItemsInfo.size

            // 计算滑块高度：可视占比
            val scrollbarHeight = (viewPortHeight * (visibleItemsCount.toFloat() / totalItems))
                .coerceAtLeast(40.dp.toPx())

            // 计算滑块位置：当前索引 / 总数
            val scrollbarOffset = (viewPortHeight - scrollbarHeight) * (firstVisibleIndex.toFloat() / (totalItems - visibleItemsCount).coerceAtLeast(1))

            // 绘制轨道
            drawRoundRect(
                color = trackColor,
                size = size.copy(width = 4.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
            
            // 绘制滑块
            drawRoundRect(
                color = scrollbarColor,
                topLeft = Offset(0f, scrollbarOffset.coerceIn(0f, viewPortHeight - scrollbarHeight)),
                size = androidx.compose.ui.geometry.Size(4.dp.toPx(), scrollbarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}