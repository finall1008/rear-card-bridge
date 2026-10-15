package dev.finall.rearcardbridge.module

import org.json.JSONObject

/**
 * module.json of a card module package. A module zip contains:
 *   module.json (required), icon.png (optional),
 *   card/index.html + card/main.js (+ assets), optionally webroot/index.html,
 *   and optionally background/main.js for scheduled background refresh.
 */
data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiVersion: Int,
    val description: String,
    val author: String,
    val capabilities: Set<String>,
    val hasWebui: Boolean,
    /** Optional "background": {"intervalMinutes": N}; null when the module does not opt in. */
    val backgroundIntervalMinutes: Int? = null,
) {
    /** Equal versions overwrite directly; only an older package needs user confirmation. */
    fun checkDowngrade(current: ModuleManifest?, allowDowngrade: Boolean = false) {
        if (current != null && versionCode < current.versionCode && !allowDowngrade) {
            throw ModuleDowngradeException(current, this)
        }
    }

    companion object {
        const val API_VERSION = 2
        val KNOWN_CAPABILITIES = setOf("network", "storage", "files")

        fun isValidModuleId(id: String): Boolean =
            id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))

        /** Parses module.json and validates it together with the zip entry names. */
        fun parse(json: String, zipEntryNames: Set<String>): ModuleManifest {
            val obj = try {
                JSONObject(json)
            } catch (error: Exception) {
                throw IllegalArgumentException("module.json 不是有效的 JSON。")
            }
            return validate(
                id = obj.optString("id"),
                name = obj.optString("name"),
                version = obj.optString("version"),
                versionCode = obj.optLong("versionCode", 0L),
                apiVersion = obj.optInt("apiVersion", 0),
                description = obj.optString("description"),
                author = obj.optString("author"),
                capabilities = buildSet {
                    val array = obj.optJSONArray("capabilities")
                    if (array != null) for (i in 0 until array.length()) add(array.optString(i))
                },
                entryNames = zipEntryNames,
                backgroundIntervalMinutes = parseBackgroundInterval(obj),
            )
        }

        /** Reads the optional "background": {"intervalMinutes": N} declaration. */
        private fun parseBackgroundInterval(obj: JSONObject): Int? {
            if (obj.isNull("background")) return null
            val background = obj.optJSONObject("background")
                ?: throw IllegalArgumentException("模块 background 字段必须是对象。")
            val raw = background.opt("intervalMinutes")
            require(raw is Int || (raw is Long && raw in Int.MIN_VALUE..Int.MAX_VALUE)) {
                "模块 background.intervalMinutes 必须是整数。"
            }
            return (raw as Number).toInt()
        }

        /** Pure validation; kept free of org.json so it can run in local unit tests. */
        fun validate(
            id: String,
            name: String,
            version: String,
            versionCode: Long,
            apiVersion: Int,
            description: String,
            author: String,
            capabilities: Set<String>,
            entryNames: Set<String>,
            backgroundIntervalMinutes: Int? = null,
        ): ModuleManifest {
            require(isValidModuleId(id)) { "模块 id 非法：$id" }
            require(name.isNotBlank()) { "模块缺少 name。" }
            require(version.isNotBlank()) { "模块缺少 version。" }
            require(versionCode > 0) { "模块 versionCode 必须大于 0。" }
            require(apiVersion == API_VERSION) { "模块 apiVersion 不受支持，需要 $API_VERSION。" }
            val unknown = capabilities - KNOWN_CAPABILITIES
            require(unknown.isEmpty()) { "模块声明了未知能力：${unknown.joinToString()}" }
            require("card/index.html" in entryNames) { "模块缺少 card/index.html。" }
            require("card/main.js" in entryNames) { "模块缺少 card/main.js。" }
            val hasWebui = entryNames.any { it.startsWith("webroot/") }
            require(!hasWebui || "webroot/index.html" in entryNames) { "模块 webroot/ 缺少 index.html。" }
            if (backgroundIntervalMinutes != null) {
                require(backgroundIntervalMinutes >= 1) { "模块 background.intervalMinutes 必须不小于 1。" }
                require("background/main.js" in entryNames) { "模块声明了 background 但缺少 background/main.js。" }
            }
            return ModuleManifest(id, name, version, versionCode, apiVersion,
                description, author, capabilities, hasWebui, backgroundIntervalMinutes)
        }
    }
}

class ModuleDowngradeException(current: ModuleManifest, incoming: ModuleManifest) : IllegalArgumentException(
    "模块「${incoming.name}」将从 ${current.version}（${current.versionCode}）降级到 " +
        "${incoming.version}（${incoming.versionCode}）。配置和数据会保留，但旧版本可能无法正确读取现有数据。",
)
