package dev.finall.rearcardbridge.model

import android.graphics.Bitmap
import java.io.File

data class CardInfo(
    val id: String,
    val title: String,
    val sourcePath: String,
    val canReplace: Boolean,
    val hasBackup: Boolean = false,
    val preview: Bitmap? = null,
)

/** One installed card module (module.json metadata plus its content group state). */
data class ModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val description: String,
    val author: String,
    val capabilities: Set<String>,
    val hasWebui: Boolean,
    val source: String?,
    val groupId: String?,
    val boundCardId: String?,
    val boundCardTitle: String?,
    val backgroundIntervalMinutes: Int? = null,
    val backgroundLastRefreshAt: Long? = null,
    val backgroundNextRefreshAt: Long? = null,
    val backgroundRefreshError: String? = null,
)
enum class ShizukuStatus { NOT_RUNNING, AUTH_REQUIRED, CONNECTING, READY, ERROR }
data class ShizukuState(val status: ShizukuStatus, val message: String = "")
data class OperationResult(val success: Boolean, val code: String, val message: String)
data class BridgeUiState(
    val shizuku: ShizukuState = ShizukuState(ShizukuStatus.NOT_RUNNING),
    val cards: List<CardInfo> = emptyList(),
    val groups: List<dev.finall.rearcardbridge.live.ContentBinding> = emptyList(),
    val activeGroupId: String? = null,
    val sourcePackage: String? = null,
    val contentKey: String? = null,
    val selectedCardId: String? = null,
    val packageName: String? = null,
    val packageEntries: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: String = "导入内容，背屏载体会读取最新版本。",
    val contentRevision: Long = 0L,
    val carrierResources: dev.finall.rearcardbridge.live.CarrierResourceStatus? = null,
    val boundCardId: String? = null,
    val boundCardTitle: String? = null,
    val error: String? = null,
    val lastReplacedCardId: String? = null,
    val modules: List<ModuleInfo> = emptyList(),
    val modulesLoaded: Boolean = false,
    val activeModuleId: String? = null,
    val moduleInstallEvent: Long = 0,
    val moduleDowngradeWarning: String? = null,
    val backgroundRefreshEnabled: Boolean = false,
    val backgroundRefreshRunning: Boolean = false,
    val backgroundRefreshMessage: String = "后台刷新未开启",
)
data class PreparedCardPackage(val file: File, val entryNames: List<String>)
