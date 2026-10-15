/*
 * 背屏卡片桥 · 模块设置页桥接 API。
 * 在 webroot/index.html 中以第一个脚本引入：<script src="bridge-shim.js"></script>
 * 与背屏卡片内的 bridge API 保持一致；这里的调用经 WebView 原生接口同步执行，
 * 以 Promise 形式返回。桥不可用（例如直接放到浏览器里调试）时 bridge 为 undefined。
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

  function invoke(op, args) {
    return new Promise(function (resolve, reject) {
      var raw;
      try {
        raw = native.invoke(JSON.stringify({ op: op, args: args || {} }));
      } catch (error) {
        reject(toError({ message: String(error) }));
        return;
      }
      var result;
      try {
        result = JSON.parse(raw);
      } catch (error) {
        reject(toError({ code: 'BAD_RESPONSE', message: '桥接返回了无效数据' }));
        return;
      }
      if (result && result.ok) resolve(result.value);
      else reject(toError(result && result.error));
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

  function poll() {
    var raw;
    try { raw = native.poll(); } catch (error) { return; }
    var state;
    try { state = JSON.parse(raw); } catch (error) { return; }
    if (typeof state.configRev === 'number' && state.configRev !== lastConfigRev) {
      lastConfigRev = state.configRev;
      lastConfig = state.config || {};
      fire(configListeners, lastConfig);
    }
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
      requestRefresh: function () { return invoke('card.requestRefresh'); }
    },
    log: {
      info: function (message) { return invoke('log', { level: 'info', message: String(message) }); },
      warn: function (message) { return invoke('log', { level: 'warn', message: String(message) }); },
      error: function (message) { return invoke('log', { level: 'error', message: String(message) }); }
    }
  };
})();
