package dev.finall.rearcardbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Rear-card frame. Loading and failure keep the image slot stable. */
private const val PREVIEW_ASPECT_RATIO = 912f / 596f

/**
 * Horizontal preview on a shop card. The first URL is the cover.
 * Image load state stays inside this slot and does not affect install.
 * A tap opens a fullscreen pager on that page.
 */
@Composable
internal fun ModulePreviewGallery(
    previews: List<String>,
    moduleName: String,
    modifier: Modifier = Modifier,
) {
    val urls = androidx.compose.runtime.remember(previews) {
        previews.map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (urls.isEmpty()) return
    var viewerPage by rememberSaveable { mutableIntStateOf(-1) }
    val pagerState = rememberPagerState(pageCount = { urls.size })
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(PREVIEW_ASPECT_RATIO)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = urls.size > 1,
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "查看大图",
                        onClick = { viewerPage = page },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ModulePreviewImage(
                    url = urls[page],
                    contentDescription = previewDescription(moduleName, page, urls.size),
                    onDark = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PreviewPageCount(
            page = pagerState.currentPage,
            count = urls.size,
            onDark = false,
            announceChanges = false,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
    if (viewerPage in urls.indices) {
        ModulePreviewViewer(
            urls = urls,
            initialPage = viewerPage,
            moduleName = moduleName,
            onDismiss = { viewerPage = -1 },
        )
    }
}

@Composable
private fun ModulePreviewViewer(
    urls: List<String>,
    initialPage: Int,
    moduleName: String,
    onDismiss: () -> Unit,
) {
    val start = initialPage.coerceIn(0, urls.lastIndex)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { urls.size })
    val title = moduleName.ifBlank { "预览" }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            windowTitle = "$title 预览",
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = urls.size > 1,
            ) { page ->
                ModulePreviewImage(
                    url = urls[page],
                    contentDescription = previewDescription(moduleName, page, urls.size),
                    onDark = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    MiuixIcons.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
                )
            }
            PreviewPageCount(
                page = pagerState.currentPage,
                count = urls.size,
                onDark = true,
                announceChanges = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ModulePreviewImage(
    url: String,
    contentDescription: String,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val message = if (onDark) {
        Color.White.copy(alpha = 0.86f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = Alignment.Center,
        contentScale = ContentScale.Fit,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (onDark) {
                    InfiniteProgressIndicator(size = 28.dp, color = Color.White)
                } else {
                    InfiniteProgressIndicator(size = 28.dp)
                }
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "无法加载",
                    color = message,
                    fontSize = 13.sp,
                )
            }
        },
    )
}

@Composable
private fun PreviewPageCount(
    page: Int,
    count: Int,
    onDark: Boolean,
    announceChanges: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (onDark) {
        Color(0xFF2C2C2E).copy(alpha = 0.82f)
    } else {
        MiuixTheme.colorScheme.surface.copy(alpha = 0.88f)
    }
    val textColor = if (onDark) Color.White else MiuixTheme.colorScheme.onSurface
    Text(
        text = "${page + 1}/$count",
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .clearAndSetSemantics {
                contentDescription = "第 ${page + 1} 张，共 $count 张"
                if (announceChanges) liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = textColor,
        fontSize = 12.sp,
    )
}

private fun previewDescription(moduleName: String, page: Int, count: Int): String {
    val title = moduleName.ifBlank { "模块" }
    return "$title 预览，第 ${page + 1} 张，共 $count 张"
}
