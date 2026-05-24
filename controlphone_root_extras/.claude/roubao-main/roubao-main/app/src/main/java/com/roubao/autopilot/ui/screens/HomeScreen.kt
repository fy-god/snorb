package com.roubao.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.agent.AgentState
import com.roubao.autopilot.ui.theme.BaoziTheme
import com.roubao.autopilot.ui.theme.Primary
import com.roubao.autopilot.ui.theme.Secondary

/**
 * 预设命令
 */
data class PresetCommand(
    val icon: String,
    val title: String,
    val command: String
)

val presetCommands = listOf(
    PresetCommand("🍔", "点汉堡", "帮我点个附近好吃的汉堡"),
    PresetCommand("📕", "发小红书", "帮我发一条小红书，内容是今日份好心情"),
    PresetCommand("📺", "刷B站", "打开B站搜索肉包，找到第一个视频点个赞"),
    PresetCommand("✈️", "旅游攻略", "用小美帮我查一下三亚旅游攻略"),
    PresetCommand("🎵", "听音乐", "打开网易云音乐播放每日推荐"),
    PresetCommand("🛒", "点外卖", "帮我在美团点一份猪脚饭")
)

/** 技能提示 (触发词 → 描述) */
data class SkillHint(
    val trigger: String,
    val description: String
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    agentState: AgentState?,
    logs: List<String>,
    onExecute: (String) -> Unit,
    onStop: () -> Unit,
    shizukuAvailable: Boolean,
    currentModel: String = "",
    onRefreshShizuku: () -> Unit = {},
    onShizukuRequired: () -> Unit = {},
    isExecuting: Boolean = false,
    onSaveWorkflow: (() -> Unit)? = null,
    skillHints: List<SkillHint> = emptyList()
) {
    val colors = BaoziTheme.colors
    var inputText by remember { mutableStateOf("") }
    // 使用 isExecuting 或 agentState?.isRunning 来判断是否运行中
    val isRunning = isExecuting || agentState?.isRunning == true
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 记录上一次的运行状态，用于检测任务结束
    var wasRunning by remember { mutableStateOf(false) }

    // 任务结束时保留输入框用于命名 Workflow
    var showSaveWorkflow by remember { mutableStateOf(false) }
    LaunchedEffect(isRunning) {
        if (wasRunning && !isRunning) {
            // 从运行中变为未运行 → 显示保存按钮
            showSaveWorkflow = true
            inputText = agentState?.instruction ?: ""
        }
        wasRunning = isRunning
    }

    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        // 顶部标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "肉包",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Text(
                        text = if (shizukuAvailable) "准备就绪，告诉我你想做什么" else "请先连接 Shizuku",
                        fontSize = 14.sp,
                        color = if (shizukuAvailable) colors.textSecondary else colors.error
                    )
                }

                // 未连接时显示刷新按钮
                if (!shizukuAvailable) {
                    IconButton(
                        onClick = onRefreshShizuku,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.backgroundCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新 Shizuku 状态",
                            tint = colors.primary
                        )
                    }
                }
            }
        }

        // 内容区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (isRunning || logs.isNotEmpty()) {
                // 执行中或有日志时显示日志
                ExecutionLogView(
                    logs = logs,
                    isRunning = isRunning,
                    currentStep = agentState?.currentStep ?: 0,
                    currentModel = currentModel,
                    listState = listState,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 空闲时显示预设命令 + 技能提示
                PresetCommandsView(
                    onCommandClick = { command ->
                        if (shizukuAvailable) {
                            inputText = command
                        } else {
                            onShizukuRequired()
                        }
                    },
                    skillHints = skillHints,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Workflow 保存栏 (任务完成后显示)
        if (showSaveWorkflow && onSaveWorkflow != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已执行 ${agentState?.lastActions?.size ?: 0} 步",
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    showSaveWorkflow = false
                    inputText = ""
                }) {
                    Text("忽略", fontSize = 13.sp, color = colors.textHint)
                }
                Button(
                    onClick = {
                        showSaveWorkflow = false
                        onSaveWorkflow()
                        inputText = ""
                    },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("\u4fdd\u5b58\u4e3a Skill", fontSize = 13.sp)
                }
            }
        }

        // 底部输入区域
        InputArea(
            inputText = inputText,
            onInputChange = { inputText = it },
            onExecute = {
                if (inputText.isNotBlank()) {
                    // 收起键盘并清除焦点
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    showSaveWorkflow = false
                    onExecute(inputText)
                }
            },
            onStop = {
                // 停止任务并清空输入框
                inputText = ""
                showSaveWorkflow = false
                onStop()
            },
            isRunning = isRunning,
            enabled = shizukuAvailable,
            onInputClick = {
                if (!shizukuAvailable) {
                    onShizukuRequired()
                }
            }
        )
    }
}

