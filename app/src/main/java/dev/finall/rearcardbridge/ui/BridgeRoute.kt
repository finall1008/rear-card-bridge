package dev.finall.rearcardbridge.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
internal sealed interface BridgeRoute : NavKey {
    @Serializable
    data object Root : BridgeRoute

    @Serializable
    data class ModuleDetail(val moduleId: String) : BridgeRoute

    @Serializable
    data class ModuleSettings(val moduleId: String) : BridgeRoute
}
