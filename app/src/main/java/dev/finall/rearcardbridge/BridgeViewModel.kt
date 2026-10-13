package dev.finall.rearcardbridge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finall.rearcardbridge.live.LiveContentStore
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
    private val preferences = application.getSharedPreferences("carrier", 0)
    private val mutableState = MutableStateFlow(BridgeUiState(
        boundCardId = preferences.getString("id", null),
        boundCardTitle = preferences.getString("title", null),
        selectedCardId = preferences.getString("id", null),
    ))
    val state = mutableState.asStateFlow()
    private val operationLock = Mutex()
    var onOperationResult: ((OperationResult, String?) -> Unit)? = null
    var onOpenRearSettingsRequested: ((String?) -> OperationResult)? = null

    init {
        application.getExternalFilesDir(null)
        runOperation(queue = true) {
            val saved = withContext(Dispatchers.IO) { contentStore.current() }
            if (saved != null) mutableState.update { it.copy(packageName = saved.name,
                packageEntries = saved.entries, contentRevision = saved.revision,
                status = "${saved.name} · 已保存版本 ${saved.revision}") }
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

    fun refresh() = runOperation(queue = true) {
        val cards = withContext(Dispatchers.IO) { client.listCards() }
        mutableState.update { old -> old.copy(cards = cards,
            selectedCardId = old.selectedCardId?.takeIf { id -> cards.any { it.id == id && it.canReplace } },
            status = "${cards.size} 个载体资源") }
    }

    // Importing is a local publish operation, independent of Shizuku and the native card database.
    fun receivePackage(uri: Uri) = runOperation(queue = true) {
        require(uri.scheme == "content") { "仅接受 content:// ZIP 文件。" }
        val saved = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "card.zip"
            val file = File.createTempFile("incoming-", ".zip", getApplication<Application>().cacheDir)
            try {
                requireNotNull(resolver.openInputStream(uri)) { "无法读取 ZIP 文件。" }.use { source ->
                    file.outputStream().use { source.copyTo(it) }
                }
                val entries = builder.inspect(file)
                val script = builder.extractScript(file)
                contentStore.save(name, entries, script, file)
            } finally { file.delete() }
        }
        mutableState.update { it.copy(packageName = saved.name, packageEntries = saved.entries,
            contentRevision = saved.revision) }
        recordResult(OperationResult(true, "SAVED", "${saved.name} · 已保存版本 ${saved.revision}"), state.value.boundCardId)
    }

    fun installCarrier() = runOperation {
        val id = requireNotNull(state.value.selectedCardId) { "请选择载体卡片。" }
        val card = state.value.cards.firstOrNull { it.id == id && it.canReplace }
            ?: error("目标不可写，请刷新资源。")
        val result = withContext(Dispatchers.IO) {
            val output = File.createTempFile("carrier-", ".zip", getApplication<Application>().cacheDir)
            try {
                builder.buildLiveCarrier(contentStore.archive(), output, id)
                client.replaceCard(id, output)
            } finally { output.delete() }
        }
        if (!result.success) { recordResult(result, id); return@runOperation }
        preferences.edit().putString("id", id).putString("title", card.title).apply()
        mutableState.update { it.copy(boundCardId = id, boundCardTitle = card.title, lastReplacedCardId = id) }
        recordResult(OperationResult(true, "CARRIER_WRITTEN", "动态载体已写入「${card.title}」"), id)
        openSettingsAfterWrite(id)
        refreshAfterChange()
    }

    fun restore(id: String) = runOperation {
        val result = withContext(Dispatchers.IO) { client.restoreCard(id) }
        recordResult(result, id)
        if (result.success) {
            if (state.value.boundCardId == id) {
                preferences.edit().clear().apply()
                mutableState.update { it.copy(boundCardId = null, boundCardTitle = null) }
            }
            openSettingsAfterWrite(id)
            refreshAfterChange()
        }
    }

    fun openRearSettings(id: String? = state.value.boundCardId) = runOperation {
        recordResult(requestRearSettings(id), id)
    }
    private fun requestRearSettings(id: String?): OperationResult =
        onOpenRearSettingsRequested?.invoke(id)
            ?: OperationResult(false, "ERROR", "无法打开背屏设置。")

    private fun openSettingsAfterWrite(id: String) {
        val result = requestRearSettings(id)
        if (!result.success) mutableState.update { it.copy(error = result.message) }
    }
    private suspend fun refreshAfterChange() {
        try {
            val cards = withContext(Dispatchers.IO) { client.listCards() }
            mutableState.update { it.copy(cards = cards) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutableState.update { it.copy(error = "资源操作已完成，但刷新列表失败：${error.message}") }
        }
    }
    private fun recordResult(result: OperationResult, cardId: String? = state.value.boundCardId) {
        mutableState.update { it.copy(status = result.message, error = result.message.takeUnless { result.success }) }
        onOperationResult?.invoke(result, cardId)
    }
    private fun runOperation(queue: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy && !queue) return
        viewModelScope.launch {
            operationLock.withLock {
                mutableState.update { it.copy(busy = true, error = null) }
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordResult(OperationResult(false, "ERROR", error.message ?: "操作失败"))
                } finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }
    override fun onCleared() {
        onOperationResult = null
        onOpenRearSettingsRequested = null
        client.close()
    }
}
