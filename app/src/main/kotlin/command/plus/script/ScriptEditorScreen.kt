package command.plus

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Stack
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Context
import android.content.ContextWrapper
import android.util.Log

// 逻辑控制块类型枚举
enum class LineType { IF, CONDITION, THEN, ELSE, ACTION }

// 条件判定规则结构
data class ConditionRule(
    var relation: String = "&&",   
    var expression: String = ""    
)

// 树形节点数据结构（已增加说明字段）
class EditorLine(
    val id: String = UUID.randomUUID().toString(),
    val type: LineType,
    initialCommandName: String,
    initialArgs: String = "",
    initialConditionRule: ConditionRule? = null,
    initialIsExpanded: Boolean = false,
    initialDescription: String = "" // 注入说明字段
) {
    var commandName by mutableStateOf(initialCommandName)
    var args by mutableStateOf(initialArgs)
    var conditionRule by mutableStateOf(initialConditionRule)
    var isExpanded by mutableStateOf(initialIsExpanded)
    var description by mutableStateOf(initialDescription) // 状态化说明

    val children = mutableStateListOf<EditorLine>()

    fun deepCopy(refreshId: Boolean = true): EditorLine {
        val copy = EditorLine(
            id = if (refreshId) UUID.randomUUID().toString() else this.id,
            type = this.type,
            initialCommandName = this.commandName,
            initialArgs = this.args,
            initialConditionRule = this.conditionRule?.copy(),
            initialIsExpanded = this.isExpanded,
            initialDescription = this.description // 复制说明
        )
        this.children.forEach { child ->
            copy.children.add(child.deepCopy(refreshId))
        }
        return copy
    }
}

