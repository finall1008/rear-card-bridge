package dev.finall.rearcardbridge.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import dev.finall.rearcardbridge.BuildConfig
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.module.ModuleBackend
import dev.finall.rearcardbridge.module.ModuleStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Card-side bridge endpoint used by the MAML carrier protocol.
 *
 * The rear-screen host cannot reach the network itself; module scripts batch requests into
 * localStorage, the carrier's MAML tick forwards each batch through [call] (`bridge_batch`),
 * and results come back either synchronously in the returned Bundle (`sync_results`) or — for
 * network requests executed on a background executor — through [query] on
 * `content://dev.finall.rearcardbridge.bridge/results/<groupId>`, which the carrier polls once
 * per second.
 *
 * In-memory async results are bounded and acknowledged by subsequent batches; a process
 * restart loses them and the card-side watchdog resends its batch.
 */
class BridgeProvider : ContentProvider() {
    private val executor = Executors.newCachedThreadPool()
    private val asyncResults = ConcurrentHashMap<String, LinkedHashMap<String, JSONObject>>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (method != "bridge_batch") {
            result.putString("batch", ""); return result
        }
        val context = requireNotNull(context)
        val groupId = arg.orEmpty()
        val moduleId = moduleForGroup(context, groupId) ?: run {
            result.putString("batch", ""); return result
        }
        val raw = extras?.getString("payload").orEmpty()
        val outbox = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            result.putString("batch", ""); return result
        }
        val batch = outbox.optString("batch")
        result.putString("batch", batch)

        val acks = outbox.optJSONArray("acks")
        if (acks != null) {
            val stored = asyncResults[groupId]
            if (stored != null) synchronized(stored) {
                for (i in 0 until acks.length()) stored.remove(acks.optString(i))
            }
        }
        val backend = ModuleBackend(context)
        val sync = JSONObject()
        val requests = outbox.optJSONArray("requests")
        if (requests != null) {
            for (i in 0 until requests.length()) {
                val request = requests.optJSONObject(i) ?: continue
                val id = request.optString("id")
                if (id.isEmpty()) continue
                val op = request.optString("op")
                val args = request.optJSONObject("args")
                if (op == "net.request") {
                    executor.execute {
                        val value = runCatching { backend.execute(moduleId, op, args) }
                            .getOrElse { error ->
                                JSONObject().put("ok", false).put("error",
                                    JSONObject().put("code", "ERROR")
                                        .put("message", error.message ?: "请求失败"))
                            }
                        storeAsyncResult(groupId, id, value)
                        context.contentResolver.notifyChange(resultsUri(groupId), null)
                    }
                } else {
                    sync.put(id, runCatching { backend.execute(moduleId, op, args) }
                        .getOrElse { error ->
                            JSONObject().put("ok", false).put("error",
                                JSONObject().put("code", "ERROR")
                                    .put("message", error.message ?: "操作失败"))
                        })
                }
            }
        }
        // The host only surfaces one HostVar change per MAML cycle to JS, so the
        // standalone bridge_outbox_ack var never arrives. Carry the batch id inside
        // sync_results instead — that var demonstrably reaches the card every time.
        result.putString("sync_results", JSONObject().put("batch", batch).put("results", sync).toString())
        return result
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = requireNotNull(context)
        val segments = uri.pathSegments
        require(uri.authority == AUTHORITY && segments.size == 2 && segments[0] == "results") {
            "未知内容地址。"
        }
        val groupId = segments[1]
        val moduleId = moduleForGroup(context, groupId)
            ?: throw IllegalArgumentException("分组未绑定模块。")
        val store = ModuleStore(context)
        val state = store.readState(moduleId)
        val payload = JSONObject()
            .put("config", store.readConfig(moduleId))
            .put("configRev", state.optLong("configRev", 0L))
            .put("refreshRev", state.optLong("refreshRev", 0L))
        val results = JSONObject()
        val stored = asyncResults[groupId]
        if (stored != null) synchronized(stored) {
            for ((id, value) in stored) results.put(id, value)
        }
        payload.put("results", results)
        // MatrixCursor rows ride a CursorWindow; keep the payload well below its limit.
        var text = payload.toString()
        if (stored != null && text.length > MAX_PAYLOAD_CHARS) synchronized(stored) {
            val iterator = stored.entries.iterator()
            while (iterator.hasNext() && text.length > MAX_PAYLOAD_CHARS) {
                iterator.next(); iterator.remove()
            }
            val trimmed = JSONObject().put("config", store.readConfig(moduleId))
                .put("configRev", state.optLong("configRev", 0L))
                .put("refreshRev", state.optLong("refreshRev", 0L))
            val trimmedResults = JSONObject()
            for ((id, value) in stored) trimmedResults.put(id, value)
            text = trimmed.put("results", trimmedResults).toString()
        }
        return MatrixCursor(arrayOf("payload")).apply {
            addRow(arrayOf<Any>(text))
            setNotificationUri(context.contentResolver, uri)
        }
    }

    private fun storeAsyncResult(groupId: String, id: String, value: JSONObject) {
        val stored = asyncResults.getOrPut(groupId) { LinkedHashMap() }
        synchronized(stored) {
            stored[id] = value
            while (stored.size > MAX_RESULTS_PER_GROUP) {
                val eldest = stored.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next(); eldest.remove()
            }
        }
    }

    /** A group belongs to a module only when it was created by the bridge app itself. */
    private fun moduleForGroup(context: android.content.Context, groupId: String): String? {
        val binding = LiveContentStore(context).bindings().firstOrNull { it.id == groupId }
            ?: return null
        if (binding.sourcePackage != context.packageName) return null
        val moduleId = binding.key
        return if (ModuleStore(context).get(moduleId) != null) moduleId else null
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.results"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("只读接口")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读接口")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读接口")

    companion object {
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".bridge"
        private const val MAX_RESULTS_PER_GROUP = 100
        private const val MAX_PAYLOAD_CHARS = 1_000_000
        fun resultsUri(groupId: String): Uri = Uri.parse("content://$AUTHORITY/results/$groupId")
    }
}
