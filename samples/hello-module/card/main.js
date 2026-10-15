(function () {
  'use strict';
  var canvas = document.getElementById('rear');
  var ctx = canvas.getContext('2d');
  var W = window.CANVAS_W || 912;
  var H = window.CANVAS_H || 596;
  var state = { count: 0, label: '加载中…' };
  var queued = false;

  function requestDraw() {
    if (queued) return;
    queued = true;
    window.requestAnimationFrame(function () { queued = false; draw(); });
  }

  function draw() {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#10142b';
    ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = '#f2f3f7';
    ctx.font = 'bold 64px sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText(state.label, 360, 120);
    ctx.font = 'bold 160px sans-serif';
    ctx.fillText(String(state.count), 360, 320);
    ctx.font = '24px sans-serif';
    ctx.fillStyle = '#8b93b8';
    ctx.fillText('在卡片桥的模块设置里修改标题 / 增加计数', 360, 480);
  }

  // 源码直接用原生 async/await；打包前必须用 tools/card-build 编译（见 docs/module-development.md）。
  async function load() {
    if (typeof bridge === 'undefined') { state.label = '桥不可用'; requestDraw(); return; }
    try {
      var config = await bridge.config.get();
      state.label = config.title || '计数器';
      var count = await bridge.storage.get('count');
      state.count = typeof count === 'number' ? count : 0;
      requestDraw();
      bridge.card.saveSnapshot({ label: state.label, count: state.count });
    } catch (error) {
      state.label = '读取失败';
      bridge.log.error('加载失败: ' + error);
      requestDraw();
    }
  }

  // 息屏常显只给最初一两帧：先同步画出前台或后台保存的同一份快照。
  var snapshot = bridge.card.readSnapshot();
  if (snapshot && typeof snapshot.label === 'string' && typeof snapshot.count === 'number') {
    state = snapshot;
    draw();
  }
  bridge.config.onChange(load);
  bridge.card.onRefresh(load);
  load();
  requestDraw();
})();