// 扁平化渲染元数据
data class FlattenedLineMetadata(
    val line: EditorLine,
    val indent: Int,
    val isVisible: Boolean,
    val hasChildren: Boolean,
    val parentList: SnapshotStateList<EditorLine>,
    val indexInParent: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    initialScriptText: String,
    onBack: () -> Unit,
    onSaveComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val rootEditorLines = remember { mutableStateListOf<EditorLine>() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val lazyListState = rememberLazyListState()

    var clipboardLine by remember { mutableStateOf<EditorLine?>(null) }
    var draggedLineId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(initialScriptText) {    
        if (rootEditorLines.isEmpty() && initialScriptText.isNotBlank()) {    
            rootEditorLines.addAll(parseTextToTree(initialScriptText))    
        }    
    }    

    var selectedLineId by remember { mutableStateOf<String?>(null) }    
    var showImportDialog by remember { mutableStateOf(false) }    
    var showExitConfirmDialog by remember { mutableStateOf(false) }    
    
    var showActionSelectDialog by remember { mutableStateOf(false) }    
    var editingConditionLine by remember { mutableStateOf<EditorLine?>(null) }
    var editingActionLine by remember { mutableStateOf<EditorLine?>(null) }
    var editingDescriptionLine by remember { mutableStateOf<EditorLine?>(null) } // 编辑说明节点状态
    var isCreatingNew by remember { mutableStateOf(false) } 
        
    var targetParentList by remember { mutableStateOf<SnapshotStateList<EditorLine>?>(null) }
    var targetIndexInParent by remember { mutableStateOf(-1) }
    var insertMode by remember { mutableStateOf("after") }     

    val flattenedTreeItems = remember(rootEditorLines) {    
        derivedStateOf {    
            val resultList = mutableListOf<FlattenedLineMetadata>()
            
            fun traverse(
                nodes: SnapshotStateList<EditorLine>,
                currentIndent: Int,
                parentVisible: Boolean,
                currentParentList: SnapshotStateList<EditorLine>
            ) {
                nodes.forEachIndexed { index, node ->
                    val isNodeVisible = parentVisible
                    val visualIndent = if (node.type == LineType.CONDITION) currentIndent + 1 else currentIndent
                    val hasChildren = node.children.isNotEmpty()

                    resultList.add(
                        FlattenedLineMetadata(
                            line = node,
                            indent = visualIndent,
                            isVisible = isNodeVisible,
                            hasChildren = hasChildren,
                            parentList = currentParentList,
                            indexInParent = index
                        )
                    )
                    
                    val nextIndent = if (node.type == LineType.THEN || node.type == LineType.ELSE) {
                        currentIndent + 1
                    } else {
                        currentIndent
                    }
                    
                    val nextVisible = isNodeVisible && node.isExpanded
                    traverse(node.children, nextIndent, nextVisible, node.children)
                }
            }
            
            traverse(rootEditorLines, currentIndent = 0, parentVisible = true, currentParentList = rootEditorLines)
            resultList    
        }    
    }    

    BackHandler { showExitConfirmDialog = true }    

    Scaffold(    
        topBar = {    
            TopAppBar(    
                title = { Text("树形可视化工作区") },    
                navigationIcon = {    
                    IconButton(onClick = { showExitConfirmDialog = true }) {    
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")    
                    }    
                },    
                actions = {    
                    TextButton(onClick = { showImportDialog = true }) {    
                        Icon(Icons.Default.Download, contentDescription = "导入")    
                        Spacer(modifier = Modifier.width(4.dp))    
                        Text("读取导入")    
                    }    
                    Button(onClick = { onSaveComplete(parseTreeToText(rootEditorLines)) }, modifier = Modifier.padding(end = 8.dp)) {    
                        Text("保存完成")    
                    }    
                }    
            )    
        },    
        snackbarHost = { SnackbarHost(snackbarHostState) },    
        floatingActionButton = {    
            ExtendedFloatingActionButton(    
                onClick = {    
                    targetParentList = rootEditorLines
                    targetIndexInParent = -1
                    insertMode = "after"    
                    showActionSelectDialog = true    
                },    
                icon = { Icon(Icons.Default.Add, contentDescription = "追加") },    
                text = { Text("追加行为块") }    
            )    
        }    
    ) { paddingValues ->    
        Box(    
            modifier = Modifier    
                .fillMaxSize()    
                .padding(paddingValues)    
        ) {    
            if (selectedLineId != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            selectedLineId = null
                        }
                )
            }

            if (rootEditorLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {    
                    Text("画布无控制逻辑，请点击右下角或导入新配置", color = Color.Gray)    
                }    
            } else {    
                val visibleItems = flattenedTreeItems.value.filter { it.isVisible }

                LazyColumn(    
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize().zIndex(1f),    
                    contentPadding = PaddingValues(bottom = 100.dp)    
                ) {    
                    itemsIndexed(    
                        items = visibleItems,    
                        key = { _, item -> item.line.id }    
                    ) { _, metadata ->    
                        val line = metadata.line
                        val pList = metadata.parentList

                        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { true })
                        var deleteHandled by remember(line.id) { mutableStateOf(false) }

                        LaunchedEffect(dismissState.currentValue) {
    if (deleteHandled) return@LaunchedEffect
    if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
        dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd
    ) {
        deleteHandled = true
        val currentIdx = pList.indexOf(line)
        if (currentIdx != -1) {
            val nodeToRestore = line.deepCopy(refreshId = false)
            pList.removeAt(currentIdx)
            
            // 核心修改：短时间内连续删除时，立即强行清除/关闭前一个未消失的撤销 Snackbar 提示框
            snackbarHostState.currentSnackbarData?.dismiss()

            coroutineScope.launch {
                val snackbarResult = snackbarHostState.showSnackbar(
                    message = "已移除",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Short
                )
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    pList.add(currentIdx.coerceAtMost(pList.size), nodeToRestore)
                }
            }
        }
        coroutineScope.launch {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
        deleteHandled = false
    }
}

                        val isDraggingThis = draggedLineId == line.id

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isDraggingThis) Modifier else Modifier.animateItem())
                                .pointerInput(line.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedLineId = line.id
                                            dragOffsetY = 0f
                                            isDragging = true
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            
                                            val density = context.resources.displayMetrics.density
                                            val layoutInfo = lazyListState.layoutInfo
                                            val visibleLayoutItems = layoutInfo.visibleItemsInfo
                                            val dynamicIdx = pList.indexOf(line)
                                            
                                            if (dynamicIdx != -1) {
                                                if (dynamicIdx + 1 < pList.size) {
                                                    val nextLine = pList[dynamicIdx + 1]
                                                    val startIndex = visibleItems.indexOfFirst { it.line.id == nextLine.id }
                                                    if (startIndex != -1) {
                                                        var itemsToSum = 1
                                                        for (k in (startIndex + 1) until visibleItems.size) {
                                                            if (visibleItems[k].indent > visibleItems[startIndex].indent) {
                                                                itemsToSum++
                                                            } else {
                                                                break
                                                            }
                                                        }
                                                        var totalSkippedHeight = 0
                                                        for (m in 0 until itemsToSum) {
                                                            val itemKey = visibleItems[startIndex + m].line.id
                                                            val layoutItem = visibleLayoutItems.find { it.key == itemKey }
                                                            totalSkippedHeight += layoutItem?.size ?: (64f * density).toInt()
                                                        }

                                                        if (dragOffsetY > totalSkippedHeight * 0.6f) {
                                                            pList.removeAt(dynamicIdx)
                                                            pList.add(dynamicIdx + 1, line)
                                                            dragOffsetY = dragOffsetY - totalSkippedHeight.toFloat()
                                                        }
                                                    }
                                                }

                                                if (dynamicIdx - 1 >= 0) {
                                                    val prevLine = pList[dynamicIdx - 1]
                                                    val startIndex = visibleItems.indexOfFirst { it.line.id == prevLine.id }
                                                    if (startIndex != -1) {
                                                        var itemsToSum = 1
                                                        for (k in (startIndex + 1) until visibleItems.size) {
                                                            if (visibleItems[k].indent > visibleItems[startIndex].indent) {
                                                                itemsToSum++
                                                            } else {
                                                                break
                                                            }
                                                        }
                                                        var totalSkippedHeight = 0
                                                        for (m in 0 until itemsToSum) {
                                                            val itemKey = visibleItems[startIndex + m].line.id
                                                            val layoutItem = visibleLayoutItems.find { it.key == itemKey }
                                                            totalSkippedHeight += layoutItem?.size ?: (64f * density).toInt()
                                                        }

                                                        if (dragOffsetY < -totalSkippedHeight * 0.6f) {
                                                            pList.removeAt(dynamicIdx)
                                                            pList.add(dynamicIdx - 1, line)
                                                            dragOffsetY = dragOffsetY + totalSkippedHeight.toFloat()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedLineId = null
                                            dragOffsetY = 0f
                                            isDragging = false
                                        },
                                        onDragCancel = {
                                            draggedLineId = null
                                            dragOffsetY = 0f
                                            isDragging = false
                                        },
                                    )
                                }
                                .graphicsLayer {
                                    if (isDraggingThis) {
                                        translationY = dragOffsetY
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .zIndex(if (isDraggingThis) 10f else 2f)
                        ) {
                            SwipeToDismissBox(    
                                modifier = Modifier,   
                                state = dismissState,    
                                backgroundContent = {    
                                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))) 
                                }    
                            ) {    
                                val currentIdx = pList.indexOf(line)
                                val isSecondOrMoreCondition = line.type == LineType.CONDITION && 
                                        currentIdx > 0 && pList[currentIdx - 1].type == LineType.CONDITION

                                ScriptLineRow(    
                                    line = line,    
                                    indent = metadata.indent,    
                                    hasChildren = metadata.hasChildren,    
                                    isDraggingThis = isDraggingThis,
                                    isSelected = selectedLineId == line.id,    
                                    isSecondCondition = isSecondOrMoreCondition,
                                    clipboardAvailable = clipboardLine != null,
                                    onLineClick = { 
                                        selectedLineId = if (selectedLineId == line.id) null else line.id 
                                    },    
                                    onEditAction = {    
                                        isCreatingNew = false
                                        if (line.type == LineType.CONDITION) {    
                                            editingConditionLine = line    
                                        } else if (line.type == LineType.ACTION) {    
                                            editingActionLine = line
                                        }    
                                    },    
                                    onToggleExpand = {    
                                        line.isExpanded = !line.isExpanded
                                    },    
                                    onAddInsideClick = {    
                                        if (line.type == LineType.IF) {
                                            targetParentList = line.children
                                            targetIndexInParent = -1
                                            isCreatingNew = true
                                            editingConditionLine = EditorLine(type = LineType.CONDITION, initialCommandName = "cond", initialConditionRule = ConditionRule())
                                        } else if (line.type == LineType.THEN || line.type == LineType.ELSE) {
                                            targetParentList = line.children
                                            targetIndexInParent = -1
                                            insertMode = "inside"    
                                            showActionSelectDialog = true    
                                        }
                                    },    
                                    onActionSelected = { mode ->
    if (line.type == LineType.CONDITION) {
        // 【核心重定向】如果当前操作的是条件块，无论前后，目标容器必须是当前的 pList（即父 IF 的 children 集合）
        targetParentList = pList
        insertMode = mode
        
        // 计算在当前条件列表中的精确相对位置
        val idx = pList.indexOf(line)
        targetIndexInParent = idx
        
        // 强制开启“新建”模式，并拉起专门的“断言判定条件配置弹窗”
        isCreatingNew = true
        editingConditionLine = EditorLine(
            type = LineType.CONDITION, 
            initialCommandName = "cond", 
            initialConditionRule = ConditionRule()
        )
    } else {
        // 其余常规行动/流控块，走原本的分类选择逻辑
        targetParentList = pList
        targetIndexInParent = pList.indexOf(line)
        insertMode = mode    
        showActionSelectDialog = true    
    }
},
                                    onToggleRelation = {
                                        val currentRule = line.conditionRule ?: ConditionRule()
                                        val nextRelation = if (currentRule.relation == "&&") "||" else "&&"
                                        line.conditionRule = currentRule.copy(relation = nextRelation)
                                    },
                                    onCopyClick = { clipboardLine = line.deepCopy(refreshId = false) },
                                    onPasteClick = { mode -> 
                                        val source = clipboardLine ?: return@ScriptLineRow
                                        val elementToPaste = source.deepCopy(refreshId = true) 
                                        val activeIdx = pList.indexOf(line)
                                        if (activeIdx != -1) {
                                            when (mode) {
                                                "before" -> pList.add(activeIdx.coerceAtLeast(0), elementToPaste)
                                                "after" -> pList.add((activeIdx + 1).coerceAtMost(pList.size), elementToPaste)
                                                "inside" -> line.children.add(elementToPaste)
                                            }
                                        }
                                        selectedLineId = null
                                    },
                                    onAddDescriptionClick = {
                                        editingDescriptionLine = line
                                    }
                                )    
                            }    
                        }
                    }    
                }    
            }    
        }    
    }    

    if (showExitConfirmDialog) {    
        AlertDialog(    
            onDismissRequest = { showExitConfirmDialog = false },    
            title = { Text("放弃当前改动？") },    
            confirmButton = { TextButton(onClick = { showExitConfirmDialog = false; onBack() }) { Text("离开") } },    
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("留下") } }    
        )    
    }    

    if (showImportDialog) {    
        ImportScriptDialog(    
            onDismiss = { showImportDialog = false },    
            onConfirmImport = { importedText ->    
                rootEditorLines.clear()    
                rootEditorLines.addAll(parseTextToTree(importedText))    
                showImportDialog = false    
            }    
        )    
    }    

    if (editingConditionLine != null) {
    SingleConditionEditDialog(
        line = editingConditionLine!!,
        onDismiss = { editingConditionLine = null },
        onSave = { updatedRule ->
            val pList = targetParentList ?: rootEditorLines
            if (isCreatingNew) {
                // 构建新断言节点
                val newLine = EditorLine(
                    type = LineType.CONDITION, 
                    initialCommandName = "cond", 
                    initialConditionRule = updatedRule
                )
                
                val idx = targetIndexInParent
                if (idx != -1) {
                    // 根据点击的模式，精准决定插入到当前条件的前面还是后面
                    when (insertMode) {
                        "before" -> pList.add(idx.coerceAtLeast(0), newLine)
                        "after" -> pList.add((idx + 1).coerceAtMost(pList.size), newLine)
                        else -> pList.add(newLine)
                    }
                } else {
                    // 兜底策略：如果找不到索引，按照原逻辑追加在条件区末尾
                    var insertLoc = 0
                    while (insertLoc < pList.size && pList[insertLoc].type == LineType.CONDITION) {
                        insertLoc++
                    }
                    pList.add(insertLoc, newLine)
                }
            } else {
                // 编辑已有条件逻辑不变
                editingConditionLine!!.conditionRule = updatedRule
            }
            editingConditionLine = null
            selectedLineId = null
            isCreatingNew = false // 重置状态
        }
    )
}

    if (editingActionLine != null) {
        ActionEditDialog(
            line = editingActionLine!!,
            onDismiss = { editingActionLine = null },
            onSave = { updatedArgs ->
                val pList = targetParentList ?: rootEditorLines
                if (isCreatingNew) {
                    val newLine = EditorLine(type = editingActionLine!!.type, initialCommandName = editingActionLine!!.commandName, initialArgs = updatedArgs)
                    val idx = targetIndexInParent
                    when (insertMode) {
                        "before" -> pList.add(idx.coerceAtLeast(0), newLine)
                        "after" -> {
                            if (idx == -1) pList.add(newLine) 
                            else pList.add((idx + 1).coerceAtMost(pList.size), newLine)
                        }
                        "inside" -> pList.add(newLine)
                    }
                } else {
                    editingActionLine!!.args = updatedArgs
                }
                editingActionLine = null
                selectedLineId = null
            }
        )
    }

    // 弹出说明配置弹窗
    if (editingDescriptionLine != null) {
        DescriptionEditDialog(
            line = editingDescriptionLine!!,
            onDismiss = { editingDescriptionLine = null },
            onSave = { updatedComment ->
                editingDescriptionLine!!.description = updatedComment
                editingDescriptionLine = null
                selectedLineId = null
            }
        )
    }

    if (showActionSelectDialog) {    
        CategorizedActionSelectDialog(    
            onDismiss = { showActionSelectDialog = false },    
            onActionChosen = { type, name, defaultArgs ->    
                val pList = targetParentList ?: rootEditorLines
                val idx = targetIndexInParent
                var targetIdx = if (idx == -1) pList.size else idx + 1
                if (insertMode == "before" && idx != -1) targetIdx = idx

                if (type == LineType.IF || type == LineType.THEN || type == LineType.ELSE) {
                    val block = EditorLine(type = type, initialCommandName = name)
                    if (insertMode == "inside") pList.add(block) else pList.add(targetIdx.coerceAtMost(pList.size), block)
                    showActionSelectDialog = false    
                    selectedLineId = null    
                } else {    
                    isCreatingNew = true
                    editingActionLine = EditorLine(type = type, initialCommandName = name, initialArgs = defaultArgs)
                    showActionSelectDialog = false
                }    
            }    
        )    
    }
}

