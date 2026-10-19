/*
 * 背屏卡片桥 · 模块设置页桥接 API。
 * 在 webroot/index.html 中以第一个脚本引入：<script src="bridge-shim.js"></script>
 * 提供与背屏共享的数据、卡片操作和原生网络；普通网页请求仍可直接用 fetch / XMLHttpRequest。
 * 原生接口只提交任务，后台完成后按请求 ID
 * 回调对应 Promise，不阻塞网页 JS。桥不可用时 bridge 为 undefined。
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

  // A new page must not consume a completion belonging to the previous page.
  var pageId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
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
      var id = pageId + '-' + (++nextId);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        native.invoke(id, JSON.stringify({ op: op, args: args || {} }));
      } catch (error) {
        delete pending[id];
        reject(toError({ message: String(error) }));
      }
    });
  }

  var configListeners = [];
  var refreshListeners = [];
  var lastConfigRev = -1;
  var lastConfig = {};

  function fire(list, arg) {
    list.slice().forEach(function (fn) {
      try { fn(arg); } catch (error) { console.error('bridge listener error', error); }
    });
  }

  var polling = false;
  function poll() {
    if (polling) return;
    polling = true;
    invoke('bridge.poll').then(function (state) {
      polling = false;
      if (typeof state.configRev === 'number' && state.configRev !== lastConfigRev) {
        lastConfigRev = state.configRev;
        lastConfig = state.config || {};
        fire(configListeners, lastConfig);
      }
    }, function () { polling = false; });
  }
  setInterval(poll, 1000);

  window.bridge = {
    module: {
      info: function () { return invoke('module.info'); }
    },
    config: {
      get: function (key) { return invoke('config.get', key === undefined ? {} : { key: key }); },
      set: function (key, value) { return invoke('config.set', { key: key, value: value }); },
      onChange: function (fn) { if (typeof fn === 'function') configListeners.push(fn); }
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
      onRefresh: function (fn) { if (typeof fn === 'function') refreshListeners.push(fn); },
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
