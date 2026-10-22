package dev.finall.rearcardbridge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.finall.rearcardbridge.BuildConfig
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.InstallMode
import dev.finall.rearcardbridge.model.ModuleInfo
import dev.finall.rearcardbridge.model.ShizukuStatus
import dev.finall.rearcardbridge.store.CatalogModule
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.DateFormat
import java.util.Date

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onInstallModuleZip: () -> Unit,
    onInstallModuleGit: (String, String?) -> Unit,
    onRefreshShop: () -> Unit,
    onInstallShopModule: (CatalogModule) -> Unit,
    onConfirmModuleDowngrade: () -> Unit,
    onDismissModuleDowngrade: () -> Unit,
    onSelectModule: (String) -> Unit,
    onUninstallModule: (String) -> Unit,
    onUpdateModule: (String) -> Unit,
    onToggleBackgroundRefresh: () -> Unit,
    onSetBackgroundRefreshInterval: (String, Int) -> Unit,
    onInstallStandalone: (String) -> Unit,
    onUpdateStandalone: (String) -> Unit,
    onInstallManagerCard: () -> Unit,
    onSetInstallMode: (InstallMode) -> Unit,
    onCancelInstallTask: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav = rememberNavController<BridgeRoute>(BridgeRoute.Root)
    var handledInstall by rememberSaveable { mutableLongStateOf(0L) }
    var showGit by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    val topRoute = nav.backStack.last()
    val moduleId = when (topRoute) {
        is BridgeRoute.ModuleDetail -> topRoute.moduleId
        is BridgeRoute.ModuleSettings -> topRoute.moduleId
        else -> null
    }
    val module = state.modules.firstOrNull { it.id == moduleId }
    val ready = state.shizuku.status == ShizukuStatus.READY

    LaunchedEffect(tab) {
        if (tab == 1 && !state.shop.loaded && !state.shop.loading) onRefreshShop()
    }
    LaunchedEffect(state.moduleInstallEvent) {
        if (state.moduleInstallEvent > handledInstall) {
            handledInstall = state.moduleInstallEvent
            state.activeModuleId?.let { id ->
                tab = 0
                nav.popUntil { it == BridgeRoute.Root }
                nav.push(BridgeRoute.ModuleDetail(id))
            }
        }
    }
    // Restored routes wait for the installed modules to load before checking their IDs.
    LaunchedEffect(moduleId, state.modulesLoaded, state.modules, state.busy, state.activeModuleId) {
        if (moduleId != null && state.modulesLoaded && !state.busy) {
            if (module == null) nav.popUntil { it == BridgeRoute.Root }
            else if (state.activeModuleId != moduleId) onSelectModule(moduleId)
        }
    }
    val back: () -> Unit = { nav.pop() }
    val theme = remember { ThemeController() }
    MiuixTheme(controller = theme) {
        NavDisplay(navController = nav, modifier = Modifier.fillMaxSize()) {
            entry<BridgeRoute.Root> {
                Scaffold(
                    topBar = { TopAppBar(title = when (tab) {
                        1 -> "模块商店"
                        2 -> "设置与关于"
                        else -> "背屏卡片桥"
                    }) },
                    bottomBar = {
                        FloatingNavigationBar {
                            FloatingNavigationBarItem(selected = tab == 0,
                                onClick = { tab = 0 }, icon = MiuixIcons.Home, label = "主页")
                            FloatingNavigationBarItem(selected = tab == 1,
                                onClick = { tab = 1 }, icon = MiuixIcons.Store, label = "商店")
                            FloatingNavigationBarItem(selected = tab == 2,
                                onClick = { tab = 2 }, icon = MiuixIcons.Settings, label = "设置与关于")
                        }
                    },
                ) { padding ->
                    val layoutDirection = LocalLayoutDirection.current
                    // The navigation bar overlays the list; only its scrollable tail reserves space.
                    val listPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp)
                    Box(Modifier.fillMaxSize().padding(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection))) {
                        when (tab) {
                            1 -> ModuleShopPage(shop = state.shop, installedModules = state.modules,
                                modulesLoaded = state.modulesLoaded, busy = state.busy,
                                onRefresh = onRefreshShop, onInstall = onInstallShopModule,
                                onOpenModule = { id ->
                                    tab = 0
                                    nav.push(BridgeRoute.ModuleDetail(id))
                                }, contentPadding = listPadding)
                            2 -> SettingsPage(state, onAuthorize, onRefresh, onToggleBackgroundRefresh,
                                onSetInstallMode, onInstallManagerCard,
                                contentPadding = listPadding)
                            else -> ModuleListPage(state, onInstallModuleZip, contentPadding = listPadding,
                                onInstallGit = { showGit = true }, onOpenModule = { id ->
                                    nav.push(BridgeRoute.ModuleDetail(id))
                                })
                        }
                    }
                }
            }
            entry<BridgeRoute.ModuleDetail> { route ->
                val detail = state.modules.firstOrNull { it.id == route.moduleId }
                ModulePageScaffold(detail?.name ?: "模块详情", back) {
                    if (detail == null || state.activeModuleId != route.moduleId) ModuleLoadingPage()
                    else ModuleDetailPage(state, detail,
                        onOpenSettings = { nav.push(BridgeRoute.ModuleSettings(route.moduleId)) },
                        onUpdate = { onUpdateModule(route.moduleId) },
                        onUninstall = { confirmUninstall = true },
                        onSetBackgroundRefreshInterval = onSetBackgroundRefreshInterval,
                        onInstallStandalone = { onInstallStandalone(route.moduleId) },
                        onUpdateStandalone = { onUpdateStandalone(route.moduleId) },
                        onCancelInstallTask = onCancelInstallTask)
                }
            }
            entry<BridgeRoute.ModuleSettings> { route ->
                ModuleSettingsPage(moduleId = route.moduleId, onBack = back)
            }
        }

        WindowDialog(show = showGit, title = "仓库安装",
            summary = "公开的 GitHub、GitLab 或 Gitee 仓库，根目录需包含 module.json。",
            onDismissRequest = { showGit = false }) {
            val url = rememberTextFieldState()
            val branch = rememberTextFieldState()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(state = url, label = "仓库 URL", useLabelAsPlaceholder = true)
                TextField(state = branch, label = "分支（默认 main）", useLabelAsPlaceholder = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showGit = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
                        showGit = false
                        onInstallModuleGit(url.text.toString(), branch.text.toString().ifBlank { null })
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                        enabled = !state.busy && url.text.isNotBlank()) { Text("安装") }
                }
            }
        }
        WindowDialog(show = confirmUninstall, title = "卸载模块并删除背屏卡？",
            summary = "删除模块及其配置、业务数据，并按当前授权模式移除背屏上的对应卡片。",
            onDismissRequest = { confirmUninstall = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmUninstall = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmUninstall = false; module?.id?.let(onUninstallModule) },
                    modifier = Modifier.weight(1f), enabled = !state.busy,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("卸载") }
            }
        }
        WindowDialog(show = state.moduleDowngradeWarning != null, title = "安装旧版本？",
            summary = state.moduleDowngradeWarning,
            onDismissRequest = { if (!state.busy) onDismissModuleDowngrade() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismissModuleDowngrade, modifier = Modifier.weight(1f),
                    enabled = !state.busy) { Text("取消") }
                Button(onClick = onConfirmModuleDowngrade, modifier = Modifier.weight(1f),
                    enabled = !state.busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text("继续安装") }
            }
        }
        WindowDialog(show = state.error != null, title = "操作提示", summary = state.error,
            onDismissRequest = onDismissError) {
            Button(onClick = onDismissError, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
        }
    }
}

