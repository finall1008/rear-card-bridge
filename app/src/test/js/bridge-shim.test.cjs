const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

const shim = readFileSync(resolve(__dirname, '../../main/assets/bridge-shim.js'), 'utf8');

function loadBridge(submit) {
  const requests = [];
  const intervals = [];
  const fetch = () => Promise.resolve({ ok: true });
  const XMLHttpRequest = function () {};
  const window = { fetch, XMLHttpRequest, BridgeNative: {
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
    bridge: window.bridge, requests, window, fetch, XMLHttpRequest,
    poll() { intervals[0](); },
    complete(id, result) { window.__rearCardBridgeComplete(id, result); },
  };
}

// A held storage request must not block JS or the asynchronous configuration poll.
test('pending data request leaves JS and configuration polling responsive', async () => {
  const h = loadBridge();
  let settled = false;
  const data = h.bridge.storage.get('count')
    .then(value => { settled = true; return value; });
  assert.equal(h.requests[0].op, 'storage.get');

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

  h.complete(h.requests[0].id, { ok: true, value: 42 });
  assert.equal(await data, 42);
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

test('WebUI exposes session networking without replacing browser network APIs', async () => {
  const h = loadBridge();
  const response = h.bridge.net.request({ url: 'https://example.com/', cookies: true, redirect: 'manual' });
  assert.equal(h.requests[0].op, 'net.request');
  assert.equal(h.requests[0].args.cookies, true);
  assert.equal(h.requests[0].args.redirect, 'manual');
  h.complete(h.requests[0].id, { ok: true, value: { status: 302, url: 'https://example.com/', headers: { location: ['/login'] }, body: '' } });
  assert.equal((await response).status, 302);
  assert.equal(h.window.fetch, h.fetch);
  assert.equal(h.window.XMLHttpRequest, h.XMLHttpRequest);
  assert.equal((await h.window.fetch('https://example.com/')).ok, true);
  assert.equal(h.requests.length, 1);
});

test('settings saveSnapshot waits for backend acceptance before refresh and propagates save errors', async () => {
  const h = loadBridge();
  const snapshot = { profile: { rating: 15158 }, images: {}, page: 2 };
  const notified = h.bridge.card.saveSnapshot(snapshot).then(() => h.bridge.card.requestRefresh());
  assert.equal(h.requests[0].op, 'card.saveSnapshot');
  assert.deepEqual(h.requests[0].args, { value: snapshot });
  await Promise.resolve();
  assert.equal(h.requests.length, 1, 'refresh must wait until the snapshot is saved');
  h.complete(h.requests[0].id, { ok: true, value: 12 });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.requests[1].op, 'card.requestRefresh');
  h.complete(h.requests[1].id, { ok: true, value: true });
  assert.equal(await notified, true);

  const rejected = assert.rejects(h.bridge.card.saveSnapshot(snapshot),
    error => error.code === 'ERROR' && error.message === '快照内容过大。');
  h.complete(h.requests[2].id, { ok: false, error: { code: 'ERROR', message: '快照内容过大。' } });
  await rejected;
  assert.equal(h.requests.length, 3, 'failed saves must not retry automatically');
});

test('native submission failure rejects without retry', async () => {
  const h = loadBridge(() => { throw new Error('bridge unavailable'); });
  await assert.rejects(h.bridge.module.info(), /bridge unavailable/);
  assert.equal(h.requests.length, 1);
});
