package dev.finall.rearcardbridge.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeRouteTest {
    @Test
    fun navigationStackRestoresModuleRoutes() {
        val stack: List<BridgeRoute> = listOf(
            BridgeRoute.Root,
            BridgeRoute.ModuleDetail("hello-module"),
            BridgeRoute.Targets("hello-module"),
            BridgeRoute.ModuleSettings("hello-module"),
        )
        val encoded = Json.encodeToString(stack)
        assertEquals(stack, Json.decodeFromString<List<BridgeRoute>>(encoded))
    }
}
