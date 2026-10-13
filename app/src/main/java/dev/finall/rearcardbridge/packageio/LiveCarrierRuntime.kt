package dev.finall.rearcardbridge.packageio

/** Permanent bootstrap; application scripts arrive through the native HostVar bridge. */
internal object LiveCarrierRuntime {
    fun script(canvasName: String): String = SOURCE.replace("__CANVAS_NAME__", canvasName)

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
    // This listener belongs to the permanent carrier, not to an imported application.
    data.addEventListener('change', function (event) {
      if (event && event.detail && event.detail.path === 'bridge_live_payload') receive(event.detail.value);
    });
    wrapEvents(g); wrapEvents(data);
    receive(data.bridge_live_payload);
  } else {
    nativeRaf(function () { hint('背屏数据接口不可用'); });
  }
  console.log('RearCardBridgeLive carrier ready');
})();
""".trimIndent()
}
