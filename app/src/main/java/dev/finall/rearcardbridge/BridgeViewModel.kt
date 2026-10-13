package dev.finall.rearcardbridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finall.rearcardbridge.live.ContentBinding
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.live.PackageImporter
import dev.finall.rearcardbridge.model.BridgeUiState
import dev.finall.rearcardbridge.model.OperationResult
import dev.finall.rearcardbridge.model.ShizukuStatus
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import dev.finall.rearcardbridge.shizuku.ShizukuCardClient
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
    private val importer = PackageImporter(application)
    private val mutableState = MutableStateFlow(BridgeUiState())
    val state = mutableState.asStateFlow()
    private val operationLock = Mutex()
    private var pendingUri: Uri? = null
    var onOperationResult: ((OperationResult, String?) -> Unit)? = null
    var onOpenRearSettingsRequested: ((String?) -> OperationResult)? = null

    init {
        application.getExternalFilesDir(null)
        runOperation(queue = true) {
            val group = withContext(Dispatchers.IO) {
                contentStore.bindings().firstOrNull()
                    ?: contentStore.createGroup(application.packageName, application.packageName)
            }
            loadGroup(group)
        }
        viewModelScope.launch {
            client.state.collect { connection ->
                val becameReady = connection.status == ShizukuStatus.READY &&
                    mutableState.value.shizuku.status != ShizukuStatus.READY
                mutableState.update { it.copy(shizuku = connection) }
                if (becameReady) refresh()
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

    fun selectGroup(id: String) = runOperation {
        val group = withContext(Dispatchers.IO) { contentStore.bindings().first { it.id == id } }
        loadGroup(group)
        pendingUri?.let { uri -> importPackage(uri, group); pendingUri = null }
    }

    fun receiveExternal(uri: Uri, source: String?, key: String?) = runOperation(queue = true) {
        require(key == null || key.isNotBlank()) { "key 不能为空。" }
        if (source == null) {
            pendingUri = uri
            mutableState.update { it.copy(status = "无法确认分享来源，请选择一个内容分组后导入。") }
            return@runOperation
        }
        pendingUri = null
        val group = withContext(Dispatchers.IO) { contentStore.createGroup(source, key ?: source) }
        loadGroup(group)
        importPackage(uri, group)
    }

    fun receivePackage(uri: Uri) = runOperation(queue = true) {
        val group = withContext(Dispatchers.IO) {
            contentStore.bindings().first { it.id == state.value.activeGroupId }
        }
        importPackage(uri, group)
    }

    private suspend fun importPackage(uri: Uri, group: ContentBinding) {
        val saved = withContext(Dispatchers.IO) {
            synchronized(ContentOperations.lock) { importer.import(uri, group.id) }
        }
        loadGroup(group)
        recordResult(OperationResult(group.cardId != null,
            if (group.cardId != null) "SAVED" else "BINDING_REQUIRED",
            if (group.cardId != null) "${saved.name} · 已保存版本 ${saved.revision}。${state.value.carrierResources?.message.orEmpty()}"
            else "内容已暂存，请为此来源 / key 选择目标并安装载体。"), group.cardId)
    }

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
            status = if (group.cardId == null) "请选择此分组的替换目标。" else "已绑定「${group.cardTitle}」") }
    }

    fun refresh() = runOperation(queue = true) {
        val cards = withContext(Dispatchers.IO) { client.listCards() }
        mutableState.update { old -> old.copy(cards = cards,
            selectedCardId = old.selectedCardId?.takeIf { id -> cards.any { it.id == id && it.canReplace } },
            status = "${cards.size} 个载体资源") }
        reloadActive()
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
                    "这张卡片已绑定其他分组，请选择其他目标。"
                }
                val output = File.createTempFile("carrier-", ".zip", getApplication<Application>().cacheDir)
                val snapshot = File.createTempFile("resources-", ".zip", getApplication<Application>().cacheDir)
                try {
                    val archive = contentStore.archive(groupId)
                    builder.buildLiveCarrier(archive, output, id,
                        "content://dev.finall.rearcardbridge.live/bindings/$groupId/current")
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
        recordResult(OperationResult(true, "CARRIER_WRITTEN", "动态载体已写入「${card.title}」"), id)
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
        if (result.success) { reloadActive(); openSettingsAfterWrite(id); refreshAfterChange() }
    }

    fun confirmReapplied() = runOperation {
        val groupId = requireNotNull(state.value.activeGroupId)
        withContext(Dispatchers.IO) { contentStore.confirmReapplied(groupId) }
        reloadActive()
        mutableState.update { it.copy(status = "已记录你的重新应用确认。") }
    }

    fun reload() = runOperation(queue = true) { reloadActive() }
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
    override fun onCleared() { onOperationResult = null; onOpenRearSettingsRequested = null; client.close() }
}
