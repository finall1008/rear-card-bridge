import { transformAsync } from '@babel/core';
import asyncToGenerator from '@babel/plugin-transform-async-to-generator';

/**
 * 编译错误：带上出错的模块内文件路径。
 *
 * 只做 Babel 编译本身；不使用正则判断 async，也不做 AST/安全分析。
 */
export class ScriptCompileError extends Error {
  /**
   * @param {string} relPath 模块内相对路径，例如 card/main.js
   * @param {unknown} cause Babel 抛出的原始错误
   */
  constructor(relPath, cause) {
    const raw = String((cause && cause.message) || cause);
    const lines = raw.split('\n');
    let headline = lines[0];
    // Babel 在未传 filename 时用 "unknown: " 前缀；换成模块内路径再输出。
    if (headline.startsWith('unknown: ')) headline = headline.slice('unknown: '.length);
    const frame = lines.slice(1).join('\n');
    super(`${relPath}: ${headline}${frame ? `\n${frame}` : ''}`);
    this.name = 'ScriptCompileError';
    this.file = relPath;
    this.cause = cause;
  }
}

/**
 * 把单个卡片脚本的原生 async/await 编译成生成器纯 JS。
 *
 * 只挂 @babel/plugin-transform-async-to-generator：没有 preset-env、没有打包、
 * 没有安全扫描。卡片脚本按普通脚本（sourceType: script）编译，import/export
 * 与顶层 await 会作为编译错误报出文件并失败。产物是纯 JS，运行时通过全局
 * Promise（新运行时在执行模块脚本前注入 window.Promise）执行。
 *
 * @param {string} source 脚本源码（源码保持原生 async/await）
 * @param {string} relPath 模块内相对路径，仅用于错误报告
 * @returns {Promise<string>} 编译后的 JS 文本
 */
export async function compileScript(source, relPath) {
  let result;
  try {
    result = await transformAsync(source, {
      babelrc: false,
      configFile: false,
      sourceType: 'script',
      compact: false,
      sourceMaps: false,
      plugins: [asyncToGenerator],
    });
  } catch (error) {
    throw new ScriptCompileError(relPath, error);
  }
  return result.code;
}
