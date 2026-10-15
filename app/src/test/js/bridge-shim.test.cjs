const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

const shim = readFileSync(resolve(__dirname, '../../main/assets/bridge-shim.js'), 'utf8');

function loadBridge(submit) {
  const requests = [];
  const intervals = [];
  const window = { BridgeNative: {
    invoke(id, payload) {
      const request = { id, ...JSON.parse(payload) };
      requests.push(request);
      if (submit) submit(request);
    },
  } };
  vm.runInNewContext(shim, {
    window, console,
    setInterval(fn) { intervals.push(fn); },
  });
  return {
    bridge: window.bridge, requests,
    poll() { intervals[0](); },
    complete(id, result) { window.__rearCardBridgeComplete(id, result); },
  };
}

// A held native request stands in for a long network response. No real HTTP or UI needed.
test('pending network request leaves JS and configuration polling responsive', async () => {
  const h = loadBridge();
  let settled = false;
  const network = h.bridge.net.request({ url: 'https://example.com/', timeoutMs: 180000 })
    .then(value => { settled = true; return value; });
  assert.equal(h.requests[0].args.timeoutMs, 180000);

  await new Promise(resolve => setImmediate(resolve));
  assert.equal(settled, false);

  let title;
  h.bridge.config.onChange(config => { title = config.title; });
  h.poll();
  h.poll();
  assert.equal(h.requests.length, 2, 'only one configuration poll may be in flight');
  assert.equal(h.requests[1].op, 'bridge.poll');
  h.complete(h.requests[1].id, { ok: true, value: { configRev: 1, config: { title: 'still responsive' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(title, 'still responsive');
  assert.equal(settled, false);

  h.complete(h.requests[0].id, { ok: true, value: { status: 200, body: 'done' } });
  assert.equal((await network).body, 'done');
});

test('out-of-order responses complete the matching Promises', async () => {
  const h = loadBridge();
  const first = h.bridge.storage.get('one');
  const second = h.bridge.storage.get('two');
  assert.notEqual(h.requests[0].id, h.requests[1].id);
  h.complete(h.requests[1].id, { ok: true, value: 'second' });
  assert.equal(await second, 'second');
  h.complete(h.requests[0].id, { ok: true, value: 'first' });
  assert.equal(await first, 'first');
  h.complete(h.requests[0].id, { ok: true, value: 'duplicate ignored' });
});

test('timeout rejects as result unknown without resubmitting a paid request', async () => {
  const h = loadBridge();
  const request = h.bridge.net.request({ url: 'https://example.com/paid', method: 'POST', timeoutMs: 0 });
  assert.equal(h.requests[0].args.timeoutMs, 0);
  const rejected = assert.rejects(request, error => {
    assert.equal(error.code, 'TIMEOUT');
    assert.match(error.message, /结果未知/);
    return true;
  });
  h.complete(h.requests[0].id, {
    ok: false, error: { code: 'TIMEOUT', message: '网络请求超时，结果未知' },
  });
  await rejected;
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.requests.filter(r => r.op === 'net.request').length, 1);
});

test('native submission failure rejects without retry', async () => {
  const h = loadBridge(() => { throw new Error('bridge unavailable'); });
  await assert.rejects(h.bridge.module.info(), /bridge unavailable/);
  assert.equal(h.requests.length, 1);
});
