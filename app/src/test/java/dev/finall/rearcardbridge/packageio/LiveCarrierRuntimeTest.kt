package dev.finall.rearcardbridge.packageio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCarrierRuntimeTest {

    @Test
    fun runtimeUsesCompatiblePromiseDrivenBySingleFrameLoop() {
        val script = LiveCarrierRuntime.script("canvas_x", "dev.example.weather")
        // es6-promise with an explicit scheduler: the stock host never runs native Promise jobs.
        assertTrue(script.contains("._setScheduler(function (fn) { asyncQueue.push(fn); })"))
        // One native frame entry drains the async queue and dispatches module frame callbacks.
        assertTrue(script.contains("function mainLoop()"))
        assertTrue(script.contains("drainAsync();"))
        assertTrue(script.contains("nativeRaf(mainLoop);"))
        // Module scripts receive the compatible Promise; the broken native one stays untouched.
        assertTrue(script.contains("(function(Promise)"))
        assertTrue(script.contains("window.__rearCardBridgePromise"))
        assertTrue(script.contains("new CompatiblePromise(function (resolve, reject)"))
        // Batch ids carry a per-instance prefix: bridge_outbox_ack survives JS reloads,
        // and a stale ack matching a reused id silently drops the batch until the watchdog.
        assertTrue(script.contains("batchPrefix: 'b' + Date.now().toString(36)"))
        assertTrue(script.contains("var batchId = bs.batchPrefix + bs.batch;"))
        // The ack rides inside sync_results (the host pushes only one HostVar
        // change per cycle, so a standalone ack var gets shadowed and lost).
        assertTrue(script.contains("if (bs.outboxBusy && sync.batch === currentBatchId())"))
        // Outbox writes are deferred to the frame loop: writes made inside a host
        // data-change callback were never seen by the carrier's MAML tick.
        assertTrue(script.contains("scheduleAsync(function () { pumpScheduled = false; pump(); });"))
        // Frame callbacks requested from Promise continuations belong to the current session.
        assertTrue(script.contains("function currentOwner() { return active || session; }"))
        // AOD recovery rebuilds the runtime; modules redraw synchronously from this snapshot.
        assertTrue(script.contains("readSnapshot: function ()"))
        assertTrue(script.contains("localStorage.getItem('bridge_snapshot')"))
        // Module frame registrations are dispatched by the loop, not registered natively.
        assertTrue(script.contains("frameCallbacks[token] = { token: token, owner: owner, fn: fn };"))
    }

    @Test
    fun scriptInjectsCanvasNameAndModuleIdOnly() {
        val withModule = LiveCarrierRuntime.script("my_canvas", "dev.example.weather")
        assertTrue(withModule.contains("document.getElementById('my_canvas')"))
        assertTrue(withModule.contains("var MODULE_ID = 'dev.example.weather';"))
        val plain = LiveCarrierRuntime.script("other_canvas")
        assertTrue(plain.contains("var MODULE_ID = '';"))
        assertFalse(plain.contains("__CANVAS_NAME__"))
        assertFalse(plain.contains("__MODULE_ID__"))
    }

    @Test
    fun scriptIsStableAndXmlSafe() {
        val a = LiveCarrierRuntime.script("c", "m")
        val b = LiveCarrierRuntime.script("c", "m")
        assertEquals(a, b)
        assertFalse(a.contains("]]>"))
    }
}
