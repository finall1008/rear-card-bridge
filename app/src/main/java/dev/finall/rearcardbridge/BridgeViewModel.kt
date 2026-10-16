package dev.finall.rearcardbridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finall.rearcardbridge.live.ContentBinding
import dev.finall.rearcardbridge.live.LiveContentContract
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.ModuleInfo
import dev.finall.rearcardbridge.model.OperationResult
import dev.finall.rearcardbridge.model.ShizukuStatus
import dev.finall.rearcardbridge.module.BackgroundRefreshScheduler
import dev.finall.rearcardbridge.module.GitInstall
import dev.finall.rearcardbridge.module.ModuleInstaller
import dev.finall.rearcardbridge.module.ModuleDowngradeException
import dev.finall.rearcardbridge.module.ModuleStore
import dev.finall.rearcardbridge.module.ModuleWebProfiles
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import dev.finall.rearcardbridge.shizuku.ShizukuCardClient
import dev.finall.rearcardbridge.store.CatalogModule
import dev.finall.rearcardbridge.store.ModuleCatalogRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val client = ShizukuCardClient(application)
    private val builder = CardPackageBuilder()
    private val contentStore = LiveContentStore(application)
    private val moduleStore = ModuleStore(application)
    private val moduleInstaller = ModuleInstaller(application)
    private val mutableState = MutableStateFlow(BridgeUiState())
    val state = mutableState.asStateFlow()
    private val operationLock = Mutex()
    private data class PendingModuleInstall(val file: File, val source: String, val updateOnly: Boolean)
    private var pendingModuleInstall: PendingModuleInstall? = null
    var onOperationResult: ((OperationResult, String?) -> Unit)? = null
    var onOpenRearSettingsRequested: ((String?) -> OperationResult)? = null

    init {
        BackgroundRefreshScheduler.restorePreference(application)
        application.getExternalFilesDir(null)
        runOperation(queue = true) { refreshModules() }
        viewModelScope.launch {
            client.state.collect { connection ->
                val becameReady = connection.status == ShizukuStatus.READY &&
                    mutableState.value.shizuku.status != ShizukuStatus.READY
                mutableState.update { it.copy(shizuku = connection) }
                if (becameReady) refresh()
            }
        }
        viewModelScope.launch {
            BackgroundRefreshScheduler.state.collect { refresh ->
                mutableState.update { it.copy(backgroundRefreshEnabled = refresh.enabled,
                    backgroundRefreshRunning = refresh.running,
                    backgroundRefreshMessage = refresh.message,
                    modules = it.modules.map(::withBackgroundSchedule)) }
            }
        }
        client.connect()
    }

    fun authorize() { client.requestPermission(); client.connect() }
    fun selectCard(id: String) {
        if (!state.value.busy && state.value.cards.any { it.id == id && it.canReplace }) {
            mutableState.update { it.copy(selectedCardId = id) }
        }
    }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun rejectInput(message: String) { mutableState.update { it.copy(error = message) } }

    /** All incoming zips are module packages; the legacy per-source groups flow is gone. */
    fun receiveExternal(uri: Uri) = installModuleZip(uri)

    private suspend fun loadGroup(group: ContentBinding) {
        val (groups, saved, resources) = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                Triple(contentStore.bindings(), contentStore.current(group.id),
                    if (group.cardId != null) contentStore.resourceStatus(group.id) else null)
            }
        }
        mutableState.update { it.copy(groups = groups, activeGroupId = group.id,
            sourcePackage = group.sourcePackage, contentKey = group.key,
            boundCardId = group.cardId, boundCardTitle = group.cardTitle,
            selectedCardId = group.cardId, packageName = saved?.name,
            packageEntries = saved?.entries.orEmpty(), contentRevision = saved?.revision ?: 0,
            carrierResources = resources,
            status = if (group.cardId == null) "请选择模块的替换目标。" else "已绑定「${group.cardTitle}」") }
    }

    fun refresh() = runOperation(queue = true) {
        refreshModules()
        val cards = withContext(Dispatchers.IO) { client.listCards() }
        mutableState.update { old -> old.copy(cards = cards,
            selectedCardId = old.selectedCardId?.takeIf { id -> cards.any { it.id == id && it.canReplace } },
            status = "${cards.size} 个载体资源") }
        reloadActive()
    }

    /** Lists installed modules together with their content group / binding state. */
    private suspend fun refreshModules() {
        val (modules, groups) = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) { moduleStore.list() to contentStore.bindings() }
        }
        val appPackage = getApplication<Application>().packageName
        mutableState.update { it.copy(groups = groups, modulesLoaded = true, modules = modules.map { installed ->
            val group = groups.firstOrNull { g -> g.sourcePackage == appPackage && g.key == installed.manifest.id }
            val manifest = installed.manifest
            ModuleInfo(id = manifest.id, name = manifest.name, version = manifest.version,
                versionCode = manifest.versionCode, description = manifest.description,
                author = manifest.author, capabilities = manifest.capabilities,
                hasWebui = manifest.hasWebui, source = installed.source,
                groupId = group?.id, boundCardId = group?.cardId, boundCardTitle = group?.cardTitle,
                backgroundIntervalMinutes = manifest.backgroundIntervalMinutes).let(::withBackgroundSchedule)
        }) }
        BackgroundRefreshScheduler.schedule(getApplication(), modules)
    }

    private fun withBackgroundSchedule(module: ModuleInfo): ModuleInfo {
        val minutes = module.backgroundIntervalMinutes ?: return module
        val schedule = BackgroundRefreshScheduler.scheduleFor(getApplication(), module.id, minutes)
        return module.copy(backgroundIntervalMinutes = schedule.intervalMinutes,
            backgroundLastRefreshAt = schedule.lastRefreshAt,
            backgroundNextRefreshAt = schedule.nextAttemptAt.takeIf { it != 0L },
            backgroundRefreshError = schedule.error)
    }

    fun toggleBackgroundRefresh() = runOperation {
        val application = getApplication<Application>()
        BackgroundRefreshScheduler.setEnabled(application, !BackgroundRefreshScheduler.isEnabled(application))
        refreshModules()
    }

    fun setBackgroundRefreshInterval(id: String, minutes: Int) = runOperation {
        val module = withContext(Dispatchers.IO) { moduleStore.get(id) } ?: error("模块尚未安装。")
        requireNotNull(module.manifest.backgroundIntervalMinutes) { "模块未提供后台刷新入口。" }
        BackgroundRefreshScheduler.setInterval(getApplication(), id, minutes)
        refreshModules()
    }

    fun installModuleZip(uri: Uri) = runOperation(queue = true) {
        val file = File.createTempFile("module-import-", ".zip", getApplication<Application>().cacheDir)
        try {
            withContext(Dispatchers.IO) {
                requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) {
                    "无法读取 ZIP 文件。"
                }.use { source -> file.outputStream().use { source.copyTo(it) } }
            }
            installPreparedModule(file, source = "zip")
        } finally {
            if (pendingModuleInstall?.file != file) file.delete()
        }
    }

    fun refreshShop() {
        if (state.value.shop.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(shop = it.shop.copy(loading = true, error = null)) }
            try {
                val modules = withContext(Dispatchers.IO) { ModuleCatalogRepository.load() }
                mutableState.update { it.copy(shop = it.shop.copy(modules = modules, loaded = true)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(shop = it.shop.copy(error = error.message ?: "商店加载失败")) }
            } finally {
                mutableState.update { it.copy(shop = it.shop.copy(loading = false)) }
            }
        }
    }

    fun installShopModule(module: CatalogModule) = runOperation {
        val file = File.createTempFile("module-shop-", ".zip", getApplication<Application>().cacheDir)
        mutableState.update { it.copy(shop = it.shop.copy(installingModuleId = module.id)) }
        try {
            withContext(Dispatchers.IO) { ModuleCatalogRepository.download(module, file) }
            installPreparedModule(file, source = "zip")
        } finally {
            if (pendingModuleInstall?.file != file) file.delete()
            mutableState.update { it.copy(shop = it.shop.copy(installingModuleId = null)) }
        }
    }

    fun installModuleGit(url: String, branch: String?) = runOperation {
        installModuleGitOnce(url, branch)
    }

    private suspend fun installPreparedModule(file: File, source: String, updateOnly: Boolean = false,
        allowDowngrade: Boolean = false) {
        clearPendingModuleInstall()
        val result = try {
            withContext(Dispatchers.IO) {
                synchronized(ContentOperations.lock) { moduleInstaller.install(file, source, allowDowngrade) }
            }
        } catch (error: ModuleDowngradeException) {
            // Retain the exact downloaded/copied ZIP, not a URI or URL that could change before confirmation.
            pendingModuleInstall = PendingModuleInstall(file, source, updateOnly)
            mutableState.update { it.copy(moduleDowngradeWarning = error.message) }
            return
        }
        val (installed, saved) = result
        if (updateOnly) {
            refreshModules()
            reloadActive()
            mutableState.update { it.copy(status = "模块已更新。") }
        } else {
            finishModuleInstall(installed.manifest.id,
                "模块「${installed.manifest.name}」已安装（版本 ${saved.revision}）。")
        }
    }

    fun confirmModuleDowngrade() = runOperation {
        val pending = pendingModuleInstall ?: return@runOperation
        pendingModuleInstall = null
        mutableState.update { it.copy(moduleDowngradeWarning = null) }
        try {
            installPreparedModule(pending.file, pending.source, pending.updateOnly, allowDowngrade = true)
        } finally { pending.file.delete() }
    }

    fun dismissModuleDowngrade() {
        if (!state.value.busy) clearPendingModuleInstall()
    }

    private fun clearPendingModuleInstall() {
        pendingModuleInstall?.file?.delete()
        pendingModuleInstall = null
        mutableState.update { it.copy(moduleDowngradeWarning = null) }
    }

    /** Re-downloads the recorded git source. Modules installed from ZIP have no update channel. */
    fun updateModule(id: String) = runOperation {
        val installed = withContext(Dispatchers.IO) { moduleStore.get(id) }
            ?: error("模块尚未安装。")
        val source = installed.source
        require(source != null && source != "zip") { "ZIP 安装的模块请重新选择新版 ZIP。" }
        val (url, branch) = source.split('@').let {
            "https://" + it[0] to it.getOrNull(1)
        }
        installModuleGitOnce(url, branch, updateOnly = true)
    }

    private suspend fun installModuleGitOnce(url: String, branch: String?, updateOnly: Boolean = false) {
        val repo = GitInstall.parseRepoUrl(url, branch)
        val repacked = File.createTempFile("module-git-repack-", ".zip", getApplication<Application>().cacheDir)
        try {
            withContext(Dispatchers.IO) {
                val archive = File.createTempFile("module-git-", ".zip", getApplication<Application>().cacheDir)
                val unpacked = File.createTempFile("module-git-", "", getApplication<Application>().cacheDir)
                try {
                    check(unpacked.delete() && unpacked.mkdirs()) { "无法创建临时目录。" }
                    GitInstall.download(repo, archive)
                    GitInstall.stripTopLevel(archive, unpacked)
                    repackDirectory(unpacked, repacked)
                } finally {
                    archive.delete()
                    unpacked.deleteRecursively()
                }
            }
            installPreparedModule(repacked, repo.display, updateOnly)
        } finally {
            if (pendingModuleInstall?.file != repacked) repacked.delete()
        }
    }

    /** stripTopLevel output is a plain directory; ModuleInstaller expects a zip. */
    private fun repackDirectory(root: File, output: File) {
        java.util.zip.ZipOutputStream(output.outputStream()).use { out ->
            root.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(root).path }
                .forEach { file ->
                    out.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
        }
    }

    private suspend fun finishModuleInstall(moduleId: String, message: String) {
        refreshModules()
        val group = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                contentStore.group(getApplication<Application>().packageName, moduleId)
            }
        }
        if (group != null) loadGroup(group)
        mutableState.update { it.copy(activeModuleId = moduleId, status = message,
            moduleInstallEvent = it.moduleInstallEvent + 1) }
        onOperationResult?.invoke(OperationResult(true,
            if (group?.cardId == null) "BINDING_REQUIRED" else "SAVED", message), group?.cardId)
    }

    fun selectModule(id: String) = runOperation {
        val group = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                contentStore.group(getApplication<Application>().packageName, id)
            }
        } ?: error("模块尚未安装。")
        loadGroup(group)
        mutableState.update { it.copy(activeModuleId = id) }
    }

    fun uninstallModule(id: String) = runOperation {
        withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                val group = contentStore.group(getApplication<Application>().packageName, id)
                require(group?.cardId == null) { "请先恢复该模块绑定的载体卡片，再卸载。" }
                ModuleWebProfiles.remove(id)
                if (group != null) contentStore.removeGroup(group.id)
                moduleStore.remove(id)
            }
        }
        BackgroundRefreshScheduler.removeModule(getApplication(), id)
        refreshModules()
        if (state.value.activeModuleId == id) {
            mutableState.update { it.copy(activeModuleId = null) }
            reloadActive()
        }
        mutableState.update { it.copy(status = "模块已卸载。") }
    }

    /** The bridge protocol is only wired for carriers backed by an installed module. */
    private fun moduleIdFor(group: ContentBinding): String? {
        if (group.sourcePackage != getApplication<Application>().packageName) return null
        return if (moduleStore.get(group.key) != null) group.key else null
    }

    fun installCarrier() = runOperation {
        val groupId = requireNotNull(state.value.activeGroupId)
        val id = requireNotNull(state.value.selectedCardId) { "请选择载体卡片。" }
        val card = state.value.cards.firstOrNull { it.id == id && it.canReplace }
            ?: error("目标不可写，请刷新资源。")
        val result = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                val groups = contentStore.bindings()
                val active = groups.first { it.id == groupId }
                require(active.cardId == null || active.cardId == id) { "更换目标前请先恢复原来的载体。" }
                require(groups.none { it.id != groupId && it.cardId == id }) {
                    "这张卡片已绑定其他模块，请选择其他目标。"
                }
                val output = File.createTempFile("carrier-", ".zip", getApplication<Application>().cacheDir)
                val snapshot = File.createTempFile("resources-", ".zip", getApplication<Application>().cacheDir)
                try {
                    val archive = contentStore.archive(groupId)
                    val moduleId = moduleIdFor(active)
                    builder.buildLiveCarrier(archive, output, id,
                        LiveContentContract.uri(groupId).toString(),
                        bridgeGroupId = moduleId?.let { groupId }, bridgeModuleId = moduleId)
                    builder.writeStaticResourceSnapshot(archive, snapshot)
                    client.replaceCard(id, output).also { result ->
                        if (result.success) {
                            contentStore.bind(groupId, id, card.title)
                            contentStore.recordInstalledResources(groupId, snapshot)
                        }
                    }
                } finally { output.delete(); snapshot.delete() }
            }
        }
        if (!result.success) { recordResult(result, id); return@runOperation }
        reloadActive()
        refreshModules()
        recordResult(OperationResult(true, "CARRIER_WRITTEN", "载体已写入「${card.title}」，请在背屏设置中重新应用。"), id)
        openSettingsAfterWrite(id)
        refreshAfterChange()
    }

    fun restore(id: String) = runOperation {
        val result = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                client.restoreCard(id).also { if (it.success) contentStore.unbindCard(id) }
            }
        }
        recordResult(result, id)
        if (result.success) { reloadActive(); refreshModules(); openSettingsAfterWrite(id); refreshAfterChange() }
    }

    fun confirmReapplied() = runOperation {
        val groupId = requireNotNull(state.value.activeGroupId)
        withContext(Dispatchers.IO) { contentStore.confirmReapplied(groupId) }
        reloadActive()
        mutableState.update { it.copy(status = "已记录你的重新应用确认。") }
    }

    fun reload() = runOperation(queue = true) { refreshModules(); reloadActive() }
    private suspend fun reloadActive() {
        val group = withContext(Dispatchers.IO) {
            contentStore.bindings().firstOrNull { it.id == state.value.activeGroupId }
        }
        if (group != null) loadGroup(group)
    }
    fun openRearSettings(id: String? = state.value.boundCardId) = runOperation {
        recordResult(requestRearSettings(id), id)
    }
    private fun requestRearSettings(id: String?): OperationResult =
        onOpenRearSettingsRequested?.invoke(id) ?: OperationResult(false, "ERROR", "无法打开背屏设置。")
    private fun openSettingsAfterWrite(id: String) {
        val result = requestRearSettings(id)
        if (!result.success) mutableState.update { it.copy(error = result.message) }
    }
    private suspend fun refreshAfterChange() {
        try {
            val cards = withContext(Dispatchers.IO) { client.listCards() }
            mutableState.update { it.copy(cards = cards) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(error = "资源操作已完成，但刷新列表失败：${error.message}") } }
    }
    private fun recordResult(result: OperationResult, cardId: String? = state.value.boundCardId) {
        mutableState.update { it.copy(status = result.message,
            error = result.message.takeUnless { result.success || result.code == "BINDING_REQUIRED" }) }
        onOperationResult?.invoke(result, cardId)
    }
    private fun runOperation(queue: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy && !queue) return
        viewModelScope.launch {
            operationLock.withLock {
                mutableState.update { it.copy(busy = true, error = null) }
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { recordResult(OperationResult(false, "ERROR", error.message ?: "操作失败")) }
                finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }
    override fun onCleared() {
        clearPendingModuleInstall()
        onOperationResult = null
        onOpenRearSettingsRequested = null
        client.close()
    }
}
