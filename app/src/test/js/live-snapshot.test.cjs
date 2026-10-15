const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Execute the embedded carrier JS with the host's storage, data events and frame loop.
const kotlin = fs.readFileSync(path.join(__dirname,
  '../../main/java/dev/finall/rearcardbridge/packageio/LiveCarrierRuntime.kt'), 'utf8');
const runtime = kotlin.split('private val SOURCE = """')[1].split('""".trimIndent()')[0]
  .replaceAll("${'$'}", '$').replaceAll('__CANVAS_NAME__', 'canvas')
  .replaceAll('__MODULE_ID__', 'example.counter');

function host(snapshot, cached) {
  const storage = new Map(cached ? [['bridge_snapshot', JSON.stringify(cached)]] : []);
  const listeners = [];
  let frame;
  const script = 'window.boots = (window.boots || 0) + 1; window.first = bridge.card.readSnapshot();';
  const payload = (value, rev) => JSON.stringify({ revision: 1, script, snapshot: value, snapshotRev: rev });
  const data = { bridge_live_payload: payload(snapshot, 1),
    addEventListener: (_, fn) => listeners.push(fn), removeEventListener() {} };
  const context = vm.createContext({ data, console: { log() {} },
    document: { getElementById: () => ({ getContext: () => ({ setTransform() {}, clearRect() {} }) }) },
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    requestAnimationFrame: fn => { frame = fn; },
    setInterval() {}, clearInterval() {}, setTimeout() {}, clearTimeout() {},
  });
  context.window = context;
  vm.runInContext(runtime, context);
  const event = (key, value) => listeners.forEach(fn => fn({ detail: { path: key, value } }));
  return { context, storage, frame: () => frame(),
    snapshot: (value, rev) => event('bridge_live_payload', payload(value, rev)),
    complete: (batch, id, rev) => event('bridge_sync', JSON.stringify({ batch, results: { [id]: { ok: true, value: rev } } })),
  };
}

const plain = value => JSON.parse(JSON.stringify(value));

test('first frame reads shared snapshot; later snapshot updates do not reload the script', () => {
  const h = host({ count: 2 }, { count: 1 });
  h.frame();
  assert.deepEqual(plain(h.context.first), { count: 2 });
  assert.equal(h.context.boots, 1);
  assert.equal(h.storage.get('bridge_outbox'), ''); // No implicit upload of cached state.
  h.snapshot({ count: 3 }, 2);
  h.frame();
  assert.equal(h.context.boots, 1);
  assert.deepEqual(plain(h.context.bridge.card.readSnapshot()), { count: 3 });
});

test('foreground save updates locally before submission and older payloads cannot overwrite it', () => {
  const h = host({ count: 2 });
  h.frame();
  h.context.bridge.card.saveSnapshot({ count: 4 });
  assert.deepEqual(plain(h.context.bridge.card.readSnapshot()), { count: 4 });
  h.snapshot({ count: 3 }, 2); // Previous backend value while our save is in flight.
  assert.deepEqual(plain(h.context.bridge.card.readSnapshot()), { count: 4 });
  h.frame();
  const batch = JSON.parse(h.storage.get('bridge_outbox'));
  assert.equal(batch.requests.length, 1);
  assert.equal(batch.requests[0].op, 'card.saveSnapshot');
  assert.deepEqual(batch.requests[0].args.value, { count: 4 });
  h.complete(batch.batch, batch.requests[0].id, 3);
  h.frame();
  h.snapshot({ count: 3 }, 2); // Delayed live payload after completion.
  assert.deepEqual(plain(h.context.bridge.card.readSnapshot()), { count: 4 });
  h.snapshot({ count: 5 }, 4); // New background refresh after our completed save.
  assert.deepEqual(plain(h.context.bridge.card.readSnapshot()), { count: 5 });
});
