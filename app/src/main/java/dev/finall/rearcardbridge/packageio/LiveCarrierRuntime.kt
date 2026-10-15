package dev.finall.rearcardbridge.packageio

/**
 * Permanent bootstrap; application scripts arrive through the native HostVar bridge.
 * When a module-backed carrier supplies __MODULE_ID__, this runtime also exposes the
 * asynchronous `bridge` API: JS writes batched requests into localStorage, the MAML
 * layer forwards them to the bridge app through ContentResolver.call, and results come
 * back through the results ContentProviderBinder as HostVars.
 *
 * The stock JsCanvas runtime never runs native Promise jobs (verified on device:
 * resolved native Promises never invoke their callbacks). This bootstrap therefore
 * embeds es6-promise (MIT, (c) 2014 Yehuda Katz, Tom Dale, Stefan Penner and
 * contributors) with a custom scheduler and drives everything from one unified frame
 * loop: a single native frame callback drains the async queue, then dispatches module
 * frame callbacks. Module scripts receive the compatible Promise as an eval parameter;
 * they must be compiled with the card build tool so `async`/`await` becomes generator
 * code that settles through the same queue.
 */
internal object LiveCarrierRuntime {
    fun script(canvasName: String, moduleId: String? = null): String = SOURCE
        .replace("__CANVAS_NAME__", canvasName)
        .replace("__MODULE_ID__", moduleId.orEmpty())

