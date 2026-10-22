package dev.finall.rearcardbridge.module.install

import org.json.JSONObject

enum class InstallOp { INSTALL, UPDATE, REMOVE }

enum class InstallTaskState { PENDING, CLAIMED, SUCCEEDED, FAILED }

data class InstallTask(
    val taskId: String,
    val op: InstallOp,
    val groupId: String,
    val cardId: String,
    val cardName: String,
    val resourceRevision: Long,
    val stagingPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long,
    val state: InstallTaskState,
    val resultCode: String?,
    val resultMessage: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("taskId", taskId)
        .put("op", op.name)
        .put("groupId", groupId)
        .put("cardId", cardId)
        .put("cardName", cardName)
        .put("resourceRevision", resourceRevision)
        .put("stagingPath", stagingPath ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("state", state.name)
        .put("resultCode", resultCode ?: JSONObject.NULL)
        .put("resultMessage", resultMessage ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): InstallTask = InstallTask(
            taskId = json.getString("taskId"),
            op = InstallOp.valueOf(json.getString("op")),
            groupId = json.getString("groupId"),
            cardId = json.getString("cardId"),
            cardName = json.getString("cardName"),
            resourceRevision = json.getLong("resourceRevision"),
            stagingPath = json.stringOrNull("stagingPath"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            state = InstallTaskState.valueOf(json.getString("state")),
            resultCode = json.stringOrNull("resultCode"),
            resultMessage = json.stringOrNull("resultMessage"),
        )
    }
}

data class InstallOutcome(val success: Boolean, val code: String, val message: String)

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