@Composable
private fun ModulePageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        SmallTopAppBar(title = title, navigationIcon = {
            IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

@Composable
private fun ModuleLoadingPage() {
    Card(Modifier.padding(16.dp).fillMaxWidth()) { BasicComponent(title = "正在读取模块…") }
}

@Composable
private fun ModuleListPage(state: BridgeUiState, onInstallZip: () -> Unit,
    onInstallGit: () -> Unit, onOpenModule: (String) -> Unit, contentPadding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onInstallZip, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(), enabled = !state.busy) {
                    Text("导入 ZIP", maxLines = 1, style = MiuixTheme.textStyles.button,
                        fontWeight = FontWeight.Medium)
                }
                Button(onClick = onInstallGit, modifier = Modifier.weight(1f), enabled = !state.busy) {
                    Text("仓库安装", maxLines = 1, style = MiuixTheme.textStyles.button,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
        if (state.modules.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "还没有卡片模块", summary = "导入模块 ZIP 后，选择一张本地卡片作为替换目标。",
                    startAction = { Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(32.dp)) })
            }
        }
        items(state.modules, key = { it.id }) { module ->
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = module.name,
                    summary = module.version,
                    startAction = { Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(36.dp)) },
                    endActions = {
                        if (module.cardNeedsUpdate == true) UpdateDot()
                        ForwardIcon()
                    }, enabled = !state.busy,
                    onClick = { onOpenModule(module.id) })
            }
        }
        if (state.busy) item {
            Card(Modifier.fillMaxWidth()) { BasicComponent(title = "正在处理…", summary = "请稍候") }
        }
    }
}

