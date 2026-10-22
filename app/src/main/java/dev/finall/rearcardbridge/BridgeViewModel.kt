package dev.finall.rearcardbridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finall.rearcardbridge.live.ContentBinding
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.InstallMode
import dev.finall.rearcardbridge.model.ModuleInfo
import dev.finall.rearcardbridge.model.OperationResult
import dev.finall.rearcardbridge.model.ShizukuStatus
import dev.finall.rearcardbridge.module.BackgroundRefreshScheduler
import dev.finall.rearcardbridge.module.GitInstall
import dev.finall.rearcardbridge.module.ModuleInstaller
import dev.finall.rearcardbridge.module.ModuleDowngradeException
import dev.finall.rearcardbridge.module.ModuleStore
import dev.finall.rearcardbridge.module.ModuleWebProfiles
import dev.finall.rearcardbridge.module.install.InstallManager
import dev.finall.rearcardbridge.module.install.InstallOp
import dev.finall.rearcardbridge.module.install.InstallTask
import dev.finall.rearcardbridge.module.install.ManagerCardAssets
import dev.finall.rearcardbridge.root.RootCardInstaller
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
    private val contentStore = LiveContentStore(application)
    private val moduleStore = ModuleStore(application)
    private val moduleInstaller = ModuleInstaller(application)
    private val installManager = InstallManager(application)
    private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(BridgeUiState(
        installMode = InstallMode.fromPref(prefs.getString(PREF_INSTALL_MODE, null)),
        managerCardInstalled = prefs.getBoolean(PREF_MANAGER_INSTALLED, false),
    ))
    val state = mutableState.asStateFlow()
    private val operationLock = Mutex()
    private data class PendingModuleInstall(val file: File, val source: String, val updateOnly: Boolean)
    private var pendingModuleInstall: PendingModuleInstall? = null
    var onOperationResult: ((OperationResult, String?) -> Unit)? = null

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
        viewModelScope.launch {
            var previous = emptyList<InstallTask>()
            installManager.taskFlow.collect { tasks ->
                val finished = tasks.filter { task ->
                    task.state == dev.finall.rearcardbridge.module.install.InstallTaskState.SUCCEEDED ||
                        task.state == dev.finall.rearcardbridge.module.install.InstallTaskState.FAILED
                }.filter { task ->
                    previous.firstOrNull { it.taskId == task.taskId }?.let {
                        it.state != task.state
                    } ?: false
                }
                previous = tasks
                mutableState.update { it.copy(installTasks = tasks) }
                // The card may have been removed outside the app (the local snapshot
                // only tracks what this device installed). A failed UPDATE is
                // resubmitted as a fresh INSTALL unless a task for that card
                // already exists after it.
                val staleUpdates = tasks.filter {
                    it.op == dev.finall.rearcardbridge.module.install.InstallOp.UPDATE &&
                        it.state == dev.finall.rearcardbridge.module.install.InstallTaskState.FAILED
                }.filter { failed ->
                    tasks.none { other ->
                        other.cardId == failed.cardId &&
                            (other.createdAt > failed.createdAt ||
                                (other.createdAt == failed.createdAt && other.taskId > failed.taskId))
                    }
                }
                withContext(Dispatchers.IO) {
                    finished.forEach { runCatching { client.clearInstallStage(it.taskId) } }
                    staleUpdates.forEach { task ->
                        installManager.discardStageBundle(task.taskId)
                        val moduleId = task.cardId.removePrefix(CARD_ID_PREFIX)
                        if (task.cardId != moduleId) {
                            runCatching {
                                val retry = installManager.submitInstall(moduleId, update = false)
                                dispatchCardTask(retry)
                            }
                        }
                    }
                }
                if (finished.isNotEmpty() || staleUpdates.isNotEmpty()) refreshCardFlags()
            }
        }
        client.connect()
    }

    fun authorize() { client.requestPermission(); client.connect() }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun rejectInput(message: String) { mutableState.update { it.copy(error = message) } }

    /** All incoming zips are module packages; the legacy per-source groups flow is gone. */
    fun receiveExternal(uri: Uri) = installModuleZip(uri)

    private suspend fun loadGroup(group: ContentBinding) {
        val saved = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) { contentStore.current(group.id) }
        }
        mutableState.update { it.copy(activeGroupId = group.id,
            sourcePackage = group.sourcePackage, contentKey = group.key,
            packageName = saved?.name,
            packageEntries = saved?.entries.orEmpty(), contentRevision = saved?.revision ?: 0) }
    }

    fun refresh() = runOperation(queue = true) {
        refreshModules()
        reloadActive()
    }

    /** Lists installed modules together with their content group / binding state. */
    private suspend fun refreshModules() {
        val (modules, groups) = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) { moduleStore.list() to contentStore.bindings() }
        }
        val appPackage = getApplication<Application>().packageName
        mutableState.update { it.copy(modulesLoaded = true, modules = modules.map { installed ->
            val group = groups.firstOrNull { g -> g.sourcePackage == appPackage && g.key == installed.manifest.id }
            val manifest = installed.manifest
            ModuleInfo(id = manifest.id, name = manifest.name, version = manifest.version,
                versionCode = manifest.versionCode, description = manifest.description,
                author = manifest.author, capabilities = manifest.capabilities,
                hasWebui = manifest.hasWebui, source = installed.source,
                groupId = group?.id,
                legacyCardId = group?.cardId,
                legacyCardTitle = group?.cardTitle,
                backgroundIntervalMinutes = manifest.backgroundIntervalMinutes).let(::withBackgroundSchedule)
        }) }
        BackgroundRefreshScheduler.schedule(getApplication(), modules)
        refreshCardFlags()
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
            val installedId = installPreparedModule(file, source = "zip")
            // When the module was kept (no downgrade pending), immediately install the card.
            if (installedId != null && pendingModuleInstall == null) {
                submitAndDispatch(installedId, update = false)
            }
        } finally {
            if (pendingModuleInstall?.file != file) file.delete()
            mutableState.update { it.copy(shop = it.shop.copy(installingModuleId = null)) }
        }
    }

    fun installModuleGit(url: String, branch: String?) = runOperation {
        installModuleGitOnce(url, branch)
    }

    /** Returns the installed manifest id, or null when a downgrade warning is pending. */
    private suspend fun installPreparedModule(file: File, source: String, updateOnly: Boolean = false,
        allowDowngrade: Boolean = false): String? {
        clearPendingModuleInstall()
        val result = try {
            withContext(Dispatchers.IO) {
                synchronized(ContentOperations.lock) { moduleInstaller.install(file, source, allowDowngrade) }
            }
        } catch (error: ModuleDowngradeException) {
            // Retain the exact downloaded/copied ZIP, not a URI or URL that could change before confirmation.
            pendingModuleInstall = PendingModuleInstall(file, source, updateOnly)
            mutableState.update { it.copy(moduleDowngradeWarning = error.message) }
            return null
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
        return installed.manifest.id
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
        refreshCardFlags()
        onOperationResult?.invoke(OperationResult(true, "SAVED", message), null)
    }

    fun selectModule(id: String) = runOperation {
        val group = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                contentStore.group(getApplication<Application>().packageName, id)
            }
        } ?: error("模块尚未安装。")
        loadGroup(group)
        mutableState.update { it.copy(activeModuleId = id) }
        refreshCardFlags()
    }

    fun uninstallModule(id: String) = runOperation {
        val cardId = CARD_ID_PREFIX + id
        val hadCard = withContext(Dispatchers.IO) { installManager.cardInstalled(cardId) } ||
            state.value.installTasks.any { it.cardId == cardId }
        // Drop queued installs so a stale bundle cannot appear after the module is gone.
        withContext(Dispatchers.IO) { installManager.cancelPendingForCard(cardId) }
        val cardRemoved = if (hadCard) {
            val task = withContext(Dispatchers.IO) {
                synchronized(ContentOperations.lock) { installManager.removeCard(cardId) }
            }
            dispatchCardTask(task)
        } else false
        withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                val group = contentStore.group(getApplication<Application>().packageName, id)
                ModuleWebProfiles.remove(id)
                moduleStore.remove(id)
            }
        }
        // A bound carrier keeps the module's resources after the group is deleted;
        // restore its original resources before dropping the binding record.
        val legacyCard = state.value.modules.firstOrNull { it.id == id }?.legacyCardId
        if (legacyCard != null) {
            if (restoreCarrierIfPossible(legacyCard)) {
                withContext(Dispatchers.IO) { contentStore.unbindCard(legacyCard) }
            } else {
                mutableState.update { it.copy(error =
                    "模块已卸载，但载体「$legacyCard」的原始资源未能自动恢复。") }
            }
        }
        withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                val group = contentStore.group(getApplication<Application>().packageName, id)
                if (group != null) contentStore.removeGroup(group.id)
            }
        }
        BackgroundRefreshScheduler.removeModule(getApplication(), id)
        refreshModules()
        if (state.value.activeModuleId == id) {
            mutableState.update { it.copy(activeModuleId = null) }
            reloadActive()
        }
        mutableState.update {
            it.copy(status = if (cardRemoved) "模块已卸载，卡片移除任务已下发。" else "模块已卸载。")
        }
    }

    fun setInstallMode(mode: InstallMode) {
        prefs.edit().putString(PREF_INSTALL_MODE, mode.prefName).apply()
        mutableState.update { it.copy(installMode = mode) }
    }

    /** Queues a fresh install task for the module card and dispatches it by [InstallMode]. */
    fun installStandalone(moduleId: String) = runOperation { submitAndDispatch(moduleId, update = false) }

    /** Queues an UPDATE task for an already-installed card. */
    fun updateStandalone(moduleId: String) = runOperation { submitAndDispatch(moduleId, update = true) }

    private suspend fun submitAndDispatch(moduleId: String, update: Boolean) {
        val task = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) { installManager.submitInstall(moduleId, update) }
        }
        dispatchCardTask(task)
    }

    private suspend fun dispatchCardTask(task: InstallTask): Boolean =
        when (state.value.installMode) {
            InstallMode.ROOT -> dispatchRoot(task)
            InstallMode.SHIZUKU -> dispatchRelay(task)
        }

    private suspend fun cancelDispatch(task: InstallTask, code: String, message: String) {
        withContext(Dispatchers.IO) {
            runCatching { installManager.cancel(task.taskId) }
            installManager.discardStageBundle(task.taskId)
        }
        recordResult(OperationResult(false, code, message))
    }

    private suspend fun dispatchRoot(task: InstallTask): Boolean {
        val rootReady = withContext(Dispatchers.IO) { RootCardInstaller.isRootAvailable() }
        if (!rootReady) {
            cancelDispatch(task, "NO_ROOT", "授权模式为 root，但当前没有 root 权限。")
            return false
        }
        val outcome = when (task.op) {
            InstallOp.REMOVE -> withContext(Dispatchers.IO) {
                runCatching { RootCardInstaller.remove(getApplication(), task.cardId, relayToken()) }
                    .getOrElse { false to (it.message ?: "移除失败") }
            }
            else -> withContext(Dispatchers.IO) {
                rootInstall(task, bundle = installManager.stageBundleFile(task.taskId))
            }
        }
        if (!outcome.first) {
            runCatching { installManager.cancel(task.taskId) }
            installManager.discardStageBundle(task.taskId)
            recordResult(OperationResult(false, "ROOT_INSTALL_FAILED", outcome.second))
        } else {
            installManager.finishTask(task.taskId, success = true, message = outcome.second)
            recordResult(OperationResult(true, "CARD_" + task.op.name,
                when (task.op) {
                    InstallOp.REMOVE -> "卡片「${task.cardName}」已从背屏移除。"
                    InstallOp.UPDATE -> "卡片「${task.cardName}」已更新。"
                    else -> "新卡「${task.cardName}」已插入背屏列表。"
                }))
        }
        return outcome.first
    }

    private suspend fun dispatchRelay(task: InstallTask): Boolean {
        if (!state.value.managerCardInstalled) {
            cancelDispatch(task, "NO_MANAGER",
                "安装失败：管理器卡未安装。请先在「设置与关于」安装管理器卡。")
            return false
        }
        if (state.value.shizuku.status != ShizukuStatus.READY) {
            cancelDispatch(task, "NO_SHIZUKU", "安装失败：Shizuku 未连接，无法暂存安装包。")
            return false
        }
        if (task.op == InstallOp.REMOVE) {
            // Removal needs no staged package; the relay card claims it directly.
            recordResult(OperationResult(true, "REMOVE_QUEUED",
                "已入队。请打开背屏上的「RearCard 安装管理器」卡片完成移除。"))
            return true
        }
        val staged = withContext(Dispatchers.IO) {
            runCatching { client.stageInstallPackage(task.taskId, installManager.stageBundleFile(task.taskId)) }
                .getOrElse { OperationResult(false, "STAGE_FAILED", "无法暂存安装包：${it.message}") to null }
        }
        val (result, path) = staged
        if (!result.success) {
            withContext(Dispatchers.IO) {
                runCatching { installManager.cancel(task.taskId) }
                installManager.discardStageBundle(task.taskId)
            }
            recordResult(result)
            return false
        }
        withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) {
                installManager.markStaged(task.taskId, path ?: "/data/local/tmp/rearcard-staging/${task.taskId}")
            }
        }
        recordResult(OperationResult(true, "INSTALL_QUEUED",
            "已在暂存区就绪。请打开背屏上的「RearCard 安装管理器」卡片完成安装。"))
        return true
    }

    /** Runs the root dex agent against a staging bundle produced by [InstallManager]. */
    private fun rootInstall(task: InstallTask, bundle: File): Pair<Boolean, String> {
        val dir = File(getApplication<Application>().cacheDir, "install/root-${task.taskId}")
        return try {
            java.util.zip.ZipFile(bundle).use { zip ->
                for (name in arrayOf("rearScreen.mrc", "app_icon.png", "preview.png")) {
                    val entry = zip.getEntry(name) ?: return false to "暂存包缺少 $name"
                    check(dir.isDirectory || dir.mkdirs()) { "无法创建临时目录。" }
                    File(dir, name).outputStream().use { out ->
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                }
            }
            val (ok, out) = RootCardInstaller.count(getApplication(), relayToken())
            val before = out.lineSequence()
                .firstOrNull { it.startsWith("RESULT=") }?.removePrefix("RESULT=")?.trim()?.toIntOrNull()
            require(ok && before != null) { "无法读取卡片数量：$out" }
            if (task.op == InstallOp.UPDATE) {
                RootCardInstaller.update(
                    getApplication(), task.cardId, task.cardName,
                    File(dir, "rearScreen.mrc"), File(dir, "app_icon.png"), File(dir, "preview.png"),
                    themeMagicDir(task.cardId), task.resourceRevision, relayToken(), before,
                )
            } else {
                RootCardInstaller.install(
                    getApplication(), task.cardId, task.cardName,
                    File(dir, "rearScreen.mrc"), File(dir, "app_icon.png"), File(dir, "preview.png"),
                    themeMagicDir(task.cardId), task.resourceRevision, relayToken(), before + 1,
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Root-only bootstrap: writes the relay manager card itself as a standalone card. */
    fun installManagerCard() = runOperation {
        val application = getApplication<Application>()
        val rootReady = withContext(Dispatchers.IO) { RootCardInstaller.isRootAvailable() }
        if (!rootReady) {
            recordResult(OperationResult(false, "NO_ROOT", "安装管理器卡需要 root 权限。"))
            return@runOperation
        }
        val outcome = withContext(Dispatchers.IO) {
            val dir = File(application.cacheDir, "install/manager")
            try {
                ManagerCardAssets.writeTo(application, dir)
                val (ok, out) = RootCardInstaller.count(application, relayToken())
                val before = out.lineSequence()
                    .firstOrNull { it.startsWith("RESULT=") }?.removePrefix("RESULT=")?.trim()?.toIntOrNull()
                require(ok && before != null) { "无法读取卡片数量：$out" }
                RootCardInstaller.install(
                    application, MANAGER_CARD_ID, "RearCard 安装管理器",
                    File(dir, "rearScreen.mrc"), File(dir, "app_icon.png"), File(dir, "preview.png"),
                    themeMagicDir(MANAGER_CARD_ID), MANAGER_REVISION, relayToken(), before + 1,
                )
            } finally {
                dir.deleteRecursively()
            }
        }
        if (outcome.first) {
            prefs.edit().putBoolean(PREF_MANAGER_INSTALLED, true).apply()
            mutableState.update { it.copy(managerCardInstalled = true) }
            recordResult(OperationResult(true, "MANAGER_INSTALLED",
                "安装管理器已插入背屏列表，打开它即可执行队列中的安装。"))
        } else {
            recordResult(OperationResult(false, "MANAGER_FAILED", outcome.second))
        }
    }

    private companion object {
        const val MANAGER_CARD_ID = "dev.finall.rearcardbridge.manager"
        const val MANAGER_REVISION = 1L
        const val CARD_ID_PREFIX = "finall-card-"
        const val PREFS = "rearcard"
        const val PREF_INSTALL_MODE = "install_mode"
        const val PREF_MANAGER_INSTALLED = "manager_card_installed"
    }

    /** Recomputes cardInstalled for every module and cardNeedsUpdate where a snapshot exists. */
    private suspend fun refreshCardFlags() {
        // Best-effort cleanup of orphan staging dirs left by finished/cancelled tasks.
        withContext(Dispatchers.IO) {
            val live = state.value.installTasks
                .filter {
                    it.state != dev.finall.rearcardbridge.module.install.InstallTaskState.SUCCEEDED &&
                        it.state != dev.finall.rearcardbridge.module.install.InstallTaskState.FAILED
                }
                .map { it.taskId }.toSet()
            File("/data/local/tmp/rearcard-staging").listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in live) runCatching { client.clearInstallStage(dir.name) }
            }
        }
        val modules = state.value.modules.map { module ->
            val cardId = CARD_ID_PREFIX + module.id
            val installed = withContext(Dispatchers.IO) { installManager.cardInstalled(cardId) }
            module.copy(
                cardInstalled = installed,
                cardNeedsUpdate = if (installed)
                    withContext(Dispatchers.IO) { installManager.cardNeedsUpdate(module.id) } else null,
            )
        }
        mutableState.update { it.copy(modules = modules) }
        // Migration tail: a module still bound to a legacy carrier whose standalone
        // card is now installed gets its carrier resources restored and the binding
        // cleared. Content groups (and thus user data) are untouched.
        modules.filter { it.cardInstalled && it.legacyCardId != null }.forEach { module ->
            val carrierId = module.legacyCardId ?: return@forEach
            if (!migratedCarriers.add(carrierId)) return@forEach
            if (restoreCarrierIfPossible(carrierId)) {
                withContext(Dispatchers.IO) { contentStore.unbindCard(carrierId) }
                mutableState.update { s -> s.copy(modules = s.modules.map {
                    if (it.id == module.id) it.copy(legacyCardId = null, legacyCardTitle = null) else it
                }, status = "已从载体「${module.legacyCardTitle ?: carrierId}」迁移为独立背屏卡。") }
            } else {
                mutableState.update { it.copy(error =
                    "独立背屏卡已安装，但恢复载体「${module.legacyCardTitle ?: carrierId}」的原始资源失败。") }
            }
        }
    }

    private val migratedCarriers = mutableSetOf<String>()

    /**
     * Writes the stored original.zip back over the carrier's rearscreen resource.
     * Returns false when no backup exists or no privileged channel can write it.
     */
    private suspend fun restoreCarrierIfPossible(cardId: String): Boolean {
        val backup = File(getApplication<Application>().getExternalFilesDir(null),
            "backups/$cardId/original.zip")
        if (!backup.isFile) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                if (RootCardInstaller.isRootAvailable()) {
                    RootCardInstaller.restore(getApplication(), cardId, backup.absolutePath, relayToken()).first
                } else if (state.value.shizuku.status == ShizukuStatus.READY) {
                    client.restoreCarrier(cardId, backup).success
                } else false
            }.getOrDefault(false)
        }
    }

    fun cancelInstallTask(taskId: String) = runOperation {
        withContext(Dispatchers.IO) {
            runCatching { client.clearInstallStage(taskId) }
            installManager.cancel(taskId)
            installManager.discardStageBundle(taskId)
        }
        mutableState.update { it.copy(status = "安装任务已取消。") }
    }

    fun reload() = runOperation(queue = true) { refreshModules(); reloadActive() }
    private suspend fun reloadActive() {
        val group = withContext(Dispatchers.IO) {
            contentStore.bindings().firstOrNull { it.id == state.value.activeGroupId }
        }
        if (group != null) loadGroup(group)
    }
    private val relayTokenLock = Any()

    private fun relayToken(): String = synchronized(relayTokenLock) {
        val file = File(getApplication<Application>().filesDir, "relay-token")
        if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() }?.let { return@synchronized it }
        val token = java.util.UUID.randomUUID().toString()
        file.writeText(token)
        token
    }

    private fun themeMagicDir(cardId: String): String =
        "/data/system/theme_magic/users/0/rearScreenAiApp_Theme/$cardId"

    private fun recordResult(result: OperationResult) {
        mutableState.update { it.copy(status = result.message,
            error = result.message.takeUnless { result.success }) }
        onOperationResult?.invoke(result, null)
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
        client.close()
    }
}
