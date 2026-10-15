import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { unzipSync } from 'fflate';
import { buildModuleZip } from '../src/build.js';
import { ScriptCompileError } from '../src/compile.js';

const CLI = fileURLToPath(new URL('../bin/card-build.js', import.meta.url));

const CARD_SOURCE = `'use strict';
function add(a, b) { return Promise.resolve(a + b); }
async function compute() {
  var sum = await add(2, 3);
  var again = await add(sum, 4);
  globalThis.result = again;
}
compute().then(function () { globalThis.done = true; },
  function (error) { globalThis.failed = String(error); });
`;

// webroot 是浏览器页面：原生 async/await 与 fetch 必须原样保留。
const WEBROOT_SOURCE = `<!doctype html>
<html><head><script src="bridge-shim.js"></script></head>
<body><script>
async function load() {
  const res = await fetch("https://example.com/api");
  return res.json();
}
</script></body></html>
`;

const ICON_BYTES = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);

/** 临时模块夹具：含模块内容与必须被排除的杂物（node_modules 等）。 */
function makeFixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'card-build-fixture-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const write = (rel, data) => {
    const p = path.join(dir, rel);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, data);
  };
  write('module.json', `${JSON.stringify({
    id: 'dev.example.fixture',
    name: 'fixture',
    version: '1.0.0',
    versionCode: 1,
    apiVersion: 2,
  }, null, 2)}\n`);
  write('icon.png', ICON_BYTES);
  write('card/index.html', '<!doctype html><script src="main.js"></script>');
  write('card/main.js', CARD_SOURCE);
  write('card/assets/logo.bin', Buffer.from([9, 8, 7, 6]));
  write('webroot/index.html', WEBROOT_SOURCE);
  // 杂物：绝不能出现在输出 ZIP 里
  write('node_modules/left-pad/index.js', 'module.exports = function (s) { return s; };');
  write('package.json', '{"name":"cruft"}');
  write('README.md', '# cruft\n');
  write('.git/config', '[core]\n');
  write('dist/bundle.js', 'var bundled = 1;\n');
  write('scripts/internal.js', 'async function mustNotBePacked() {}\n');
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'card-build-out-'));
  t.after(() => fs.rmSync(outDir, { recursive: true, force: true }));
  return { sourceDir: dir, outPath: path.join(outDir, 'module.zip') };
}

function readEntry(entries, name) {
  assert.ok(entries[name], `ZIP 缺少条目 ${name}`);
  return Buffer.from(entries[name]);
}

/** 在 vm 里执行编译产物（环境自带全局 Promise），等待 then 链落定。 */
async function runCompiled(code) {
  const sandbox = {};
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { timeout: 2000 });
  for (let i = 0; i < 100 && sandbox.done === undefined && sandbox.failed === undefined; i += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
  return sandbox;
}

test('card 脚本编译为生成器纯 JS，源码保持 async/await，产物可用全局 Promise 执行', async (t) => {
  const { sourceDir, outPath } = makeFixture(t);
  const result = await buildModuleZip({ sourceDir, outPath });
  assert.deepEqual(result.compiled, ['card/main.js']);

  const sourceOnDisk = fs.readFileSync(path.join(sourceDir, 'card', 'main.js'), 'utf8');
  assert.match(sourceOnDisk, /await add\(2, 3\)/, '源文件必须保持原生 async/await 不被改写');

  const compiled = readEntry(unzipSync(fs.readFileSync(outPath)), 'card/main.js').toString('utf8');
  assert.notEqual(compiled, sourceOnDisk, 'ZIP 内的脚本必须是编译产物');
  assert.match(compiled, /function\s*\*/, 'async 函数应编译为生成器');
  assert.ok(!/\bawait\b/.test(compiled), '编译产物不应残留 await');

  const sandbox = await runCompiled(compiled);
  assert.equal(sandbox.failed, undefined, `编译产物执行失败: ${sandbox.failed}`);
  assert.equal(sandbox.result, 9);
  assert.equal(sandbox.done, true);
});

test('webroot 与非 JS 内容按原始字节保留，webroot 不被编译', async (t) => {
  const { sourceDir, outPath } = makeFixture(t);
  await buildModuleZip({ sourceDir, outPath });
  const entries = unzipSync(fs.readFileSync(outPath));

  const pairs = ['webroot/index.html', 'card/index.html', 'module.json', 'icon.png', 'card/assets/logo.bin'];
  for (const rel of pairs) {
    const source = fs.readFileSync(path.join(sourceDir, ...rel.split('/')));
    assert.equal(readEntry(entries, rel).compare(source), 0, `${rel} 必须逐字节一致`);
  }
  assert.match(
    readEntry(entries, 'webroot/index.html').toString('utf8'),
    /await fetch/,
    'webroot 必须保持浏览器原生 async/await 源码',
  );
});

test('ZIP 形状只含模块根，不含 node_modules/工具杂物；CLI 成功退出', async (t) => {
  const { sourceDir, outPath } = makeFixture(t);
  const run = spawnSync(process.execPath, [CLI, sourceDir, outPath], { encoding: 'utf8' });
  assert.equal(run.status, 0, `CLI 应成功退出: ${run.stderr}`);
  assert.match(run.stdout, /card\/main\.js/);

  const entries = unzipSync(fs.readFileSync(outPath));
  assert.deepEqual(Object.keys(entries).sort(), [
    'card/assets/logo.bin',
    'card/index.html',
    'card/main.js',
    'icon.png',
    'module.json',
    'webroot/index.html',
  ]);
  const compiled = readEntry(entries, 'card/main.js').toString('utf8');
  assert.notEqual(compiled, fs.readFileSync(path.join(sourceDir, 'card', 'main.js'), 'utf8'));
});

test('语法错误报出出错文件、CLI 非零退出且不写出产物', async (t) => {
  const { sourceDir, outPath } = makeFixture(t);
  fs.writeFileSync(path.join(sourceDir, 'card', 'main.js'), 'async function broken( {\n');

  await assert.rejects(
    () => buildModuleZip({ sourceDir, outPath }),
    (error) => {
      assert.ok(error instanceof ScriptCompileError);
      assert.equal(error.file, 'card/main.js');
      assert.match(error.message, /^card\/main\.js: /);
      return true;
    },
  );
  assert.equal(fs.existsSync(outPath), false, '失败时不应写出 ZIP');

  const run = spawnSync(process.execPath, [CLI, sourceDir, outPath], { encoding: 'utf8' });
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /card\/main\.js/);
  assert.equal(fs.existsSync(outPath), false, 'CLI 失败时不应写出 ZIP');
});
