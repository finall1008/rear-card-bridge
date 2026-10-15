// 桥定时调用 refresh；与 card/main.js 保存完全相同的快照格式，不需要绘图。
async function refresh() {
  const config = await bridge.config.get();
  const count = await bridge.storage.get('count');
  await bridge.card.saveSnapshot({
    label: config.title || '计数器',
    count: typeof count === 'number' ? count : 0
  });
}