@Composable
private fun ModuleDetailPage(state: BridgeUiState, module: ModuleInfo,
    onOpenSettings: () -> Unit, onUpdate: () -> Unit, onUninstall: () -> Unit,
    onSetBackgroundRefreshInterval: (String, Int) -> Unit,
    onInstallStandalone: () -> Unit, onUpdateStandalone: () -> Unit,
    onCancelInstallTask: (String) -> Unit) {
    val intervalMinutes = module.backgroundIntervalMinutes
    var showIntervalDialog by remember { mutableStateOf(false) }
    val intervalField = rememberTextFieldState()
    val rawInterval = intervalField.text.toString().trim()
    val parsedInterval = rawInterval.toIntOrNull()
    val intervalValid = parsedInterval != null && parsedInterval >= 5
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (module.cardInstalled && module.cardNeedsUpdate == true) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = "卡片资源有更新",
                        summary = "静态资源有变化，更新背屏上的对应卡片内容",
                        endActions = { ForwardIcon() }, onClick = onUpdateStandalone,
                        enabled = !state.busy)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = module.name, summary = "${module.version} · ${module.author}",
                    startAction = { Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(40.dp)) })
                if (module.description.isNotBlank()) BasicComponent(title = "介绍", summary = module.description)
                BasicComponent(title = "使用能力", summary = module.capabilities.joinToString("、") {
                    when (it) { "network" -> "联网"; "storage" -> "存储"; "files" -> "文件"; else -> it }
                }.ifBlank { "无额外能力" })
                if (intervalMinutes == null) {
                    BasicComponent(title = "后台刷新", summary = "模块未提供后台刷新入口")
                } else {
                    BasicComponent(title = "刷新间隔", summary = "每 $intervalMinutes 分钟 · 点按修改（最短 5 分钟）",
                        endActions = { ForwardIcon() }, enabled = !state.busy, onClick = {
                            intervalField.setTextAndPlaceCursorAtEnd(intervalMinutes.toString())
                            showIntervalDialog = true
                        })
                    if (state.backgroundRefreshEnabled) {
                        val now = System.currentTimeMillis()
                        BasicComponent(title = "上次刷新", summary = module.backgroundLastRefreshAt?.let {
                            "上次成功：${formatRefreshTime(it)}"
                        } ?: "尚未完成首次刷新")
                        BasicComponent(title = "下次计划", summary = when {
                            module.backgroundNextRefreshAt == null -> "等待首次刷新"
                            module.backgroundNextRefreshAt <= now -> "已到期，等待系统安排"
                            else -> formatRefreshTime(module.backgroundNextRefreshAt)
                        })
                    } else {
                        BasicComponent(title = "刷新状态", summary = "后台刷新未开启")
                    }
                    if (!module.backgroundRefreshError.isNullOrBlank()) {
                        BasicComponent(title = "最近失败", summary = module.backgroundRefreshError)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                if (module.hasWebui) BasicComponent(title = "模块设置", summary = "打开模块自带的设置网页",
                    endActions = { ForwardIcon() }, onClick = onOpenSettings, enabled = !state.busy)
                if (module.source?.contains('@') == true) BasicComponent(title = "检查更新", summary = module.source,
                    endActions = { ForwardIcon() }, onClick = onUpdate, enabled = !state.busy)
                BasicComponent(title = "卸载模块并删除背屏卡", summary = "删除模块及其数据，并移除背屏上的对应卡片",
                    endActions = { ForwardIcon() }, onClick = onUninstall, enabled = !state.busy)
            }
        }
        val moduleTasks = state.installTasks.filter { it.groupId == module.groupId }
        val pendingTask = moduleTasks.firstOrNull {
            it.state == dev.finall.rearcardbridge.module.install.InstallTaskState.PENDING ||
                it.state == dev.finall.rearcardbridge.module.install.InstallTaskState.CLAIMED
        }
        if (!module.cardInstalled || pendingTask != null) {
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "独立背屏卡",
                    summary = if (module.legacyCardId != null)
                        "该模块此前写在载体「${module.legacyCardTitle ?: module.legacyCardId}」上"
                    else "把模块直接插入为一张新卡，不占用现有载体")
                if (pendingTask == null) {
                    if (!module.cardInstalled) {
                        BasicComponent(
                            title = if (module.legacyCardId != null) "迁移为独立背屏卡" else "安装为新卡",
                            summary = when (state.installMode) {
                                InstallMode.ROOT -> "root 直连写入，原载体将自动恢复"
                                InstallMode.SHIZUKU -> "暂存资源后由背屏管理器卡插入，原载体将自动恢复"
                            },
                            endActions = { ForwardIcon() }, onClick = onInstallStandalone,
                            enabled = !state.busy)
                    } else {
                        BasicComponent(title = "已安装为独立背屏卡",
                            summary = "脚本与内容变化由卡片自动更新，无需重新安装")
                    }
                } else {
                    BasicComponent(title = "安装任务进行中",
                        summary = when (pendingTask.state) {
                            dev.finall.rearcardbridge.module.install.InstallTaskState.PENDING ->
                                "已暂存。请打开背屏「RearCard 安装管理器」卡片完成插入"
                            dev.finall.rearcardbridge.module.install.InstallTaskState.CLAIMED ->
                                "中转卡正在执行安装"
                            else -> pendingTask.state.name
                        })
                    BasicComponent(title = "取消安装任务",
                        endActions = { ForwardIcon() },
                        onClick = { onCancelInstallTask(pendingTask.taskId) },
                        enabled = !state.busy)
                }
            }
        }
        }
    }
    WindowDialog(show = showIntervalDialog, title = "后台刷新间隔",
        summary = "单位为分钟，最短 5 分钟。由系统的延迟任务安排执行，省电模式下可能延迟。",
        onDismissRequest = { showIntervalDialog = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = intervalField, label = "间隔（分钟）", useLabelAsPlaceholder = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (rawInterval.isNotEmpty() && !intervalValid) {
                Text(text = if (parsedInterval == null) "请输入整数分钟" else "最短 5 分钟",
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { showIntervalDialog = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    showIntervalDialog = false
                    parsedInterval?.let { onSetBackgroundRefreshInterval(module.id, it) }
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = intervalValid && !state.busy) { Text("保存") }
            }
        }
    }
}

