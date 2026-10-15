package dev.finall.rearcardbridge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.finall.rearcardbridge.BuildConfig
import dev.finall.rearcardbridge.live.CarrierResourceState
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.ModuleInfo
import dev.finall.rearcardbridge.model.ShizukuStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
    onSelectCard: (String) -> Unit,
    onInstallCarrier: () -> Unit,
    onRestore: (String) -> Unit,
    onOpenRearSettings: (String?) -> Unit,
    onDismissError: () -> Unit,
    onConfirmReapplied: () -> Unit,
    onInstallModuleZip: () -> Unit,
    onInstallModuleGit: (String, String?) -> Unit,
    onSelectModule: (String) -> Unit,
    onUninstallModule: (String) -> Unit,
    onUpdateModule: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav = rememberNavController<BridgeRoute>(BridgeRoute.Root)
    var handledInstall by rememberSaveable { mutableLongStateOf(0L) }
    var showGit by remember { mutableStateOf(false) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    val topRoute = nav.backStack.last()
    val moduleId = when (topRoute) {
        is BridgeRoute.ModuleDetail -> topRoute.moduleId
        is BridgeRoute.Targets -> topRoute.moduleId
        is BridgeRoute.ModuleSettings -> topRoute.moduleId
        else -> null
    }
    val module = state.modules.firstOrNull { it.id == moduleId }
    val ready = state.shizuku.status == ShizukuStatus.READY

    LaunchedEffect(state.moduleInstallEvent) {
        if (state.moduleInstallEvent > handledInstall) {
            handledInstall = state.moduleInstallEvent
            state.activeModuleId?.let { id ->
                tab = 0
                nav.popUntil { it == BridgeRoute.Root }
                nav.push(BridgeRoute.ModuleDetail(id))
                if (state.boundCardId == null) nav.push(BridgeRoute.Targets(id))
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
                    topBar = { TopAppBar(title = if (tab == 1) "设置与关于" else "背屏卡片桥") },
                    bottomBar = {
                        FloatingNavigationBar {
                            FloatingNavigationBarItem(selected = tab == 0,
                                onClick = { tab = 0 }, icon = MiuixIcons.Home, label = "主页")
                            FloatingNavigationBarItem(selected = tab == 1,
                                onClick = { tab = 1 }, icon = MiuixIcons.Settings, label = "设置与关于")
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (tab == 1) SettingsPage(state, onAuthorize, onRefresh)
                        else ModuleListPage(state, onInstallModuleZip,
                            onInstallGit = { showGit = true }, onOpenModule = { id ->
                                nav.push(BridgeRoute.ModuleDetail(id))
                            })
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
                        onPickTarget = { nav.push(BridgeRoute.Targets(route.moduleId)) },
                        onRestore = { confirmRestore = true },
                        onOpenRearSettings = { onOpenRearSettings(detail.boundCardId) },
                        onConfirmReapplied = onConfirmReapplied)
                }
            }
            entry<BridgeRoute.Targets> { route ->
                ModulePageScaffold("选择替换目标", back) {
                    if (state.activeModuleId != route.moduleId) ModuleLoadingPage()
                    else TargetPickerPage(state, ready, onSelectCard) { confirmWrite = true }
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
        WindowDialog(show = confirmWrite, title = "写入替换目标？",
            summary = "将「${state.cards.firstOrNull { it.id == state.selectedCardId }?.title.orEmpty()}」作为本模块载体。原始资源会备份；写入后仍需在系统里重新应用。",
            onDismissRequest = { confirmWrite = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmWrite = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmWrite = false; nav.pop(); onInstallCarrier() },
                    modifier = Modifier.weight(1f), enabled = !state.busy,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("写入") }
            }
        }
        WindowDialog(show = confirmRestore, title = "恢复原始资源？",
            summary = "恢复此目标的原始资源并解除模块绑定。之后请在系统里重新应用原卡片。",
            onDismissRequest = { confirmRestore = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmRestore = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmRestore = false; module?.boundCardId?.let(onRestore) },
                    modifier = Modifier.weight(1f), enabled = ready && !state.busy,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("恢复") }
            }
        }
        WindowDialog(show = confirmUninstall, title = "卸载模块？",
            summary = "删除模块及其配置、业务数据。已绑定的模块须先恢复原始资源。",
            onDismissRequest = { confirmUninstall = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirmUninstall = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmUninstall = false; module?.id?.let(onUninstallModule) },
                    modifier = Modifier.weight(1f), enabled = !state.busy,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("卸载") }
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
    onInstallGit: () -> Unit, onOpenModule: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
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
                    summary = "${module.version} · ${module.boundCardTitle?.let { "替换 $it" } ?: "待选择替换目标"}",
                    startAction = { Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(36.dp)) },
                    endActions = { ForwardIcon() }, enabled = !state.busy,
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
    onPickTarget: () -> Unit, onRestore: () -> Unit, onOpenRearSettings: () -> Unit,
    onConfirmReapplied: () -> Unit) {
    val resources = state.carrierResources
    val ready = state.shizuku.status == ShizukuStatus.READY
    val boundCard = state.cards.firstOrNull { it.id == module.boundCardId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = module.name, summary = "${module.version} · ${module.author}",
                    startAction = { Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(40.dp)) })
                if (module.description.isNotBlank()) BasicComponent(title = "介绍", summary = module.description)
                BasicComponent(title = "使用能力", summary = module.capabilities.joinToString("、") {
                    when (it) { "network" -> "联网"; "storage" -> "存储"; "files" -> "文件"; else -> it }
                }.ifBlank { "无额外能力" })
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                if (module.hasWebui) BasicComponent(title = "模块设置", summary = "打开模块自带的设置网页",
                    endActions = { ForwardIcon() }, onClick = onOpenSettings, enabled = !state.busy)
                if (module.source?.contains('@') == true) BasicComponent(title = "检查更新", summary = module.source,
                    endActions = { ForwardIcon() }, onClick = onUpdate, enabled = !state.busy)
                BasicComponent(title = "卸载模块", summary = "删除模块及其数据",
                    endActions = { ForwardIcon() }, onClick = onUninstall, enabled = !state.busy)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "替换目标", summary = module.boundCardTitle ?: "尚未选择",
                    startAction = boundCard?.preview?.let { bitmap -> {
                        Image(bitmap.asImageBitmap(), boundCard.title, Modifier.size(56.dp))
                    } })
                if (module.boundCardId == null) {
                    BasicComponent(title = "选择替换目标", summary = "预设卡片和自行生成的卡片均可使用",
                        endActions = { ForwardIcon() }, onClick = onPickTarget, enabled = !state.busy)
                } else {
                    if (resources != null) BasicComponent(title = "载体状态", summary = resources.message)
                    BasicComponent(title = "更新载体资源", summary = "写入当前模块的最新载体包",
                        endActions = { ForwardIcon() }, onClick = onPickTarget, enabled = !state.busy)
                    BasicComponent(title = "恢复原始资源", summary = "恢复后可以选择其他替换目标",
                        endActions = { ForwardIcon() }, onClick = onRestore,
                        enabled = !state.busy && ready && boundCard?.hasBackup == true)
                }
            }
        }
        if (resources?.state == CarrierResourceState.REAPPLY_REQUIRED) item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "最后一步：重新应用", summary = "请到背屏设置中移除旧卡，再从原入口重新应用。")
                BasicComponent(title = "打开背屏设置", endActions = { ForwardIcon() },
                    onClick = onOpenRearSettings, enabled = !state.busy)
                BasicComponent(title = "我已重新应用", summary = "记录你的手动确认", endActions = { ForwardIcon() },
                    onClick = onConfirmReapplied, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun TargetPickerPage(state: BridgeUiState, ready: Boolean,
    onSelectCard: (String) -> Unit, onWrite: () -> Unit) {
    val selected = state.cards.firstOrNull { it.id == state.selectedCardId }
    val occupied = state.groups.filter { it.id != state.activeGroupId }.mapNotNull { it.cardId }.toSet()
    val changingBound = state.boundCardId != null
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ready || state.cards.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = if (ready) "没有本地卡片资源" else "需要 Shizuku 授权",
                        summary = if (ready) "先在系统中添加一张卡片，再刷新列表。" else "请返回「设置与关于」完成授权。")
                }
            }
            items(state.cards, key = { it.id }) { card ->
                val taken = card.id in occupied
                val selectable = ready && card.canReplace && !taken &&
                    (!changingBound || card.id == state.boundCardId)
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = card.title,
                        summary = when {
                            taken -> "已被其他模块占用"
                            !card.canReplace -> "只读资源"
                            changingBound && card.id != state.boundCardId -> "先恢复当前目标，才能更换"
                            else -> if (card.hasBackup) "可替换 · 已有原始备份" else "可替换 · 写入前自动备份"
                        },
                        startAction = card.preview?.let { bitmap -> {
                            Image(bitmap.asImageBitmap(), card.title, Modifier.size(56.dp))
                        } },
                        endActions = { RadioButton(selected = card.id == state.selectedCardId, onClick = null) },
                        onClick = { onSelectCard(card.id) }, role = Role.RadioButton,
                        enabled = selectable && !state.busy)
                }
            }
        }
        Button(onClick = onWrite,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColorsPrimary(),
            enabled = ready && !state.busy && selected?.canReplace == true && selected.id !in occupied &&
                (!changingBound || selected.id == state.boundCardId)) {
            Text("写入并绑定", maxLines = 1)
        }
    }
}

@Composable
private fun SettingsPage(state: BridgeUiState, onAuthorize: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val ready = state.shizuku.status == ShizukuStatus.READY
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = "Shizuku", summary = when (state.shizuku.status) {
                    ShizukuStatus.NOT_RUNNING -> "未运行，请先启动 Shizuku"
                    ShizukuStatus.AUTH_REQUIRED -> "未授权，替换载体需要授权"
                    ShizukuStatus.CONNECTING -> "正在连接…"
                    ShizukuStatus.READY -> "已连接 · ${state.cards.size} 个本地卡片资源"
                    ShizukuStatus.ERROR -> state.shizuku.message.ifBlank { "连接失败" }
                })
                BasicComponent(title = if (ready) "刷新替换目标" else "授权与连接",
                    summary = if (ready) "重新扫描预设及自行生成的本地卡片" else "只有写入和恢复载体需要 Shizuku",
                    endActions = { ForwardIcon() }, onClick = if (ready) onRefresh else onAuthorize,
                    enabled = !state.busy)
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
