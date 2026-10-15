package dev.finall.rearcardbridge.packageio

/**
 * Permanent bootstrap; application scripts arrive through the native HostVar bridge.
 * When a module-backed carrier supplies __MODULE_ID__, this runtime also exposes the
 * asynchronous `bridge` API: JS writes batched requests into localStorage, the MAML
 * layer forwards them to the bridge app through ContentResolver.call, and results come
 * back through the results ContentProviderBinder as HostVars.
 */
internal object LiveCarrierRuntime {
    fun script(canvasName: String, moduleId: String? = null): String = SOURCE
        .replace("__CANVAS_NAME__", canvasName)
        .replace("__MODULE_ID__", moduleId.orEmpty())

    private val SOURCE = """
(function () {
  var g = window;
  var canvas = document.getElementById('__CANVAS_NAME__');
  var ctx = canvas.getContext('2d');
  var width = g.CANVAS_W || 912, height = g.CANVAS_H || 596;
  var nativeRaf = g.requestAnimationFrame, nativeCancelRaf = g.cancelAnimationFrame;
  var session = null, active = null, seenRevision = -1, pendingLoad = null;

  function runFor(owner, fn, self, args) {
    if (owner && owner.dead) return;
    var previous = active;
    active = owner;
    try { return fn.apply(self, args); }
    finally { active = previous; }
  }
  g.requestAnimationFrame = function (fn) {
    var owner = active;
    var token = nativeRaf(function () {
      if (owner) { var at = owner.frames.indexOf(token); if (at >= 0) owner.frames.splice(at, 1); }
      return runFor(owner, fn, this, arguments);
    });
    if (owner) owner.frames.push(token);
    return token;
  };
  g.cancelAnimationFrame = function (token) { return nativeCancelRaf(token); };

  function wrapTimer(setName, clearName, repeats) {
    var schedule = g[setName], cancel = g[clearName];
    if (typeof schedule !== 'function' || typeof cancel !== 'function') return;
    g[setName] = function (fn) {
      var owner = active, args = Array.prototype.slice.call(arguments);
      if (typeof fn !== 'function') return schedule.apply(g, args);
      var record = { token: null, cancel: cancel };
      args[0] = function () {
        if (owner && !repeats) { var at = owner.timers.indexOf(record); if (at >= 0) owner.timers.splice(at, 1); }
        return runFor(owner, fn, this, arguments);
      };
      record.token = schedule.apply(g, args);
      if (owner) owner.timers.push(record);
      return record.token;
    };
    g[clearName] = function (token) { return cancel.call(g, token); };
  }
  wrapTimer('setTimeout', 'clearTimeout', false);
  wrapTimer('setInterval', 'clearInterval', true);

  function wrapEvents(target) {
    if (!target || typeof target.addEventListener !== 'function') return;
    var add = target.addEventListener, remove = target.removeEventListener, records = [];
    target.addEventListener = function (type, fn, options) {
      var owner = active;
      if (!owner || typeof fn !== 'function') return add.call(target, type, fn, options);
      var record = { type: type, original: fn, options: options, target: target, remove: remove };
      record.wrapped = function () { return runFor(owner, fn, this, arguments); };
      record.untrack = function () { var at = records.indexOf(record); if (at >= 0) records.splice(at, 1); };
      owner.events.push(record); records.push(record);
      return add.call(target, type, record.wrapped, options);
    };
    target.removeEventListener = function (type, fn, options) {
      for (var i = records.length - 1; i >= 0; i--) {
        var record = records[i];
        if (record.type === type && record.original === fn) {
          records.splice(i, 1);
          return remove.call(target, type, record.wrapped, record.options);
        }
      }
      return remove.call(target, type, fn, options);
    };
  }

  function stopPrevious() {
    if (!session) return;
    session.dead = true;
    session.frames.forEach(function (token) { nativeCancelRaf(token); });
    session.timers.forEach(function (record) { record.cancel.call(g, record.token); });
    session.events.forEach(function (record) {
      record.remove.call(record.target, record.type, record.wrapped, record.options);
      record.untrack();
    });
    session = null;
    g.onSensorData = undefined;
  }
  function clear() {
    if (typeof ctx.setTransform === 'function') ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.clearRect(0, 0, width, height);
  }
  function hint(text) {
    clear(); ctx.fillStyle = '#163858'; ctx.fillRect(0, 0, width, height);
    ctx.fillStyle = '#ffffff'; ctx.font = '26px sans-serif';
    ctx.fillText(text, width * 0.34, height * 0.5);
  }

  // ---- bridge API (module-backed carriers only) ----
  var MODULE_ID = '__MODULE_ID__';
  var bridgeEnabled = MODULE_ID.length > 0 && typeof localStorage !== 'undefined' && typeof data !== 'undefined';
  var bs = {
    nextReq: 1, batch: 0, outboxBusy: false, outboxSince: 0, queue: [], pending: {},
    lastRequests: [], lastAcks: [], ackIds: [], config: {}, configRev: -1, refreshRev: -1,
    configListeners: [], refreshListeners: []
  };
  function toError(result) {
    var info = result && result.error || {};
    var error = new Error(info.message || '桥接操作失败');
    error.code = info.code || 'ERROR';
    return error;
  }
  function settle(entry, result) {
    var ok = result && result.ok;
    var fn = ok ? entry.resolve : entry.reject;
    var value = ok ? result.value : toError(result);
    runFor(entry.owner, function () { fn(value); });
  }
  function rejectPending(message) {
    var ids = Object.keys(bs.pending);
    for (var i = 0; i < ids.length; i++) {
      var entry = bs.pending[ids[i]];
      delete bs.pending[ids[i]];
      try { entry.reject(new Error(message)); } catch (ignored) {}
    }
    bs.queue = [];
  }
  function pump() {
    if (!bridgeEnabled || bs.outboxBusy) return;
    if (bs.queue.length === 0 && bs.ackIds.length === 0) return;
    bs.batch += 1;
    var batchId = 'b' + bs.batch;
    var requests = bs.queue, acks = bs.ackIds;
    bs.queue = []; bs.ackIds = [];
    bs.lastRequests = requests; bs.lastAcks = acks;
    bs.outboxBusy = true; bs.outboxSince = Date.now();
    try {
      localStorage.setItem('bridge_outbox', JSON.stringify({
        batch: batchId, module: MODULE_ID, acks: acks, requests: requests
      }));
    } catch (error) {
      bs.outboxBusy = false;
      for (var i = 0; i < requests.length; i++) {
        var entry = bs.pending[requests[i].id];
        if (entry) { delete bs.pending[requests[i].id]; try { entry.reject(error); } catch (ignored) {} }
      }
    }
  }
  function invoke(op, args) {
    if (!bridgeEnabled) return Promise.reject(new Error('bridge API 仅在模块载体中可用'));
    var owner = active;
    var id = 'r' + (bs.nextReq++);
    return new Promise(function (resolve, reject) {
      bs.pending[id] = { resolve: resolve, reject: reject, owner: owner };
      bs.queue.push({ id: id, op: op, args: args || {} });
      pump();
    });
  }
  function onOutboxAck(value) {
    if (!bs.outboxBusy) return;
    if (value === 'b' + bs.batch) { bs.outboxBusy = false; pump(); }
  }
  function applyResults(map) {
    if (!map) return;
    var settled = false;
    for (var id in map) {
      if (!Object.prototype.hasOwnProperty.call(map, id)) continue;
      var entry = bs.pending[id];
      if (!entry) { bs.ackIds.push(id); settled = true; continue; }
      delete bs.pending[id];
      bs.ackIds.push(id);
      settled = true;
      settle(entry, map[id]);
    }
    if (settled) pump();
  }
  function onResults(raw) {
    if (typeof raw !== 'string' || !raw) return;
    var payload;
    try { payload = JSON.parse(raw); } catch (error) { return; }
    if (typeof payload.configRev === 'number' && payload.configRev !== bs.configRev) {
      bs.configRev = payload.configRev;
      bs.config = payload.config || {};
      bs.configListeners.slice().forEach(function (fn) {
        try { fn(bs.config); } catch (error) { console.log('bridge config listener error: ' + error); }
      });
    }
    if (typeof payload.refreshRev === 'number' && payload.refreshRev !== bs.refreshRev) {
      var first = bs.refreshRev < 0;
      bs.refreshRev = payload.refreshRev;
      if (!first) bs.refreshListeners.slice().forEach(function (fn) {
        try { fn(); } catch (error) { console.log('bridge refresh listener error: ' + error); }
      });
    }
    applyResults(payload.results);
  }
  function outboxWatchdog() {
    if (bs.outboxBusy && Date.now() - bs.outboxSince > 5000) {
      // The ack got lost (e.g. provider restarted): resend the same requests as a new batch.
      bs.outboxBusy = false;
      bs.queue = bs.lastRequests.concat(bs.queue);
      bs.ackIds = bs.lastAcks.concat(bs.ackIds);
      bs.lastRequests = []; bs.lastAcks = [];
      pump();
    }
  }
  var bridge = {
    module: {
      info: function () { return invoke('module.info'); }
    },
    config: {
      get: function (key) { return invoke('config.get', key === undefined ? {} : { key: key }); },
      set: function (key, value) { return invoke('config.set', { key: key, value: value }); },
      onChange: function (fn) { if (typeof fn === 'function') bs.configListeners.push(fn); }
    },
    storage: {
      get: function (key) { return invoke('storage.get', { key: key }); },
      set: function (key, value) { return invoke('storage.set', { key: key, value: value }); },
      remove: function (key) { return invoke('storage.remove', { key: key }); },
      keys: function () { return invoke('storage.keys', {}); }
    },
    files: {
      list: function (path) { return invoke('files.list', { path: path || '' }); },
      readText: function (path) { return invoke('files.readText', { path: path }); },
      writeText: function (path, content) { return invoke('files.writeText', { path: path, content: content }); },
      remove: function (path) { return invoke('files.remove', { path: path }); }
    },
    net: {
      request: function (options) { return invoke('net.request', options || {}); }
    },
    card: {
      onRefresh: function (fn) { if (typeof fn === 'function') bs.refreshListeners.push(fn); },
      requestRefresh: function () { return invoke('card.requestRefresh'); }
    },
    log: {
      info: function (message) { return invoke('log', { level: 'info', message: String(message) }); },
      warn: function (message) { return invoke('log', { level: 'warn', message: String(message) }); },
      error: function (message) { return invoke('log', { level: 'error', message: String(message) }); }
    }
  };
  g.bridge = bridge;
  if (bridgeEnabled) {
    try { localStorage.setItem('bridge_outbox', ''); } catch (ignored) {}
    if (typeof setInterval === 'function') setInterval(outboxWatchdog, 5000);
  }

  function receive(raw) {
    if (typeof raw !== 'string' || !raw) return;
    var payload;
    try { payload = JSON.parse(raw); }
    catch (error) { console.log('RearCardBridgeLive payload error: ' + String(error)); return; }
    if (payload.revision === seenRevision) return;
    seenRevision = payload.revision;
    if (pendingLoad !== null) nativeCancelRaf(pendingLoad);
    pendingLoad = nativeRaf(function () {
      pendingLoad = null;
      stopPrevious(); clear();
      rejectPending('卡片脚本已重新加载');
      if (!payload.script) { hint('请在背屏卡片桥导入内容'); return; }
      session = { dead: false, frames: [], timers: [], events: [] };
      active = session;
      try {
        (0, eval)('(function(){\n' + payload.script + '\n})();');
        console.log('RearCardBridgeLive executed revision=' + payload.revision);
      } catch (error) {
        stopPrevious(); hint('脚本执行失败：' + String(error));
        console.log('RearCardBridgeLive script error revision=' + payload.revision + ': ' + String(error));
      } finally { active = null; }
    });
  }

  nativeRaf(function () { if (seenRevision < 0) hint('请在背屏卡片桥导入内容'); });
  if (typeof data !== 'undefined') {
    // These listeners belong to the permanent carrier, not to an imported application.
    data.addEventListener('change', function (event) {
      if (!event || !event.detail) return;
      var path = event.detail.path, value = event.detail.value;
      if (path === 'bridge_live_payload') receive(value);
      else if (path === 'bridge_outbox_ack') onOutboxAck(value);
      else if (path === 'bridge_results') onResults(value);
      else if (path === 'bridge_sync') {
        if (typeof value === 'string' && value) {
          var sync;
          try { sync = JSON.parse(value); } catch (error) { return; }
          applyResults(sync.results);
        }
      }
    });
    wrapEvents(g); wrapEvents(data);
    receive(data.bridge_live_payload);
    if (bridgeEnabled) onResults(data.bridge_results);
  } else {
    nativeRaf(function () { hint('背屏数据接口不可用'); });
  }
  console.log('RearCardBridgeLive carrier ready');
})();
""".trimIndent()
}