@Composable
fun ScriptLineRow(
    line: EditorLine,
    indent: Int,            
    hasChildren: Boolean,    
    isDraggingThis: Boolean, 
    isSelected: Boolean,
    isSecondCondition: Boolean, 
    clipboardAvailable: Boolean,
    onLineClick: () -> Unit,
    onEditAction: () -> Unit,
    onToggleExpand: () -> Unit, 
    onAddInsideClick: () -> Unit,
    onActionSelected: (String) -> Unit,
    onToggleRelation: () -> Unit,
    onCopyClick: () -> Unit,
    onPasteClick: (String) -> Unit,
    onAddDescriptionClick: () -> Unit // 新增回调
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val containerColor = remember(line.type) {
        when (line.type) {    
            LineType.IF -> colorScheme.primaryContainer
            LineType.THEN -> colorScheme.secondaryContainer
            LineType.ELSE -> colorScheme.tertiaryContainer
            LineType.CONDITION -> colorScheme.surfaceContainerHigh
            LineType.ACTION -> colorScheme.surface    
        }
    }

    val dashedModifier = if (isSelected || isDraggingThis) {
        Modifier.drawWithContent {
            drawContent() 
            val stroke = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f))
            drawRoundRect(
                color = colorScheme.primary.copy(alpha = 0.8f),
                style = stroke,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f * density)
            )
        }
    } else Modifier

    Column(    
        modifier = Modifier    
            .fillMaxWidth()    
            .clickable { onLineClick() }    
            .padding(vertical = 3.dp, horizontal = 12.dp)    
            .padding(start = (indent * 16).dp) 
    ) {    
        Box(modifier = Modifier.fillMaxWidth()) {
            Card(    
                colors = CardDefaults.cardColors(containerColor = containerColor),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isDraggingThis) 6.dp else 1.dp),
                modifier = Modifier.fillMaxWidth().then(dashedModifier) 
            ) {    
                // 改为 Column 包裹主体和下方的说明栏
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(    
                        modifier = Modifier.fillMaxWidth(),    
                        verticalAlignment = Alignment.CenterVertically,    
                        horizontalArrangement = Arrangement.SpaceBetween    
                    ) {    
                        Row(  
                            verticalAlignment = Alignment.CenterVertically,  
                            modifier = Modifier.weight(1f)  
                        ) {  
                            if (isSecondCondition && line.conditionRule != null) {
                                Text(
                                    text = if (line.conditionRule!!.relation == "&&") "并且 " else "或者 ",
                                    color = Color(0xFFD32F2F),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        textDecoration = TextDecoration.Underline,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.clickable { onToggleRelation() }.padding(end = 4.dp)
                                )
                            }
                            val displayTitle = when (line.type) {    
                                LineType.IF -> "【如果】 .if"    
                                LineType.THEN -> "【那么】 .then"    
                                LineType.ELSE -> "【否则】 .else"    
                                LineType.CONDITION -> "满足条件: [ ${line.conditionRule?.expression ?: ""} ]"
                                LineType.ACTION -> "【行动】 .${line.commandName} (${line.args})"    
                            }    
                            Text(displayTitle, style = MaterialTheme.typography.bodyLarge)    
                        }  

                        Row {    
                            if (line.type == LineType.IF || line.type == LineType.THEN || line.type == LineType.ELSE) {    
                                if (hasChildren) {
                                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {  
                                        Icon(imageVector = if (line.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, contentDescription = null)  
                                    }  
                                } else {
                                    TextButton(onClick = onAddInsideClick) {    
                                        Icon(Icons.Default.LibraryAdd, contentDescription = null)    
                                        Spacer(modifier = Modifier.width(4.dp))    
                                        Text("内部添加")    
                                    }    
                                }
                            } else if (line.type == LineType.ACTION || line.type == LineType.CONDITION) {    
                                IconButton(onClick = onEditAction, modifier = Modifier.size(28.dp)) {    
                                    Icon(Icons.Default.Edit, contentDescription = null)    
                                }    
                            }    
                        }    
                    }

                    // --- 核心重构：高度拟合图片的专属说明美化显示栏 ---
                    if (line.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 提供暗色底色并自带柔和圆角
                                .background(
                                    color = colorScheme.errorContainer.copy(alpha = 0.15f), 
                                    shape = RoundedCornerShape(6.dp)
                                )
                                // 精准测算并在内容左侧绘制具有视觉厚度的高亮修饰垂直条
                                .drawBehind {
                                    val strokeWidthPixel = 3.5f * density
                                    drawLine(
                                        color = Color(0xFFD32F2F), // 温暖的高亮红褐色线条，完美衬托色调
                                        start = androidx.compose.ui.geometry.Offset(strokeWidthPixel / 2f, 0f),
                                        end = androidx.compose.ui.geometry.Offset(strokeWidthPixel / 2f, size.height),
                                        strokeWidth = strokeWidthPixel
                                    )
                                }
                                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "说明: ${line.description}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }    

            if (isSelected && !isDraggingThis) {
                HarmoniousActionMenu(
                    lineType = line.type,
                    clipboardAvailable = clipboardAvailable,
                    onDismiss = onLineClick,
                    onAddInside = onAddInsideClick,
                    onAddBefore = { onActionSelected("before") },
                    onAddAfter = { onActionSelected("after") },
                    onCopy = onCopyClick,
                    onPaste = onPasteClick,
                    onAddDescription = onAddDescriptionClick // 绑定方法
                )
            }
        }
    }
}

