/*
 * 背屏卡片桥 · 后台刷新桥接 API。
 * 由 BackgroundModuleRunner 在每次任务的空白页面里注入，模块无需 <script> 引入。
 * 与设置页 bridge-shim.js 不同：不轮询配置（任务是一次性的），不提供页面导航，
 * card.readSnapshot() 同步返回任务开始时注入的前后台共享快照，
 * card.saveSnapshot() 返回可 await 的 Promise（成功即已写入桥）。
 * 原生接口只提交任务，后台完成后按请求 ID 回调对应 Promise，不阻塞页面 JS。
 * 桥不可用时 bridge 为 undefined。
 */
(function () {
  'use strict';
  var native = window.BridgeNative;
  if (!native || typeof native.invoke !== 'function') return;

  function toError(info) {
    var error = new Error((info && info.message) || '桥接操作失败');
    error.code = (info && info.code) || 'ERROR';
    return error;
  }

  var runId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  var nextId = 0;
  var pending = Object.create(null);

  window.__rearCardBridgeComplete = function (id, result) {
    var request = pending[id];
    if (!request) return;
    delete pending[id];
    if (result && result.ok) request.resolve(result.value);
    else request.reject(toError(result && result.error));
  };

  function invoke(op, args) {
    return new Promise(function (resolve, reject) {
      var id = runId + '-' + (++nextId);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        native.invoke(id, JSON.stringify({ op: op, args: args || {} }));
      } catch (error) {
        delete pending[id];
        reject(toError({ message: String(error) }));
      }
    });
  }

  // Task-start snapshot injected by the runner; kept unchanged for the whole task,
  // so readSnapshot() always reflects what the bridge had when this task began.
  var initialSnapshot = null;
  try {
    var text = window.__rearCardBackgroundSnapshotText;
    if (typeof text === 'string' && text) initialSnapshot = JSON.parse(text);
  } catch (error) { initialSnapshot = null; }

  window.bridge = {
    module: {
      info: function () { return invoke('module.info'); }
    },
    config: {
      get: function (key) { return invoke('config.get', key === undefined ? {} : { key: key }); },
      set: function (key, value) { return invoke('config.set', { key: key, value: value }); }
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
      readSnapshot: function () {
        try {
          return initialSnapshot === null ? null : JSON.parse(JSON.stringify(initialSnapshot));
        } catch (error) { return null; }
      },
      // Unlike the card runtime's synchronous saveSnapshot, the background one must be awaited.
      saveSnapshot: function (value) {
        return invoke('card.saveSnapshot', { value: value === undefined ? null : value });
      },
      requestRefresh: function () { return invoke('card.requestRefresh'); }
    },
    log: {
      info: function (message) { return invoke('log', { level: 'info', message: String(message) }); },
      warn: function (message) { return invoke('log', { level: 'warn', message: String(message) }); },
      error: function (message) { return invoke('log', { level: 'error', message: String(message) }); }
    }
  };
})();
