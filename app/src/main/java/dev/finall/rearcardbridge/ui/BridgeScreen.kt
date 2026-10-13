package dev.finall.rearcardbridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.ShizukuStatus
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
    onPickPackage: () -> Unit,
    onSelectCard: (String) -> Unit,
    onInstallCarrier: () -> Unit,
    onRestore: (String) -> Unit,
    onOpenRearSettings: (String?) -> Unit,
    onDismissError: () -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    var showSetup by remember(state.activeGroupId, state.boundCardId) { mutableStateOf(state.boundCardId == null) }
    var showEntries by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val ready = state.shizuku.status == ShizukuStatus.READY
    val selected = state.cards.firstOrNull { it.id == state.selectedCardId }
    val themeController = remember { ThemeController() }
    MiuixTheme(controller = themeController) {
        Scaffold(topBar = { TopAppBar(title = "背屏卡片桥") }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = "内容分组", summary = "来源 App / key → 目标卡片；各组独立更新")
                        state.groups.forEach { group ->
                            BasicComponent(title = group.sourcePackage,
                                summary = "${group.key} → ${group.cardTitle ?: "待选择目标"}",
                                endActions = { RadioButton(selected = group.id == state.activeGroupId, onClick = null) },
                                onClick = { onSelectGroup(group.id) }, enabled = !state.busy)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = "当前内容", summary = state.packageName?.let {
                            "$it · 版本 ${state.contentRevision}"
                        } ?: "尚未导入内容")
                        if (state.packageName != null) {
                            Text("已导入并保存，无需再次导入。",
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                        }
                        Button(onClick = onPickPackage,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            colors = ButtonDefaults.buttonColorsPrimary(), enabled = !state.busy) {
                            Text("选择 ZIP 导入")
                        }
                        if (state.packageEntries.isNotEmpty()) {
                            BasicComponent(title = "包内文件", summary = "${state.packageEntries.size} 个文件",
                                onClick = { showEntries = !showEntries })
                            if (showEntries) Text(state.packageEntries.joinToString("\n"),
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = state.boundCardTitle?.let { "载体：$it" } ?: "尚未绑定载体",
                            summary = state.boundCardId,
                            onClick = { showSetup = !showSetup })
                        Button(onClick = { showSetup = !showSetup },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            enabled = !state.busy) { Text(if (showSetup) "收起载体设置" else "配置载体") }
                    }
                }
                if (showSetup) {
                    item {
                        Text("载体安装 · 首次设置", style = MiuixTheme.textStyles.title4)
                        Spacer(Modifier.height(8.dp))
                        Card(Modifier.fillMaxWidth()) {
                            BasicComponent(title = when (state.shizuku.status) {
                                ShizukuStatus.NOT_RUNNING -> "Shizuku 未运行"
                                ShizukuStatus.AUTH_REQUIRED -> "需要 Shizuku 授权"
                                ShizukuStatus.CONNECTING -> "正在连接 Shizuku"
                                ShizukuStatus.READY -> "Shizuku 已连接"
                                ShizukuStatus.ERROR -> "Shizuku 连接失败"
                            }, summary = state.shizuku.message.takeIf { it.isNotBlank() })
                            Button(onClick = if (ready) onRefresh else onAuthorize,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                                enabled = !state.busy) { Text(if (ready) "刷新载体资源" else "授权 / 连接") }
                        }
                    }
                    if (state.cards.isEmpty()) item {
                        Text(if (ready) "暂无载体资源。" else "连接 Shizuku 后选择载体。")
                    }
                    items(state.cards, key = { it.id }) { card ->
                        Card(Modifier.fillMaxWidth()) {
                            BasicComponent(title = card.title,
                                summary = if (card.canReplace) card.id else "只读 · ${card.id}",
                                startAction = card.preview?.let { bitmap ->
                                    { Image(bitmap.asImageBitmap(), card.title, Modifier.size(56.dp)) }
                                },
                                endActions = { RadioButton(selected = card.id == state.selectedCardId, onClick = null) },
                                onClick = { onSelectCard(card.id) }, role = Role.RadioButton,
                                enabled = ready && card.canReplace && !state.busy)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { confirmInstall = true }, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                enabled = ready && !state.busy && selected?.canReplace == true) {
                                Text("安装并绑定动态载体")
                            }
                            Button(onClick = { onOpenRearSettings(selected?.id ?: state.boundCardId) },
                                modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("打开背屏设置") }
                            if (selected?.hasBackup == true) Button(onClick = { confirmRestore = true },
                                modifier = Modifier.fillMaxWidth(), enabled = ready && !state.busy) {
                                Text("恢复原始资源")
                            }
                        }
                    }
                }
                item { Text(if (state.busy) "正在处理…" else state.status) }
            }
        }
        WindowDialog(show = confirmInstall, title = "绑定「${selected?.title.orEmpty()}」？",
            summary = "备份并写入固定载体包，首次在系统里重新应用这张卡。",
            onDismissRequest = { confirmInstall = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmInstall = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmInstall = false; onInstallCarrier() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(), enabled = !state.busy) { Text("安装") }
            }
        }
        WindowDialog(show = confirmRestore, title = "恢复原始资源？",
            summary = "用首次备份恢复所选卡片资源。", onDismissRequest = { confirmRestore = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmRestore = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { selected?.let { onRestore(it.id) }; confirmRestore = false },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !state.busy) { Text("恢复") }
            }
        }
        WindowDialog(show = state.error != null, title = "操作提示", summary = state.error,
            onDismissRequest = onDismissError) {
            Button(onClick = onDismissError, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
        }
    }
}