private fun formatRefreshTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

@Composable
private fun SettingsPage(state: BridgeUiState, onAuthorize: () -> Unit, onRefresh: () -> Unit,
    onToggleBackgroundRefresh: () -> Unit,
    onSetInstallMode: (InstallMode) -> Unit, onInstallManagerCard: () -> Unit,
    contentPadding: PaddingValues) {
    val context = LocalContext.current
    val ready = state.shizuku.status == ShizukuStatus.READY
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "安装授权模式", summary = "决定背屏卡如何写入系统")
                BasicComponent(title = "root 直装",
                    summary = "通过 KernelSU 在 App 内直接执行插入与移除",
                    endActions = { RadioDot(selected = state.installMode == InstallMode.ROOT) },
                    onClick = { onSetInstallMode(InstallMode.ROOT) },
                    enabled = !state.busy)
                BasicComponent(title = "Shizuku + 管理器卡",
                    summary = "资源经 Shizuku 暂存，由背屏管理器卡在系统侧完成插入",
                    endActions = { RadioDot(selected = state.installMode == InstallMode.SHIZUKU) },
                    onClick = { onSetInstallMode(InstallMode.SHIZUKU) },
                    enabled = !state.busy)
            }
        }
        if (state.installMode == InstallMode.SHIZUKU) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = "安装管理器卡到背屏",
                        summary = if (state.managerCardInstalled)
                            "已安装；打开背屏上的「RearCard 安装管理器」即可执行队列中的安装"
                        else
                            "需要 root 一次性引导；安装后打开它即可在背屏执行安装队列",
                        endActions = { ForwardIcon() }, onClick = onInstallManagerCard,
                        enabled = !state.busy)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "Shizuku", summary = when (state.shizuku.status) {
                    ShizukuStatus.NOT_RUNNING -> "未运行，请先启动 Shizuku"
                    ShizukuStatus.AUTH_REQUIRED -> "未授权，无 root 暂存安装需要授权"
                    ShizukuStatus.CONNECTING -> "正在连接…"
                    ShizukuStatus.READY -> "已连接，无 root 时用于暂存安装包"
                    ShizukuStatus.ERROR -> state.shizuku.message.ifBlank { "连接失败" }
                })
                BasicComponent(title = "授权与连接",
                    summary = "仅无 root 设备的暂存安装需要 Shizuku",
                    endActions = { ForwardIcon() }, onClick = if (ready) onRefresh else onAuthorize,
                    enabled = !state.busy)
            }
        }
        item {
            Column {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = "后台刷新", summary = state.backgroundRefreshMessage,
                        modifier = Modifier.semantics {
                            toggleableState = ToggleableState(state.backgroundRefreshEnabled)
                        },
                        endActions = {
                            Switch(checked = state.backgroundRefreshEnabled, onCheckedChange = null)
                        },
                        onClick = onToggleBackgroundRefresh, role = Role.Switch)
                }
                Text(
                    text = "${state.modules.count { it.backgroundIntervalMinutes != null }} 个模块支持后台刷新，由系统的延迟任务按各自周期更新快照，不会唤醒背屏。" +
                        "各模块最短间隔 5 分钟，可在模块详情中调整；省电模式等系统调度可能延迟实际执行。开关设置会保留。",
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "背屏卡片桥", summary = "${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " · 独立测试版" else ""}")
                BasicComponent(title = "作者", summary = "finall1008", endActions = { ForwardIcon() },
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/finall1008"))) })
                BasicComponent(title = "项目仓库", summary = "finall1008/rear-card-bridge", endActions = { ForwardIcon() },
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/finall1008/rear-card-bridge"))) })
            }
        }
    }
}

@Composable
private fun ForwardIcon() {
    Icon(MiuixIcons.ChevronForward, contentDescription = null, modifier = Modifier.size(20.dp))
}


/** Small filled/outlined dot used for single-choice rows. */
@Composable
private fun RadioDot(selected: Boolean) {
    val color = if (selected) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(Modifier.size(20.dp)) {
        drawCircle(color, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        if (selected) drawCircle(color, radius = size.minDimension / 4)
    }
}


/** Small blue badge shown on modules whose installed card has pending static updates. */
@Composable
private fun UpdateDot() {
    Canvas(Modifier.padding(end = 8.dp).size(10.dp)) {
        drawCircle(Color(0xFF3482FF))
    }
}