@Composable
fun PresetCommandsView(
    onCommandClick: (String) -> Unit,
    skillHints: List<SkillHint> = emptyList(),
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "试试这些指令",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        presetCommands.chunked(2).forEach { rowCommands ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowCommands.forEach { preset ->
                    PresetCommandCard(
                        preset = preset,
                        onClick = { onCommandClick(preset.command) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowCommands.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 技能提示区 — 显示已封装的 Fragment 模板
        if (skillHints.isNotEmpty()) {
            Text(
                text = "已封装技能 (零截图直达)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(skillHints) { hint ->
                    SkillChip(
                        hint = hint,
                        onClick = { onCommandClick(hint.trigger.replace("…", "")) },
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
fun SkillChip(
    hint: SkillHint,
    onClick: () -> Unit,
    colors: com.roubao.autopilot.ui.theme.BaoziColors
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .border(1.dp, colors.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = colors.primary.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = hint.description,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary
            )
            Text(
                text = "说「${hint.trigger}」",
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
fun PresetCommandCard(
    preset: PresetCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = preset.icon,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = preset.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = preset.command,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ExecutionLogView(
    logs: List<String>,
    isRunning: Boolean,
    currentStep: Int,
    currentModel: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 执行状态指示器
        if (isRunning) {
            ExecutingIndicator(currentStep = currentStep, currentModel = currentModel)
        }

        // 日志列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { log ->
                LogItem(log = log)
            }
        }
    }
}

@Composable
fun ExecutingIndicator(currentStep: Int, currentModel: String = "") {
    val colors = BaoziTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "executing")
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 动画圆点
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(Primary, Secondary, Primary)
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在执行 Step $currentStep",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.primary
                        )
                    }
                    // 显示当前模型
                    if (currentModel.isNotEmpty()) {
                        Text(
                            text = currentModel,
                            fontSize = 11.sp,
                            color = colors.textHint,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.backgroundInput)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Primary, Secondary)
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun LogItem(log: String) {
    val colors = BaoziTheme.colors
    val logColor = when {
        log.contains("❌") -> colors.error
        log.contains("✅") -> colors.success
        log.contains("📋") || log.contains("🎬") -> colors.secondary
        log.contains("Step") || log.contains("=====") -> colors.primary
        log.contains("⛔") -> colors.error
        else -> colors.textSecondary
    }

    Text(
        text = log,
        fontSize = 12.sp,
        color = logColor,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
fun InputArea(
    inputText: String,
    onInputChange: (String) -> Unit,
    onExecute: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    enabled: Boolean,
    onInputClick: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.backgroundCard,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isRunning) Arrangement.Center else Arrangement.Start
        ) {
            if (isRunning) {
                // 运行中只显示停止按钮
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "停止",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "停止执行",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // 非运行状态显示输入框和发送按钮
                // 输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.backgroundInput)
                        .then(
                            if (!enabled) {
                                Modifier.clickable { onInputClick() }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    if (enabled) {
                        // Shizuku 已连接，显示可编辑的输入框
                        BasicTextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (inputText.isEmpty()) {
                                        Text(
                                            text = "告诉肉包你想做什么...",
                                            color = colors.textHint,
                                            fontSize = 15.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    } else {
                        // Shizuku 未连接，显示提示文字
                        Text(
                            text = "请先连接 Shizuku",
                            color = colors.textHint,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 发送按钮
                IconButton(
                    onClick = onExecute,
                    enabled = enabled && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (inputText.isNotBlank() && enabled) colors.primary
                            else colors.backgroundInput
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