@Composable
fun HarmoniousActionMenu(
    lineType: LineType,
    clipboardAvailable: Boolean,
    onDismiss: () -> Unit,
    onAddInside: () -> Unit,
    onAddBefore: () -> Unit,
    onAddAfter: () -> Unit,
    onCopy: () -> Unit,
    onPaste: (String) -> Unit,
    onAddDescription: () -> Unit // 新增回调参数
) {
    var menuStage by remember { mutableStateOf(1) }
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(-16, 45)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.width(250.dp).padding(4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (menuStage == 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("添加", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MenuPillButton("在内部添加", enabled = lineType in listOf(LineType.IF, LineType.THEN, LineType.ELSE), modifier = Modifier.weight(1f)) { onAddInside(); onDismiss() }
                            MenuPillButton("在前面添加", modifier = Modifier.weight(1f)) { onAddBefore(); onDismiss() }
                        }
                        MenuPillButton("在后面添加", modifier = Modifier.fillMaxWidth()) { onAddAfter(); onDismiss() }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("操作", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            MenuPillButton("复制", modifier = Modifier.weight(1f)) { onCopy(); onDismiss() }
                            MenuPillButton("粘贴", enabled = clipboardAvailable, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f)) { menuStage = 2 }
                        }
                        // “添加说明”全面融入胶囊菜单按钮设计体系中
                        MenuPillButton(
                            text = "添加 / 修改说明", 
                            modifier = Modifier.fillMaxWidth()
                        ) { 
                            onAddDescription()
                            onDismiss() 
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { menuStage = 1 }.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "后退", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("粘贴位置", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        MenuPillButton("在前面粘贴", modifier = Modifier.fillMaxWidth()) { onPaste("before") }
                        MenuPillButton("在后面粘贴", modifier = Modifier.fillMaxWidth()) { onPaste("after") }
                        if (lineType == LineType.IF || lineType == LineType.THEN || lineType == LineType.ELSE) {
                            MenuPillButton("在内部粘贴", modifier = Modifier.fillMaxWidth()) { onPaste("inside") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuPillButton(
    text: String, 
    enabled: Boolean = true, 
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer, 
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer, 
    modifier: Modifier = Modifier, // 暴露 Modifier 允许外部弹性控制排版宽度
    onClick: () -> Unit
) {
    Button(
        onClick = onClick, 
        enabled = enabled, 
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor), 
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), 
        modifier = modifier.height(32.dp), 
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

// 独立的逻辑说明修改弹窗
@Composable
fun DescriptionEditDialog(
    line: EditorLine, 
    onDismiss: () -> Unit, 
    onSave: (String) -> Unit
) {
    var descriptionInput by remember { mutableStateOf(line.description) }
    AlertDialog(
        onDismissRequest = onDismiss, 
        title = { Text("配置逻辑节点行说明") }, 
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) { 
                OutlinedTextField(
                    value = descriptionInput, 
                    onValueChange = { descriptionInput = it }, 
                    label = { Text("节点备注说明内容") }, 
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入此步的操作含义") }
                ) 
            } 
        }, 
        confirmButton = { 
            Button(onClick = { onSave(descriptionInput.trim()) }) { Text("确认修改") } 
        }, 
        dismissButton = { 
            TextButton(onClick = onDismiss) { Text("取消") } 
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleConditionEditDialog(
    line: EditorLine, 
    onDismiss: () -> Unit, 
    onSave: (ConditionRule) -> Unit
) {
    val currentRule = line.conditionRule ?: ConditionRule()
    
    // 解析已有表达式 (简单兼容: 变量 + 空格 + 符号 + 空格 + 值)
    val tokens = remember(currentRule.expression) {
        val expr = currentRule.expression.trim()
        val regex = Regex("""^(\S+)\s*(==|!=|>=|<=|>|<)\s*(.*)$""")
        val match = regex.matchEntire(expr)
        if (match != null) {
            Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        } else {
            Triple(expr.substringBefore("==").trim(), "==", expr.substringAfter("==").trim())
        }
    }

    // 变量管理状态
    val builtInVars = listOf("\$isBlockType", "\$ItemText", "\$configA", "\$configB", "\$configC", "\$cmdResult", "\$Weight", "\$Height")
    var selectedVar by remember { mutableStateOf(tokens.first.ifBlank { builtInVars.first() }) }
    var isCustomVarMode by remember { mutableStateOf(selectedVar !in builtInVars) }
    var customVarInput by remember { mutableStateOf(if (isCustomVarMode) selectedVar else "") }

    // 操作符状态
    val operators = listOf("==", "!=", ">", "<", ">=", "<=")
    var operatorExpanded by remember { mutableStateOf(false) }
    var selectedOperator by remember { mutableStateOf(if (tokens.second in operators) tokens.second else "==") }

    // 目标值状态
    var valueInput by remember { mutableStateOf(tokens.third) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置断言判定条件", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                
                // 1. 变量选择区
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("选择判定变量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1.5f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            val activeColor = MaterialTheme.colorScheme.primaryContainer
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (!isCustomVarMode) activeColor else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { isCustomVarMode = false; selectedVar = builtInVars.first() }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("内置变量", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isCustomVarMode) activeColor else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { isCustomVarMode = true; selectedVar = customVarInput }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("自定义", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!isCustomVarMode) {
                            var varExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = varExpanded,
                                onExpandedChange = { varExpanded = it },
                                modifier = Modifier.weight(2f)
                            ) {
                                OutlinedTextField(
                                    value = selectedVar,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = varExpanded) },
                                    modifier = Modifier.menuAnchor(),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                                ExposedDropdownMenu(expanded = varExpanded, onDismissRequest = { varExpanded = false }) {
                                    builtInVars.forEach { v ->
                                        DropdownMenuItem(
                                            text = { Text(v) },
                                            onClick = { selectedVar = v; varExpanded = false }
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = customVarInput,
                                onValueChange = { customVarInput = it; selectedVar = it },
                                placeholder = { Text("输入变量名") },
                                modifier = Modifier.weight(2f),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // 2. 关系判定符号选择
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("判定操作符", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    ExposedDropdownMenuBox(
                        expanded = operatorExpanded,
                        onExpandedChange = { operatorExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = when (selectedOperator) {
                                "==" -> "等于 (==)"
                                "!=" -> "不等于 (!=)"
                                ">" -> "大于 (>)"
                                "<" -> "小于 (<)"
                                ">=" -> "大于等于 (>=)"
                                "<=" -> "小于等于 (<=)"
                                else -> selectedOperator
                            },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operatorExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = operatorExpanded, onDismissRequest = { operatorExpanded = false }) {
                            operators.forEach { op ->
                                DropdownMenuItem(
                                    text = { Text(op, fontWeight = FontWeight.Bold) },
                                    onClick = { selectedOperator = op; operatorExpanded = false }
                                )
                            }
                        }
                    }
                }

                // 3. 目标比较值输入
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("比较目标值", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = valueInput,
                        onValueChange = { valueInput = it },
                        placeholder = { Text("数值、字符串或表达式") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalVar = selectedVar.trim()
                val finalExpr = "$finalVar $selectedOperator ${valueInput.trim()}"
                onSave(ConditionRule(relation = currentRule.relation, expression = finalExpr))
            }) { Text("确认修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun ActionEditDialog(
    line: EditorLine, 
    onDismiss: () -> Unit, 
    onSave: (String) -> Unit
) {
    val cmd = line.commandName.lowercase()
    
    // 解析原始参数列表
    val originalArgsList = remember(line.args) {
        line.args.split(",").map { it.trim() }
    }

   
    var x by remember { mutableStateOf(originalArgsList.getOrNull(0) ?: "0") }
    var y by remember { mutableStateOf(originalArgsList.getOrNull(1) ?: "0") }
    var x1 by remember { mutableStateOf(originalArgsList.getOrNull(2) ?: "0") }
    var y1 by remember { mutableStateOf(originalArgsList.getOrNull(3) ?: "0") }
    var time by remember { mutableStateOf(originalArgsList.getOrNull(4) ?: "500") }

    // 其余非 exeaction 指令单框所用的状态
    var singleExpr by remember { mutableStateOf(line.args) }
    var varName by remember { mutableStateOf(originalArgsList.getOrNull(0) ?: "") }
    var varExpr by remember { mutableStateOf(originalArgsList.getOrNull(1) ?: "") }
    var defaultArgs by remember { mutableStateOf(line.args) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (cmd) {
                    "exeaction" -> "屏幕高级动作"
                    "sleep" -> "线程阻塞延迟"
                    "settext" -> "写入文本内容"
                    "adbcmd" -> "执行高级 Shell 指令"
                    "ftoast" -> "全局轻提示"
                    "setvar" -> "变量分配初始化"
                    "addvar" -> "变量数值操作"
                    "nextitem" -> "推进索引"
                    "restart" -> "从头执行一遍脚本"
                    else -> "设定 [ .${line.commandName} ] 行动核心参数"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (cmd) {
                    "exeaction" -> {
                        val context = LocalContext.current
                        val activity = remember(context) { context.findActivity() }
                        
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("动作参数矩阵设定：", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            
                            // 起点坐标
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = x, onValueChange = { x = it }, label = { Text("起始 X") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = y, onValueChange = { y = it }, label = { Text("起始 Y") }, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { 
                                        activity?.startLocationPicker("A") { selectedX, selectedY ->
                                            x = selectedX.toInt().toString()
                                            y = selectedY.toInt().toString()
                                        } ?: run {
                                            Log.e("DEBUG", "无法获取 MainActivity 实例")
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("定位点A", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            // 终点坐标
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = x1, onValueChange = { x1 = it }, label = { Text("终点 X1") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = y1, onValueChange = { y1 = it }, label = { Text("终点 Y1") }, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { 
                                        activity?.startLocationPicker("B") { selectedX, selectedY ->
                                            x1 = selectedX.toInt().toString()
                                            y1 = selectedY.toInt().toString()
                                        } ?: run {
                                            Log.e("DEBUG", "无法获取 MainActivity 实例")
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("定位点B", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            // 持续时间
                            OutlinedTextField(
                                value = time, 
                                onValueChange = { time = it }, 
                                label = { Text("手势滑动/点击持续耗时 (毫秒 ms)") }, 
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            // 数据联动传递
                            LaunchedEffect(x, y, x1, y1, time) {
                                line.args = "$x,$y,$x1,$y1,$time"
                            }
                        }
                    }

                    "sleep", "settext", "adbcmd", "ftoast", "restart" -> {
                        var singleExpr by remember { mutableStateOf(line.args) }
                        val fieldLabel = when (cmd) {
                            "sleep" -> "延迟时长 (单位:毫秒)"
                            "settext" -> "注入的目标文本表达式 (文本需携带双引号)"
                            "adbcmd" -> "执行的 ADB Shell 命令体"
                            "ftoast" -> "提示内容"
                            "restart" -> "新一轮脚本参数"
                            else -> "提示窗显示正文内容"
                        }
                        OutlinedTextField(
                            value = singleExpr,
                            onValueChange = { singleExpr = it; line.args = it },
                            label = { Text(fieldLabel) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        when (cmd) {
                            "restart" -> Text(text = "保留已有值(可在重新启动的脚本继续使用)，脚本立即跳回第一行重新开始。保留的变量不会被 .setVar(...) 影响。用逗号分隔，如 var1, var2, ...")
                            "adbcmd" -> Text(text = "通过Shizuku执行adb命令。例如 \"id\"")
                            "settext" -> Text(text = "输入文字，支持变量拼接。例如 \"当前任务:\" + \$ItemText")
                            "ftoast" -> Text(text = "在任意界面弹出指定内容的提示(Toast)。例如 \"结果: \" + val1")
                            else -> {}
                        }
                    }

                    "setvar", "addvar" -> {
                        var varName by remember { mutableStateOf(originalArgsList.getOrNull(0) ?: "") }
                        var varExpr by remember { mutableStateOf(originalArgsList.getOrNull(1) ?: "") }
                        
                        OutlinedTextField(
                            value = varName,
                            onValueChange = { varName = it; line.args = "$it,$varExpr" },
                            label = { Text("变量标识符 (Name)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = varExpr,
                            onValueChange = { varExpr = it; line.args = "$varName,$it" },
                            label = { Text(if (cmd == "setvar") "值" else "加数值 (递增量)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        when (cmd) {
                            "setvar" -> Text(text = "将没有值的指定变量(name)设置为某个值(expr)。例如 val1, 55")
                            "addvar" -> Text(text = "将已有数值类变量(name)增加某个数(num)。例如 val1, 11")
                            else -> {}
                        }
                    }
                    
                    "nextitem" -> {
                        Text(text = "移动至下一条指令，无需参数")
                    }

                    else -> {
                        // 备用默认单框
                        var defaultArgs by remember { mutableStateOf(line.args) }
                        OutlinedTextField(
                            value = defaultArgs,
                            onValueChange = { defaultArgs = it; line.args = it },
                            label = { Text("指令执行体参数") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(line.args.trim()) }) { Text("注入画布") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun CategorizedActionSelectDialog(onDismiss: () -> Unit, onActionChosen: (LineType, String, String) -> Unit) {
    var selectedCategory by remember { mutableStateOf("流控") }
    val categories = listOf("流控", "动作点击", "变量处理", "系统辅助")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择脚本逻辑块") }, text = { Row(modifier = Modifier.fillMaxWidth().height(300.dp)) { LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight()) { itemsIndexed(categories) { _, cat -> TextButton(onClick = { selectedCategory = cat }, colors = ButtonDefaults.textButtonColors(containerColor = if (selectedCategory == cat) MaterialTheme.colorScheme.primaryContainer else Color.Transparent), modifier = Modifier.fillMaxWidth()) { Text(cat) } } }; VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp)); LazyColumn(modifier = Modifier.weight(2f).fillMaxHeight()) { when (selectedCategory) { "流控" -> { item { DetailActionItem("如果 (.if)", "声明条件控制逻辑起点") { onActionChosen(LineType.IF, "if", "") } }; item { DetailActionItem("那么 (.then)", "挂载逻辑成立时的执行动作体") { onActionChosen(LineType.THEN, "then", "") } }; item { DetailActionItem("否则 (.else)", "挂载非成立状况下的备用执行体") { onActionChosen(LineType.ELSE, "else", "") } }; item { DetailActionItem("重执行 (.reStart)", "重置主任务循环线") { onActionChosen(LineType.ACTION, "restart", "") } } }; "动作点击" -> { item { DetailActionItem("屏幕动作 (.exeAction)", "点击/滑动特定坐标映射") { onActionChosen(LineType.ACTION, "exeAction", "0,0,0,0,500") } }; item { DetailActionItem("线程挂起 (.sleep)", "静默阻塞等待延迟(ms)") { onActionChosen(LineType.ACTION, "sleep", "1000") } } }; "变量处理" -> { item { DetailActionItem("变量初始化 (.setVar)", "声明运行时局部变量") { onActionChosen(LineType.ACTION, "setVar", "name, expr") } }; item { DetailActionItem("变量自增 (.addVar)", "数学递进累加计算") { onActionChosen(LineType.ACTION, "addVar", "name, 1") } } }; "系统辅助" -> { item { DetailActionItem("全局轻提示 (.fToast)", "分发Toast通知提示") { onActionChosen(LineType.ACTION, "fToast", "\"执行完成\"") } }; item { DetailActionItem("推进队列 (.nextItem)", "推进至下一条指令") { onActionChosen(LineType.ACTION, "nextitem", "") } }; item { DetailActionItem("执行命令 (.adbCmd)", "通过Shizuku执行adb命令") { onActionChosen(LineType.ACTION, "adbcmd", "") } }; } } } } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ImportScriptDialog(onDismiss: () -> Unit, onConfirmImport: (String) -> Unit) {
    var rawInputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument(), onResult = { uri -> uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> rawInputText = reader.readText() } } catch (_: Exception) {} } })
    AlertDialog(onDismissRequest = onDismiss, title = { Text("载入外部脚本文本") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Text("直接输入或选择文件导入：", style = MaterialTheme.typography.bodySmall); Spacer(modifier = Modifier.weight(1f)); Button(onClick = { filePickerLauncher.launch(arrayOf("text/plain")) }) { Icon(Icons.Default.Folder, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("物理文件") } }; OutlinedTextField(value = rawInputText, onValueChange = { rawInputText = it }, label = { Text("代码流正文") }, modifier = Modifier.fillMaxWidth().height(180.dp)) } }, confirmButton = { TextButton(onClick = { if (rawInputText.isNotBlank()) onConfirmImport(rawInputText) }) { Text("解析") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun DetailActionItem(title: String, description: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Bold) }, supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) })
    }
}

// --- 升级文本导入：提取并注入每行尾部的 // 说明内容 ---
fun parseTextToTree(text: String): List<EditorLine> {
    val lines = text.split("\n")
    val rootList = mutableStateListOf<EditorLine>()
    class IfScopeFrame(val parentTargetList: MutableList<EditorLine>, val thenNode: EditorLine, var elseNode: EditorLine? = null)
    val scopeStack = Stack<IfScopeFrame>()
    var currentActiveList: MutableList<EditorLine> = rootList

    for (rawLine in lines) {
        val commentIndex = rawLine.indexOf("//")
        // 分离真实逻辑和右侧备注注释
        val description = if (commentIndex != -1) rawLine.substring(commentIndex + 2).trim() else ""
        val lineCodeOnly = if (commentIndex != -1) rawLine.substring(0, commentIndex) else rawLine
        
        val trimmed = lineCodeOnly.trim()
        if (!trimmed.startsWith(".")) continue
        val cmdName = trimmed.substringBefore("(").substringBefore(" ").removePrefix(".")    
        val rawArgs = if (trimmed.contains("(")) trimmed.substringAfter("(").substringBeforeLast(")") else ""
        when (cmdName.lowercase()) {
            "if" -> {
                val ifNode = EditorLine(type = LineType.IF, initialCommandName = "if", initialDescription = description)
                parseArgsToConditions(rawArgs).forEach { rule -> ifNode.children.add(EditorLine(type = LineType.CONDITION, initialCommandName = "cond", initialConditionRule = rule)) }
                val thenNode = EditorLine(type = LineType.THEN, initialCommandName = "then")
                currentActiveList.add(ifNode)
                currentActiveList.add(thenNode)
                scopeStack.push(IfScopeFrame(parentTargetList = currentActiveList, thenNode = thenNode))
                currentActiveList = thenNode.children
            }
            "else" -> {
                if (scopeStack.isNotEmpty()) {
                    val frame = scopeStack.peek()
                    val elseNode = EditorLine(type = LineType.ELSE, initialCommandName = "else", initialDescription = description)
                    frame.elseNode = elseNode
                    frame.parentTargetList.add(elseNode) 
                    currentActiveList = elseNode.children
                }
            }
            "end" -> {
                if (scopeStack.isNotEmpty()) {
                    scopeStack.pop()
                    currentActiveList = if (scopeStack.isNotEmpty()) {
                        val topFrame = scopeStack.peek()
                        if (topFrame.elseNode != null) topFrame.elseNode!!.children else topFrame.thenNode.children
                    } else { rootList }
                }
            }
            "then" -> { } 
            else -> {
                val actionNode = EditorLine(type = LineType.ACTION, initialCommandName = cmdName, initialArgs = rawArgs, initialDescription = description)
                currentActiveList.add(actionNode)
            }
        }
    }
    return rootList
}

// --- 升级树图导出：为含有说明的行自动拼接并追加 " //说明" ---
fun parseTreeToText(rootLines: List<EditorLine>): String {
    val sb = StringBuilder()
    
    fun inlineComment(desc: String): String {
        return if (desc.isNotBlank()) " // $desc" else ""
    }

    fun exportList(lines: List<EditorLine>) {
        var i = 0
        while (i < lines.size) {
            val node = lines[i]
            when (node.type) {
                LineType.IF -> {
                    if (i + 1 < lines.size && lines[i + 1].type == LineType.THEN) {
                        val ifNode = node
                        val thenNode = lines[i + 1]
                        val condArgs = buildConditionsToArgs(ifNode.children.mapNotNull { it.conditionRule })
                        sb.append(".if($condArgs)${inlineComment(ifNode.description)}\n")
                        exportList(thenNode.children)
                        if (i + 2 < lines.size && lines[i + 2].type == LineType.ELSE) {
                            val elseNode = lines[i + 2]
                            sb.append(".else${inlineComment(elseNode.description)}\n")
                            exportList(elseNode.children)
                            i += 3
                        } else { i += 2 }
                        sb.append(".end\n")
                    } else { i++ }
                }
                LineType.ACTION -> {
                    sb.append(".${node.commandName}(${node.args})${inlineComment(node.description)}\n")
                    i++
                }
                else -> { i++ }
            }
        }
    }
    exportList(rootLines)
    return sb.toString()
}

fun parseArgsToConditions(args: String): List<ConditionRule> {
    val rules = mutableListOf<ConditionRule>()
    if (args.isBlank()) return rules
    val parts = args.split(Regex("(?<=\\s(&&|\\|\\|)\\s)|(?=\\s(&&|\\|\\|)\\s)"))
    var currentRelation = "&&"
    for (part in parts) {
        val t = part.trim()
        if (t == "&&" || t == "||") { currentRelation = t; continue }
        if (t.isEmpty()) continue
        rules.add(ConditionRule(relation = currentRelation, expression = t))
    }
    return rules
}

fun buildConditionsToArgs(rules: List<ConditionRule>): String {
    return buildString {
        rules.forEachIndexed { index, rule ->
            if (index > 0) append(" ${rule.relation} ")
            append(rule.expression)
        }
    }
}

fun Context.findActivity(): MainActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is MainActivity) return context
        context = context.baseContext
    }
    return null
}