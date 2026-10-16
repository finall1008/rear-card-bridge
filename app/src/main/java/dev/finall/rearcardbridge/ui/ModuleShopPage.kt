package dev.finall.rearcardbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.finall.rearcardbridge.model.ModuleInfo
import dev.finall.rearcardbridge.store.CatalogModule
import dev.finall.rearcardbridge.store.ModuleShopState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Native module catalog. The host owns loading, install, and navigation;
 * search filters name, author, description, and tags on the device.
 */
@Composable
internal fun ModuleShopPage(
    shop: ModuleShopState,
    installedModules: List<ModuleInfo>,
    modulesLoaded: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onInstall: (CatalogModule) -> Unit,
    onOpenModule: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var savedQuery by rememberSaveable { mutableStateOf("") }
    val queryField = rememberTextFieldState(initialText = savedQuery)
    val query = queryField.text.toString()
    SideEffect {
        if (savedQuery != query) savedQuery = query
    }
    val installedById = remember(installedModules) { installedModules.associateBy { it.id } }
    val needle = query.trim()
    val visible = if (needle.isEmpty()) shop.modules else shop.modules.filter { it.matchesShopQuery(needle) }
    val canRefresh = !shop.loading && !busy
    val showBlockingLoading = shop.modules.isEmpty() &&
        (shop.loading || (shop.error.isNullOrBlank() && !shop.loaded))
    val showEmpty = shop.loaded && !shop.loading && shop.modules.isEmpty() && shop.error.isNullOrBlank()
    val showError = !shop.error.isNullOrBlank() && !shop.loading

    PullToRefresh(
        isRefreshing = shop.loading && shop.modules.isNotEmpty(),
        onRefresh = { if (canRefresh) onRefresh() },
        refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextField(
                        state = queryField,
                        modifier = Modifier.weight(1f),
                        label = "搜索模块",
                        useLabelAsPlaceholder = true,
                    )
                    IconButton(onClick = onRefresh, enabled = canRefresh) {
                        Icon(
                            MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            if (showError) item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = if (shop.modules.isEmpty()) "无法加载" else "刷新失败",
                        summary = shop.error.orEmpty(),
                    )
                    Button(
                        onClick = onRefresh,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                        enabled = canRefresh,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            "重试",
                            maxLines = 1,
                            style = MiuixTheme.textStyles.button,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (showBlockingLoading) item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "正在读取商店…",
                        summary = "请稍候",
                        startAction = { InfiniteProgressIndicator(size = 28.dp) },
                    )
                }
            } else if (showEmpty) item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "商店里还没有模块",
                        summary = "点按刷新，看看有没有新的模块。",
                        startAction = {
                            Icon(MiuixIcons.Store, contentDescription = null, modifier = Modifier.size(32.dp))
                        },
                    )
                }
            } else if (shop.modules.isNotEmpty() && visible.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "没有匹配的模块",
                        summary = "换个名称、作者或标签试试。",
                        startAction = {
                            Icon(MiuixIcons.Search, contentDescription = null, modifier = Modifier.size(32.dp))
                        },
                    )
                }
            }
            items(visible, key = { it.id }) { module ->
                ModuleShopCard(
                    module = module,
                    installed = if (modulesLoaded) installedById[module.id] else null,
                    modulesLoaded = modulesLoaded,
                    busy = busy,
                    installing = shop.installingModuleId == module.id,
                    onInstall = { onInstall(module) },
                    onOpen = { onOpenModule(module.id) },
                    onOpenLink = { openShopLink(context, it) },
                )
            }
        }
    }
}

@Composable
private fun ModuleShopCard(
    module: CatalogModule,
    installed: ModuleInfo?,
    modulesLoaded: Boolean,
    busy: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val action = if (modulesLoaded) shopAction(module, installed) else null
    val label = when {
        installing -> "正在安装…"
        !modulesLoaded -> "请稍候"
        action == ModuleShopAction.Update -> "更新"
        action == ModuleShopAction.Open -> "打开"
        else -> "安装"
    }
    val emphasize = action == ModuleShopAction.Install || action == ModuleShopAction.Update
    val tags = module.tags.filter { it.isNotBlank() }
    Card(Modifier.fillMaxWidth()) {
        ModulePreviewGallery(
            previews = module.previews,
            moduleName = module.name,
        )
        BasicComponent(
            title = module.name,
            summary = catalogSummary(module, if (modulesLoaded) installed else null),
            startAction = {
                Icon(MiuixIcons.GridView, contentDescription = null, modifier = Modifier.size(36.dp))
            },
            bottomAction = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (module.description.isNotBlank()) {
                        Text(
                            text = module.description,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (tags.isNotEmpty()) {
                        Text(
                            text = tags.joinToString(" · "),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (module.repository.isNotBlank()) {
                            IconButton(onClick = { onOpenLink(module.repository) }) {
                                Icon(MiuixIcons.Link, contentDescription = "源码", modifier = Modifier.size(22.dp))
                            }
                        }
                        if (module.releaseUrl.isNotBlank()) {
                            IconButton(onClick = { onOpenLink(module.releaseUrl) }) {
                                Icon(MiuixIcons.Info, contentDescription = "发布页", modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (action == ModuleShopAction.Open) onOpen() else onInstall()
                            },
                            enabled = modulesLoaded && !busy && !installing,
                            colors = if (emphasize) ButtonDefaults.buttonColorsPrimary()
                            else ButtonDefaults.buttonColors(),
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                style = MiuixTheme.textStyles.button,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            },
        )
    }
}

private enum class ModuleShopAction { Install, Update, Open }

private fun shopAction(module: CatalogModule, installed: ModuleInfo?): ModuleShopAction = when {
    installed == null -> ModuleShopAction.Install
    installed.versionCode < module.versionCode -> ModuleShopAction.Update
    else -> ModuleShopAction.Open
}

private fun catalogSummary(module: CatalogModule, installed: ModuleInfo?): String {
    val version = module.version.ifBlank { module.versionCode.toString() }
    val parts = mutableListOf(version)
    if (module.author.isNotBlank()) parts += module.author
    if (installed != null && installed.versionCode != module.versionCode) {
        val local = installed.version.ifBlank { installed.versionCode.toString() }
        parts += "本机 $local"
    }
    return parts.joinToString(" · ")
}

private fun CatalogModule.matchesShopQuery(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        author.contains(query, ignoreCase = true) ||
        description.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) }

private fun openShopLink(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
