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
)
data class PreparedCardPackage(val file: File, val entryNames: List<String>)
