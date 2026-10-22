import fs from 'node:fs';
import path from 'node:path';
import { zipSync } from 'fflate';
import { compileScript } from './compile.js';

/** 模块包允许收录的根条目：与 ModuleManifest/ModuleStore 的包结构一致。 */
const ROOT_FILES = ['module.json', 'icon.png', 'preview.png'];
const ROOT_DIRS = ['card', 'webroot', 'background'];

/** 构建期结构/IO 错误。 */
export class BuildError extends Error {
  constructor(message) {
    super(message);
    this.name = 'BuildError';
  }
}

function isFile(p) {
  try {
    return fs.statSync(p).isFile();
  } catch {
    return false;
  }
}

function isDirectory(p) {
  try {
    return fs.statSync(p).isDirectory();
  } catch {
    return false;
  }
}

/**
 * 递归收集目录下的普通文件（相对路径用 / 分隔），跳过符号链接等非普通文件，
 * 保证输出 ZIP 只含模块内容，不含工具依赖或仓库杂物。
 */
function walkFiles(dir, relBase, out) {
  const dirents = fs.readdirSync(dir, { withFileTypes: true });
  dirents.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const dirent of dirents) {
    const abs = path.join(dir, dirent.name);
    const rel = relBase ? `${relBase}/${dirent.name}` : dirent.name;
    if (dirent.isDirectory()) {
      walkFiles(abs, rel, out);
    } else if (dirent.isFile()) {
      out.push({ rel, abs });
    }
    // 符号链接等其他条目一律跳过。
  }
}

/**
 * 列出会被打进 ZIP 的文件：module.json、icon.png、preview.png、card/**、webroot/**、background/**。
 * 其余内容（node_modules、package.json、.git、dist、工具源码等）不会被收录。
 *
 * @param {string} sourceDir 模块源目录（根目录直接是 module.json）
 * @returns {{rel: string, abs: string}[]} 按相对路径排序
 */
export function collectModuleEntries(sourceDir) {
  const root = path.resolve(sourceDir);
  if (!isDirectory(root)) {
    throw new BuildError(`模块源目录不存在或不是目录：${sourceDir}`);
  }
  if (!isFile(path.join(root, 'module.json'))) {
    throw new BuildError(`模块源目录缺少 module.json：${sourceDir}`);
  }
  const files = [];
  for (const name of ROOT_FILES) {
    if (isFile(path.join(root, name))) files.push({ rel: name, abs: path.join(root, name) });
  }
  for (const name of ROOT_DIRS) {
    const dir = path.join(root, name);
    if (isDirectory(dir)) walkFiles(dir, name, files);
  }
  files.sort((a, b) => (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0));
  return files;
}

/**
 * 把模块源目录打成安装用 ZIP。
 *
 * - card 下的全部 .js（含子目录）：原生 async/await 经 Babel 编译为生成器纯 JS（编译错误报文件并失败，
 *   失败时不写出产物）。
 * - 其余文件（module.json、icon.png、card 非 JS、webroot/**）按原始字节打包；
 *   webroot/ 和 background/ 在 WebView 中运行，保持原生 async/await，完全不编译。
 * - ZIP 由 fflate 生成，CRC 由库管理；不计算任何自定义哈希/摘要。
 *
 * @param {{sourceDir: string, outPath: string}} options
 * @returns {Promise<{outPath: string, entryNames: string[], compiled: string[]}>}
 */
export async function buildModuleZip({ sourceDir, outPath }) {
  const files = collectModuleEntries(sourceDir);
  const entries = {};
  const compiled = [];
  for (const { rel, abs } of files) {
    let data = fs.readFileSync(abs);
    if (rel.startsWith('card/') && rel.endsWith('.js')) {
      const code = await compileScript(data.toString('utf8'), rel);
      data = Buffer.from(code, 'utf8');
      compiled.push(rel);
    }
    entries[rel] = new Uint8Array(data);
  }
  const zipBytes = zipSync(entries);
  const target = path.resolve(outPath);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, zipBytes);
  return { outPath: target, entryNames: Object.keys(entries), compiled };
}