    private val SOURCE = """
(function () {
  var g = window;
  !function(t,e){"object"==typeof exports&&"undefined"!=typeof module?module.exports=e():"function"==typeof define&&define.amd?define(e):t.ES6Promise=e()}(this,function(){"use strict";function t(t){var e=typeof t;return null!==t&&("object"===e||"function"===e)}function e(t){return"function"==typeof t}function n(t){W=t}function r(t){z=t}function o(){return function(){return process.nextTick(a)}}function i(){return"undefined"!=typeof U?function(){U(a)}:c()}function s(){var t=0,e=new H(a),n=document.createTextNode("");return e.observe(n,{characterData:!0}),function(){n.data=t=++t%2}}function u(){var t=new MessageChannel;return t.port1.onmessage=a,function(){return t.port2.postMessage(0)}}function c(){var t=setTimeout;return function(){return t(a,1)}}function a(){for(var t=0;t<N;t+=2){var e=Q[t],n=Q[t+1];e(n),Q[t]=void 0,Q[t+1]=void 0}N=0}function f(){try{var t=Function("return this")().require("vertx");return U=t.runOnLoop||t.runOnContext,i()}catch(e){return c()}}function l(t,e){var n=this,r=new this.constructor(v);void 0===r[V]&&x(r);var o=n._state;if(o){var i=arguments[o-1];z(function(){return T(o,r,i,n._result)})}else j(n,r,t,e);return r}function h(t){var e=this;if(t&&"object"==typeof t&&t.constructor===e)return t;var n=new e(v);return w(n,t),n}function v(){}function p(){return new TypeError("You cannot resolve a promise with itself")}function d(){return new TypeError("A promises callback cannot return that same promise.")}function _(t,e,n,r){try{t.call(e,n,r)}catch(o){return o}}function y(t,e,n){z(function(t){var r=!1,o=_(n,e,function(n){r||(r=!0,e!==n?w(t,n):A(t,n))},function(e){r||(r=!0,S(t,e))},"Settle: "+(t._label||" unknown promise"));!r&&o&&(r=!0,S(t,o))},t)}function m(t,e){e._state===Z?A(t,e._result):e._state===${'$'}?S(t,e._result):j(e,void 0,function(e){return w(t,e)},function(e){return S(t,e)})}function b(t,n,r){n.constructor===t.constructor&&r===l&&n.constructor.resolve===h?m(t,n):void 0===r?A(t,n):e(r)?y(t,n,r):A(t,n)}function w(e,n){if(e===n)S(e,p());else if(t(n)){var r=void 0;try{r=n.then}catch(o){return void S(e,o)}b(e,n,r)}else A(e,n)}function g(t){t._onerror&&t._onerror(t._result),E(t)}function A(t,e){t._state===X&&(t._result=e,t._state=Z,0!==t._subscribers.length&&z(E,t))}function S(t,e){t._state===X&&(t._state=${'$'},t._result=e,z(g,t))}function j(t,e,n,r){var o=t._subscribers,i=o.length;t._onerror=null,o[i]=e,o[i+Z]=n,o[i+${'$'}]=r,0===i&&t._state&&z(E,t)}function E(t){var e=t._subscribers,n=t._state;if(0!==e.length){for(var r=void 0,o=void 0,i=t._result,s=0;s<e.length;s+=3)r=e[s],o=e[s+n],r?T(n,r,o,i):o(i);t._subscribers.length=0}}function T(t,n,r,o){var i=e(r),s=void 0,u=void 0,c=!0;if(i){try{s=r(o)}catch(a){c=!1,u=a}if(n===s)return void S(n,d())}else s=o;n._state!==X||(i&&c?w(n,s):c===!1?S(n,u):t===Z?A(n,s):t===${'$'}&&S(n,s))}function M(t,e){try{e(function(e){w(t,e)},function(e){S(t,e)})}catch(n){S(t,n)}}function P(){return tt++}function x(t){t[V]=tt++,t._state=void 0,t._result=void 0,t._subscribers=[]}function C(){return new Error("Array Methods must be provided an Array")}function O(t){return new et(this,t).promise}function k(t){var e=this;return new e(L(t)?function(n,r){for(var o=t.length,i=0;i<o;i++)e.resolve(t[i]).then(n,r)}:function(t,e){return e(new TypeError("You must pass an array to race."))})}function F(t){var e=this,n=new e(v);return S(n,t),n}function Y(){throw new TypeError("You must pass a resolver function as the first argument to the promise constructor")}function q(){throw new TypeError("Failed to construct 'Promise': Please use the 'new' operator, this object constructor cannot be called as a function.")}function D(){var t=void 0;if("undefined"!=typeof global)t=global;else if("undefined"!=typeof self)t=self;else try{t=Function("return this")()}catch(e){throw new Error("polyfill failed because global object is unavailable in this environment")}var n=t.Promise;if(n){var r=null;try{r=Object.prototype.toString.call(n.resolve())}catch(e){}if("[object Promise]"===r&&!n.cast)return}t.Promise=nt}var K=void 0;K=Array.isArray?Array.isArray:function(t){return"[object Array]"===Object.prototype.toString.call(t)};var L=K,N=0,U=void 0,W=void 0,z=function(t,e){Q[N]=t,Q[N+1]=e,N+=2,2===N&&(W?W(a):R())},B="undefined"!=typeof window?window:void 0,G=B||{},H=G.MutationObserver||G.WebKitMutationObserver,I="undefined"==typeof self&&"undefined"!=typeof process&&"[object process]"==={}.toString.call(process),J="undefined"!=typeof Uint8ClampedArray&&"undefined"!=typeof importScripts&&"undefined"!=typeof MessageChannel,Q=new Array(1e3),R=void 0;R=I?o():H?s():J?u():void 0===B&&"function"==typeof require?f():c();var V=Math.random().toString(36).substring(2),X=void 0,Z=1,${'$'}=2,tt=0,et=function(){function t(t,e){this._instanceConstructor=t,this.promise=new t(v),this.promise[V]||x(this.promise),L(e)?(this.length=e.length,this._remaining=e.length,this._result=new Array(this.length),0===this.length?A(this.promise,this._result):(this.length=this.length||0,this._enumerate(e),0===this._remaining&&A(this.promise,this._result))):S(this.promise,C())}return t.prototype._enumerate=function(t){for(var e=0;this._state===X&&e<t.length;e++)this._eachEntry(t[e],e)},t.prototype._eachEntry=function(t,e){var n=this._instanceConstructor,r=n.resolve;if(r===h){var o=void 0,i=void 0,s=!1;try{o=t.then}catch(u){s=!0,i=u}if(o===l&&t._state!==X)this._settledAt(t._state,e,t._result);else if("function"!=typeof o)this._remaining--,this._result[e]=t;else if(n===nt){var c=new n(v);s?S(c,i):b(c,t,o),this._willSettleAt(c,e)}else this._willSettleAt(new n(function(e){return e(t)}),e)}else this._willSettleAt(r(t),e)},t.prototype._settledAt=function(t,e,n){var r=this.promise;r._state===X&&(this._remaining--,t===${'$'}?S(r,n):this._result[e]=n),0===this._remaining&&A(r,this._result)},t.prototype._willSettleAt=function(t,e){var n=this;j(t,void 0,function(t){return n._settledAt(Z,e,t)},function(t){return n._settledAt(${'$'},e,t)})},t}(),nt=function(){function t(e){this[V]=P(),this._result=this._state=void 0,this._subscribers=[],v!==e&&("function"!=typeof e&&Y(),this instanceof t?M(this,e):q())}return t.prototype["catch"]=function(t){return this.then(null,t)},t.prototype["finally"]=function(t){var n=this,r=n.constructor;return e(t)?n.then(function(e){return r.resolve(t()).then(function(){return e})},function(e){return r.resolve(t()).then(function(){throw e})}):n.then(t,t)},t}();return nt.prototype.then=l,nt.all=O,nt.race=k,nt.resolve=h,nt.reject=F,nt._setScheduler=n,nt._setAsap=r,nt._asap=z,nt.polyfill=D,nt.Promise=nt,nt});
  var CompatiblePromise = g.ES6Promise.Promise;
  g.__rearCardBridgePromise = CompatiblePromise;
  var canvas = document.getElementById('__CANVAS_NAME__');
  var ctx = canvas.getContext('2d');
  var width = g.CANVAS_W || 912, height = g.CANVAS_H || 596;
  var nativeRaf = g.requestAnimationFrame;
  var session = null, active = null, seenRevision = -1, loadGeneration = 0;

  // Unified frame loop: exactly one native frame callback drains the async
  // queue (Promise continuations and one-shot tasks), then dispatches module
  // frame callbacks. The stock JsCanvas runtime never executes native Promise
  // jobs, so all module async code must settle through this queue instead.
  var asyncQueue = [], frameCallbacks = {}, nextFrameToken = 0;
  CompatiblePromise._setScheduler(function (fn) { asyncQueue.push(fn); });
  function scheduleAsync(fn) { asyncQueue.push(fn); }
  // Drain until empty, like a microtask checkpoint: during AOD recovery the host
  // grants only one or two frames, so chained continuations cannot wait a frame each.
  function drainAsync() {
    for (var rounds = 0; asyncQueue.length > 0 && rounds < 1000; rounds++) {
      var work = asyncQueue;
      asyncQueue = [];
      for (var i = 0; i < work.length; i++) {
        try { work[i](); }
        catch (error) { console.log('RearCardBridgeLive async error: ' + String(error)); }
      }
    }
  }
  function mainLoop() {
    drainAsync();
    var now = Date.now();
    var tokens = Object.keys(frameCallbacks);
    for (var i = 0; i < tokens.length; i++) {
      var record = frameCallbacks[tokens[i]];
      delete frameCallbacks[tokens[i]];
      if (!record) continue;
      var owner = record.owner;
      if (owner && !owner.dead) {
        var at = owner.frames.indexOf(record.token);
        if (at >= 0) owner.frames.splice(at, 1);
        runFor(owner, record.fn, null, [now]);
      }
    }
    nativeRaf(mainLoop);
  }

  function runFor(owner, fn, self, args) {
    if (owner && owner.dead) return;
    var previous = active;
    active = owner;
    try { return fn.apply(self, args); }
    finally { active = previous; }
  }
  // Promise continuations run from the async queue with no active owner; they
  // still belong to the current module session, so attribute them to it.
  // Otherwise their frame callbacks were dropped and the module never redrew.
  function currentOwner() { return active || session; }
  g.requestAnimationFrame = function (fn) {
    var owner = currentOwner();
    if (typeof fn !== 'function') return 0;
    var token = ++nextFrameToken;
    frameCallbacks[token] = { token: token, owner: owner, fn: fn };
    if (owner) owner.frames.push(token);
    return token;
  };
  g.cancelAnimationFrame = function (token) { delete frameCallbacks[token]; };

  function wrapTimer(setName, clearName, repeats) {
    var schedule = g[setName], cancel = g[clearName];
    if (typeof schedule !== 'function' || typeof cancel !== 'function') return;
    g[setName] = function (fn) {
      var owner = currentOwner(), args = Array.prototype.slice.call(arguments);
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
      var owner = currentOwner();
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
    session.frames.forEach(function (token) { delete frameCallbacks[token]; });
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
  // Batch ids must be unique per runtime instance: bridge_outbox_ack is a MAML
  // variable that survives JS reloads, so a reused id would be accepted as a
  // stale ack and silently drop the batch until the watchdog resends it.
  var bs = {
    nextReq: 1, batch: 0,
    batchPrefix: 'b' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1679616).toString(36) + '-',
    reqPrefix: 'r' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1679616).toString(36) + '-',
    outboxBusy: false, outboxSince: 0, queue: [], pending: {},
    lastRequests: [], lastAcks: [], ackIds: [], config: {}, configRev: -1, refreshRev: -1,
    configListeners: [], refreshListeners: [],
    // Shared snapshot state: snapshotRev is the backend revision last reflected in
    // localStorage; snapshotPending counts in-flight card.saveSnapshot submissions so a
    // payload carrying the still-older backend copy cannot clobber a fresh local write.
    snapshotRev: -1, snapshotPending: 0
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
  // Acks ride along with the next request batch; an acks-only batch is sent only
  // from the idle timer. Otherwise every result triggered an acks-only round trip
  // that held outboxBusy and made the module's next request wait behind it.
  function pump(flushAcks) {
    if (!bridgeEnabled || bs.outboxBusy) return;
    if (bs.queue.length === 0 && !(flushAcks && bs.ackIds.length > 0)) return;
    bs.batch += 1;
    var batchId = bs.batchPrefix + bs.batch;
    var requests = bs.queue, acks = bs.ackIds;
    bs.queue = []; bs.ackIds = [];
    bs.lastRequests = requests; bs.lastAcks = acks;
    bs.outboxBusy = true; bs.outboxSince = Date.now();
    var outbox = JSON.stringify({
      batch: batchId, module: MODULE_ID, acks: acks, requests: requests
    });
    try {
      localStorage.setItem('bridge_outbox', outbox);
    } catch (error) {
      bs.outboxBusy = false;
      for (var i = 0; i < requests.length; i++) {
        var entry = bs.pending[requests[i].id];
        if (entry) { delete bs.pending[requests[i].id]; try { entry.reject(error); } catch (ignored) {} }
      }
    }
  }
  function invoke(op, args) {
    if (!bridgeEnabled) return CompatiblePromise.reject(new Error('bridge API 仅在模块载体中可用'));
    var owner = active;
    // Unique per instance: leftover results from a previous session must never
    // resolve a new request that happens to reuse the same small integer id.
    var id = bs.reqPrefix + (bs.nextReq++);
    return new CompatiblePromise(function (resolve, reject) {
      bs.pending[id] = { resolve: resolve, reject: reject, owner: owner };
      bs.queue.push({ id: id, op: op, args: args || {} });
      schedulePump();
    });
  }
  // On the rear host, outbox writes made synchronously inside a host data-change
  // callback were never seen by the MAML tick, while every write made from the
  // frame loop or a timer was. Outbox writes therefore always run from the loop.
  var pumpScheduled = false;
  function schedulePump() {
    if (pumpScheduled) return;
    pumpScheduled = true;
    scheduleAsync(function () { pumpScheduled = false; pump(); });
  }
  function currentBatchId() { return bs.batchPrefix + bs.batch; }
  function onOutboxAck(value) {
    if (!bs.outboxBusy) return;
    if (value === currentBatchId()) { bs.outboxBusy = false; schedulePump(); }
  }
  function onSync(value) {
    if (typeof value !== 'string' || !value) return;
    var sync;
    try { sync = JSON.parse(value); } catch (error) { return; }
    if (bs.outboxBusy && sync.batch === currentBatchId()) bs.outboxBusy = false;
    applyResults(sync.results);
    schedulePump();
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
    if (settled) schedulePump();
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
    if (bs.outboxBusy && Date.now() - bs.outboxSince > 3000) {
      // The ack got lost (e.g. provider restarted): resend the same requests as a new batch.
      bs.outboxBusy = false;
      bs.queue = bs.lastRequests.concat(bs.queue);
      bs.ackIds = bs.lastAcks.concat(bs.ackIds);
      bs.lastRequests = []; bs.lastAcks = [];
      pump();
      return;
    }
    pump(true);
  }
  // The same localStorage entry is read synchronously by the module's first frame.
  function applySnapshot(value) {
    try {
      var text = JSON.stringify(value);
      if (localStorage.getItem('bridge_snapshot') !== text) localStorage.setItem('bridge_snapshot', text);
    } catch (error) { console.log('RearCardBridgeLive snapshot write error: ' + String(error)); }
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
      requestRefresh: function () { return invoke('card.requestRefresh'); },
      // Synchronous display snapshot. The host rebuilds the JS runtime on every AOD
      // recovery and grants only one or two frames, so the first meaningful frame
      // must be drawn from data available before any bridge round trip. The backend
      // copy (written by background refresh) is injected into localStorage by
      // receive() before the module script executes, so the first readSnapshot
      // already sees it without any startup request.
      readSnapshot: function () {
        try {
          var text = localStorage.getItem('bridge_snapshot');
          return text ? JSON.parse(text) : null;
        } catch (error) { return null; }
      },
      // The local write is synchronous and completes before this returns. When the
      // value actually changes and the bridge is enabled, the same value is also
      // submitted to the shared backend store (card.saveSnapshot) and this returns a
      // Promise resolving with the new backend revision once accepted (rejecting when
      // the shared copy failed). Identical content is neither rewritten locally nor
      // re-uploaded: cached card state is never pushed on its own — only an explicit,
      // value-changing call syncs the shared copy.
      saveSnapshot: function (value) {
        var text = JSON.stringify(value === undefined ? null : value);
        if (localStorage.getItem('bridge_snapshot') === text) return;
        localStorage.setItem('bridge_snapshot', text);
        if (!bridgeEnabled) return;
        bs.snapshotPending += 1;
        return invoke('card.saveSnapshot', { value: value === undefined ? null : value })
          .then(function (rev) {
            bs.snapshotPending -= 1;
            bs.snapshotRev = Math.max(bs.snapshotRev, rev);
            return rev;
          }, function (error) {
            bs.snapshotPending -= 1;
            console.log('RearCardBridgeLive snapshot save error: ' + String(error));
            throw error;
          });
      }
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
    if (typeof setInterval === 'function') setInterval(outboxWatchdog, 1000);
  }

  function receive(raw) {
    if (typeof raw !== 'string' || !raw) return;
    var payload;
    try { payload = JSON.parse(raw); }
    catch (error) { console.log('RearCardBridgeLive payload error: ' + String(error)); return; }
    // Shared snapshot: handled independently of the script revision, before the
    // revision check below, so a backend save refreshes localStorage without
    // re-executing the module script. Applied synchronously —
    // on the first payload the module script's readSnapshot already sees the
    // backend copy. While a local save is in flight the polled payload may still
    // carry the older backend copy, so it must not clobber the fresh local write.
    if (typeof payload.snapshotRev === 'number' && payload.snapshotRev > bs.snapshotRev &&
        bs.snapshotPending === 0 && payload.snapshot !== undefined) {
      bs.snapshotRev = payload.snapshotRev;
      applySnapshot(payload.snapshot);
    }
    if (payload.revision === seenRevision) return;
    seenRevision = payload.revision;
    var generation = ++loadGeneration;
    scheduleAsync(function () {
      if (generation !== loadGeneration) return;
      stopPrevious(); clear();
      rejectPending('卡片脚本已重新加载');
      if (!payload.script) { hint('请在背屏卡片桥导入内容'); return; }
      session = { dead: false, frames: [], timers: [], events: [] };
      active = session;
      try {
        // Module scripts (compiled with the card build tool) reference the
        // compatible Promise through this parameter; the broken native
        // Promise in the host runtime stays untouched.
        (0, eval)('(function(Promise){\n' + payload.script + '\n})(window.__rearCardBridgePromise);');
        console.log('RearCardBridgeLive executed revision=' + payload.revision);
      } catch (error) {
        stopPrevious(); hint('脚本执行失败：' + String(error));
        console.log('RearCardBridgeLive script error revision=' + payload.revision + ': ' + String(error));
      } finally { active = null; }
    });
  }

  scheduleAsync(function () { if (seenRevision < 0) hint('请在背屏卡片桥导入内容'); });
  if (typeof data !== 'undefined') {
    // These listeners belong to the permanent carrier, not to an imported application.
    data.addEventListener('change', function (event) {
      if (!event || !event.detail) return;
      var path = event.detail.path, value = event.detail.value;
      if (path === 'bridge_live_payload') receive(value);
      else if (path === 'bridge_outbox_ack') onOutboxAck(value);
      else if (path === 'bridge_results') onResults(value);
      else if (path === 'bridge_sync') onSync(value);
    });
    wrapEvents(g); wrapEvents(data);
    receive(data.bridge_live_payload);
    if (bridgeEnabled) onResults(data.bridge_results);
  } else {
    scheduleAsync(function () { hint('背屏数据接口不可用'); });
  }
  nativeRaf(mainLoop);
  console.log('RearCardBridgeLive carrier ready');
})();
""".trimIndent()
}
