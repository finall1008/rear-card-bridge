package dev.finall.rearcardbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.finall.rearcardbridge.model.OperationResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finall.rearcardbridge.ui.BridgeScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BridgeViewModel by viewModels()
    private var requestId: String? = null
    private val pickPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.receivePackage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(EXTRA_REQUEST_ID)
        viewModel.onOperationResult = { result, cardId ->
            setResult(if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra("status", result.code).putExtra("message", result.message)
                    .putExtra("card_id", cardId)
                    .putExtra("key", viewModel.state.value.contentKey)
                    .putExtra("revision", viewModel.state.value.contentRevision))
        }
        viewModel.onOpenRearSettingsRequested = ::openRearSettings
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            BridgeScreen(state = state,
                onAuthorize = viewModel::authorize, onRefresh = viewModel::refresh,
                onPickPackage = { pickPackage.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                onSelectCard = viewModel::selectCard, onInstallCarrier = viewModel::installCarrier,
                onRestore = viewModel::restore, onOpenRearSettings = viewModel::openRearSettings,
                onDismissError = viewModel::dismissError,
                onSelectGroup = viewModel::selectGroup)
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, null)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_REQUEST_ID, requestId)
        super.onSaveInstanceState(outState)
    }
    private fun handleIntent(intent: Intent, sourcePackage: String? = callingPackage) {
        val uri: Uri? = when (intent.action) {
            ACTION_UPDATE_CONTENT -> {
                if (intent.getIntExtra("api_version", 0) != 2) {
                    viewModel.rejectInput("不支持的 api_version；请使用 2。")
                    return
                }
                intent.data
            }
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (intent.action == ACTION_UPDATE_CONTENT || intent.action == Intent.ACTION_SEND) {
            if (viewModel.state.value.busy && viewModel.state.value.packageName != null) {
                viewModel.rejectInput("正在处理上一项操作，请结束后重新传入 ZIP。")
                return
            }
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            setResult(Activity.RESULT_CANCELED)
            if (uri == null) viewModel.rejectInput("Intent 未包含整包 content:// URI。")
            else viewModel.receiveExternal(uri, sourcePackage, intent.getStringExtra("key"))
        }
    }
    private fun openRearSettings(cardId: String?): OperationResult {
        val state = viewModel.state.value
        val title = state.cards.firstOrNull { it.id == cardId }?.title
            ?: state.boundCardTitle.takeIf { cardId == state.boundCardId }
        return try {
            startActivity(Intent("miui.intent.action.rearscreen_setting").setClassName(
                "com.android.thememanager", "com.rearScreen.RearScreenSettingActivity"))
            val message = if (title != null) "请移除旧卡后，在「我生成的」重新应用「$title」"
                else "请在「智能应用 → 我生成的」设置载体卡片"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            OperationResult(true, "SETTINGS_OPENED", "背屏设置已打开。")
        } catch (error: Exception) {
            OperationResult(false, "ERROR", "打开背屏设置失败：${error.message}")
        }
    }
    override fun onDestroy() {
        viewModel.onOperationResult = null
        viewModel.onOpenRearSettingsRequested = null
        super.onDestroy()
    }
    companion object {
        const val ACTION_UPDATE_CONTENT = "dev.finall.rearcardbridge.action.UPDATE_CONTENT"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
