const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/assets/background-bridge.js'), 'utf8');
function background(snapshot) {
  const calls = [];
  const context = vm.createContext({
    __rearCardBackgroundSnapshotText: JSON.stringify(snapshot),
    BridgeNative: { invoke(id, payload) { calls.push({ id, ...JSON.parse(payload) }); } },
  });
  context.window = context;
  vm.runInContext(source, context);
  return { context, calls, bridge: context.bridge };
}

test('background reads the injected shared snapshot synchronously', () => {
  const { bridge } = background({ count: 7 });
  const snapshot = bridge.card.readSnapshot();
  assert.equal(snapshot.count, 7);
  snapshot.count = 8;
  assert.equal(bridge.card.readSnapshot().count, 7);
});

test('background forwards session cookies and manual redirects through the shared network backend', async () => {
  const { bridge, calls, context } = background(null);
  const options = { url: 'https://example.com/usage', cookies: true, redirect: 'manual' };
  const request = bridge.net.request(options);
  assert.equal(calls[0].op, 'net.request');
  assert.deepEqual(calls[0].args, options);
  const response = { status: 302, url: options.url, headers: { location: ['/login'] }, body: '' };
  context.__rearCardBridgeComplete(calls[0].id, { ok: true, value: response });
  assert.deepEqual(await request, response);
});

test('background save waits for backend acceptance and reports a failed save without retry', async () => {
  const { bridge, calls, context } = background(null);
  let accepted = false;
  const saved = bridge.card.saveSnapshot({ count: 9 }).then(() => { accepted = true; });
  await Promise.resolve();
  assert.equal(accepted, false);
  assert.equal(calls[0].op, 'card.saveSnapshot');
  assert.deepEqual(calls[0].args, { value: { count: 9 } });
  context.__rearCardBridgeComplete(calls[0].id, { ok: true, value: 2 });
  await saved;
  assert.equal(accepted, true);

  const failed = bridge.card.saveSnapshot({ count: 10 });
  context.__rearCardBridgeComplete(calls[1].id,
    { ok: false, error: { code: 'ERROR', message: 'write failed' } });
  await assert.rejects(failed, error => error.code === 'ERROR' && error.message === 'write failed');
  assert.equal(calls.length, 2);
});
