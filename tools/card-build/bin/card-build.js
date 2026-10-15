#!/usr/bin/env node
import { buildModuleZip, BuildError } from '../src/build.js';
import { ScriptCompileError } from '../src/compile.js';

const USAGE = `用法: card-build <模块源目录> <输出 ZIP>

把 <模块源目录>（根目录直接是 module.json）打成安装用模块 ZIP：
  - card/**/*.js 的原生 async/await 用 Babel 编译为生成器纯 JS；
    输出是纯 JS，执行时使用运行时注入的全局 Promise。
  - module.json、icon.png、card 非 JS 文件、webroot/**、background/** 按原始字节打包；
    webroot/ 和 background/ 运行在 WebView，保持原生 async/await，不编译。
  - 只收录上述模块根，node_modules / 工具源码等不会进包。
  - 语法错误会报出出错文件并以非零状态退出。

示例:
  node tools/card-build/bin/card-build.js samples/hello-module hello-module.zip`;

async function main(argv) {
  if (argv.includes('-h') || argv.includes('--help')) {
    console.log(USAGE);
    return;
  }
  if (argv.length !== 2) {
    console.error(USAGE);
    process.exitCode = 1;
    return;
  }
  const [sourceDir, outPath] = argv;
  try {
    const result = await buildModuleZip({ sourceDir, outPath });
    console.log(`已生成 ${result.outPath}`);
    console.log(`条目 ${result.entryNames.length} 个，编译脚本 ${result.compiled.length} 个:`);
    for (const file of result.compiled) console.log(`  ${file}`);
  } catch (error) {
    if (error instanceof BuildError || error instanceof ScriptCompileError) {
      console.error(`card-build: ${error.message}`);
    } else {
      console.error(`card-build: ${(error && error.stack) || error}`);
    }
    process.exitCode = 1;
  }
}

await main(process.argv.slice(2));
