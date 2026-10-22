package dev.finall.rearcardbridge.model

import java.io.File

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
    val backgroundIntervalMinutes: Int? = null,
    val backgroundLastRefreshAt: Long? = null,
    val backgroundNextRefreshAt: Long? = null,
    val backgroundRefreshError: String? = null,
    /** True once a card install reported success; an installed snapshot exists. */
    val cardInstalled: Boolean = false,
    /** Null = unknown/not computed; true = installed static resources differ from the current module. */
    val cardNeedsUpdate: Boolean? = null,
    /** Legacy carrier this module was written into before standalone cards existed. */
    val legacyCardId: String? = null,
    val legacyCardTitle: String? = null,
)
enum class ShizukuStatus { NOT_RUNNING, AUTH_REQUIRED, CONNECTING, READY, ERROR }
data class ShizukuState(val status: ShizukuStatus, val message: String = "")
data class OperationResult(val success: Boolean, val code: String, val message: String)

/** How card installs reach the privileged rear-card host. */
enum class InstallMode(val prefName: String) {
    ROOT("root"),
    SHIZUKU("shizuku"),
    ;

    companion object {
        fun fromPref(value: String?): InstallMode =
            entries.firstOrNull { it.prefName == value } ?: ROOT
    }
}

data class BridgeUiState(
    val shizuku: ShizukuState = ShizukuState(ShizukuStatus.NOT_RUNNING),
    val installMode: InstallMode = InstallMode.ROOT,
    val managerCardInstalled: Boolean = false,
    val activeGroupId: String? = null,
    val sourcePackage: String? = null,
    val contentKey: String? = null,
    val packageName: String? = null,
    val packageEntries: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: String = "导入内容后安装为独立背屏卡。",
    val contentRevision: Long = 0L,
    val error: String? = null,
    val modules: List<ModuleInfo> = emptyList(),
    val modulesLoaded: Boolean = false,
    val activeModuleId: String? = null,
    val moduleInstallEvent: Long = 0,
    val moduleDowngradeWarning: String? = null,
    val shop: dev.finall.rearcardbridge.store.ModuleShopState = dev.finall.rearcardbridge.store.ModuleShopState(),
    val backgroundRefreshEnabled: Boolean = false,
    val backgroundRefreshRunning: Boolean = false,
    val backgroundRefreshMessage: String = "后台刷新未开启",
    val installTasks: List<dev.finall.rearcardbridge.module.install.InstallTask> = emptyList(),
)
data class PreparedCardPackage(val file: File, val entryNames: List<String>)
