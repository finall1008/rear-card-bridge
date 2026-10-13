# 小米背屏应用卡开发指南

适用于通过背屏卡片桥导入的 HTML + JavaScript ZIP。当前适配依据是 Xiaomi 18 Pro / Android 17；尺寸、原生事件和系统行为不应直接当成所有小米设备的通用规范。

**最重要的一条：事件回调负责改状态，`requestAnimationFrame` 回调负责绘制。** 收到触摸不等于新画面已经发布；ZIP 保存成功也不等于背屏已经执行。

## 1. 这不是普通网页

运行链路：

```text
来源 Android App 获取数据、生成 ZIP
    → 卡片桥合并经典脚本、按来源 App / key 保存版本
    → 已应用载体通过 ContentProvider / MAML HostVar 读取脚本
    → QuickJS 执行脚本
    → JsCanvas 在动画帧中录制绘制命令，宿主发布画面
```

| 普通网页中的习惯 | 背屏应用卡应采用的方式 |
| --- | --- |
| 用 HTML、CSS、DOM 排版按钮和列表 | 用 Canvas 绘制，自己维护状态和命中区域 |
| 在点击回调中直接调用绘图函数 | 改状态后申请下一帧，在 RAF 中统一绘制 |
| 页面滚动、CSS 动画、DOM 点击 | 自己处理指针手势、滚动偏移、动画和点击判定 |
| 浏览器具备完整 Web API | QuickJS 是 JS 引擎，宿主只提供部分浏览器形态接口 |
| 页面持续运行、计时器准时触发 | 背屏可能休眠、重建；重新打开时应能从当前数据恢复 |
| 更新网页就同时更新图片、字体 | 脚本可动态更新；静态资源需更新载体并手动重新应用 |

入口 HTML 是**打包入口**，不是由 WebView 加载的完整页面。桥读取其中的 canvas 和脚本引用，生成 MAML / JsCanvas 载体；不会把 HTML 布局、CSS 样式或 DOM 控件转换成背屏界面。

不要因为 Chrome 中存在某个 API 就默认宿主也存在：`fetch`、XHR、存储、字体加载、DOM 操作、WebGL 等都不能据网页预览判定可用。需要某项能力时，应查目标宿主实际接口并在目标设备验证。业务联网、认证、持久化优先放在来源 Android App，卡片接收可直接展示的数据。

## 2. 输入 ZIP 与脚本组织

```text
card.zip
├── index.html
├── main.js
└── assets/          # 可选，例如 logo.png
```

当前解析器要求 ZIP 根目录同时包含 `index.html` 和 `main.js`，HTML 中必须有 canvas。不要多包一层项目目录。

最小入口：

```html
<!doctype html>
<html>
<head><meta charset="utf-8"></head>
<body>
  <canvas id="rear" width="912" height="596"></canvas>
  <script src="main.js"></script>
</body>
</html>
```

注意：

- 支持本地 `.js` 经典脚本、内联经典脚本，按 HTML 中的顺序合并。
- 未被引用的 `main.js` 会追加到合并脚本末尾；不要依赖浏览器的 `async` / `defer` 加载时序。
- `type="module"` 会被拒绝；ES Module 导入不是这个交付格式。使用构建工具时输出不依赖模块加载器的经典脚本，例如 IIFE bundle。
- 不支持远程脚本引用。HTML 中的外部图片引用也会被拒绝；JS 字符串中的资源地址不会由桥做执行验证。
- 使用 UTF-8 和相对路径，不用绝对路径、`..`、带查询参数的脚本 / HTML 图片路径。不要通过 `?v=2` 做资源版本更新。
- 图片放入 `assets/`，JS 使用一致的路径，例如 `assets/logo.png`。打包器还会为图片生成去掉 `assets/` 前缀的别名；不要另放一份同名文件造成输出路径冲突。
- 其他资源会复制到载体 `assets/` 下；**被复制不代表宿主有加载它的 API**，尤其不能靠 CSS `@font-face` 假定自定义字体可用。

推荐把数据配置放在绘制代码之前：

```html
<script>window.CARD_CONFIG = { title: "用量", usedPercent: 36 };</script>
<script src="main.js"></script>
```

脚本使用闭包保存运行状态，避免把导航、手势和缓存散落到 `window`。配置全局变量应由每个新版本明确赋值；热更新并不会重建一个干净的浏览器页面。

## 3. 绘制时机：统一从 RAF 发布画面

### 为什么点击生效了却看不到变化？

ai-token 的修复说明了这个区别：触摸回调已收到事件、页面状态也已改变，但直接在回调里画图不处于 JsCanvas 的动画帧录制阶段，新画面没有被宿主发布。将点击、滑动和图片加载完成后的重绘都移到下一次 `requestAnimationFrame` 后，交互才能正常显示。

修复的是**绘制时机**，不是卡片桥的点击转发逻辑。

### 推荐的最小绘制结构

```js
(function () {
  'use strict';
  var canvas = document.getElementById('rear');
  var ctx = canvas.getContext('2d');
  var W = window.CANVAS_W || 912;
  var H = window.CANVAS_H || 596;
  var queued = false;
  var state = { page: 0 };

  function requestDraw() {
    if (queued) return;
    queued = true;
    window.requestAnimationFrame(function () {
      queued = false;
      draw();
    });
  }

  function draw() {
    ctx.save();
    // 在此例中没有跨帧保留变换；每一帧画完整背景和当前页面。
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = '#101014';
    ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = '#f2f3f7';
    ctx.font = '32px sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText('第 ' + (state.page + 1) + ' 页', 336, 80);
    ctx.restore();
  }

  function goTo(page) {
    state.page = page;
    requestDraw(); // 点击 / 滑动处理函数调用它，不直接 draw()
  }

  // 在这里注册事件、初始化资源，事件中更新 state 后调用 requestDraw。
  requestDraw(); // 首屏同样通过 RAF
})();
```

同一帧前多次状态变化只会排一个重绘，执行时读取最新状态。静止时不继续申请 RAF，不用永久循环掩盖重绘时机的问题。也不要用 `setTimeout(draw, 0)` 代替 RAF：计时器不是宿主的帧录制入口。

所有会改变画面的入口都遵循同一规则：首次渲染、点击、滑动、图片 `onload` / `onerror`、数据变更、时间文案更新。

有真实动画时才连续请求下一帧；动画结束即停止。使用时间差推进动画，而不是假定每帧固定 16ms。当前载体配置 `frameRate="30"`，不能按桌面浏览器的 60fps 假设设计。

## 4. 触摸：指针事件、坐标和手势必须一致

ai-token 采用的宿主事件入口是 canvas 上的 `pointerdown`、`pointermove`、`pointerup`、`pointercancel`。不要只监听 DOM `click` 或浏览器的 `touchstart` / `mousedown`；不要同时注册多套事件导致一个操作执行两次。

### 坐标转换

绘图和命中检测应使用同一套 Canvas 逻辑坐标。网页预览可能存在 CSS 缩放，可按下面方式映射；目标宿主的坐标是否需要映射仍以实际事件值为准。

```js
function toCanvas(e) {
  var rect = canvas.getBoundingClientRect();
  return {
    x: (e.clientX - (rect.left || 0)) * W / (rect.width || W),
    y: (e.clientY - (rect.top || 0)) * H / (rect.height || H)
  };
}
```

布局生成按钮矩形时就保存该矩形，绘制与命中检测复用它。列表滚动后，命中检测也必须减去相同的滚动偏移；裁剪区之外的条目不能仍可点击。不要把网页 CSS 像素、Canvas 坐标、手机物理像素混在一起，也不要无依据地再乘 `devicePixelRatio`。

### 手势处理规则

1. 按下时记录起点、当前页和活动 `pointerId`，忽略非主指针或第二根手指。
2. 移动超过小阈值后再区分横向翻页和纵向滚动，并在这次手势中锁定方向。
3. 抬起时：已滚动的手势不触发点击；达到横滑阈值才翻页；轻微移动才按点击处理。
4. `pointercancel` 清除按下状态，不执行点击或翻页。支持时处理 `lostpointercapture`；离开区域且无捕获时取消手势，避免下一次触摸沿用旧起点。
5. `setPointerCapture` 等接口按宿主是否提供来使用；浏览器预览中的 `preventDefault` 和 CSS `touch-action` 不能代替宿主事件验证。
6. 状态改变后调用 `requestDraw()`；滑动中的多次更新合并到下一帧。

ai-token 使用移动超过 12 个逻辑单位就不算点击、横滑至少 60 个逻辑单位才翻页。这是该卡的参数，不是协议要求；应随布局、可触区域调整。

背屏按钮应留足触摸范围。小圆点可以画得小，但命中区域应更大；镜头遮挡和屏幕边缘不放关键操作。浏览器的键盘和滚轮处理可以服务桌面预览，不能当作背屏必有的输入方式。

## 5. 尺寸、布局与 Canvas 状态

- 当前载体使用 `screenWidth="912"`，JsCanvas 的宽高来自宿主 `view_width` / `view_height`。脚本可参考 `window.CANVAS_W`、`window.CANVAS_H`，不要把 HTML 的 width / height 当成能配置原生画布的入口。
- 912 × 596 是 ai-token 的布局基准 / 回退值，不是所有卡片都固定的实际尺寸。本仓库早期静态示例还使用过 912 × 412。
- 镜头区域会遮挡左侧。ai-token 把内容放在约 x=336 到 x=876 的右侧区域；这是该设备上的布局经验，不是通用安全区。
- 首先确认目标设备的画布尺寸、可视区域和指针坐标，再统一布局与命中检测。若缩放设计坐标，输入坐标也做对应逆变换。
- 每次重绘完整背景和当前界面，避免依赖上一帧残留像素。清屏和背景绘制都在 RAF 中完成。
- 局部绘制的 `save()` / `restore()` 必须配对；缩放、平移、裁剪、透明度、文字对齐都可能污染后续绘制。热更新载体只明确重置变换（接口存在时）和 `globalAlpha`，不会替应用恢复每个 Canvas 属性。
- 字号、字重、文本基线显式设置；中文和长名称通过 `measureText` 做宽度判断和截断，不依赖 CSS 自动换行。
- 使用已确认可用的 2D 绘制接口。需要圆角时可用 `arcTo` 构造路径，不要仅凭新版浏览器支持 `roundRect` 就使用。

## 6. 图片与异步加载

```js
var logo = null;
var image = new Image();
image.onload = function () {
  logo = image;
  requestDraw();
};
image.onerror = function () {
  logo = null;
  requestDraw();
};
image.src = 'assets/logo.png';
```

绘制函数中，加载成功才 `drawImage`，否则画首字母或其他占位。先注册回调再设置 `src`，首屏不要等待所有图片才能显示；不要在每次重绘中重复创建和加载图片。

静态图片随载体安装，不把整张图片塞进日常热更新脚本。桥不会把 JS 中的图片 URL 自动转成包内文件，也不会替应用验证脚本中的加载路径。优先使用本地图片；SVG、动画图片等实际渲染能力不能仅由打包器接受扩展名来推断。

## 7. 数据更新、生命周期与状态

### Android App 负责数据，卡片负责展示

来源 App 获取网络数据、管理凭据、生成配置和 ZIP；卡片脚本读取配置并绘制。不要把访问令牌、密码、登录验证码或其他认证材料放进卡片 ZIP、脚本或日志。卡片可在背屏上被旁人看到，展示内容也要按这个场景选择。

数据中保留更新时间，区分「0」「尚未获取」「获取失败」。休眠后按当前时间重新计算倒计时 / 重置文案，不按计时器累计次数推算时间。不需要每秒变化的文案，不每秒重绘。

### 热更新是重新执行脚本，不是整页导航

当前载体收到新 revision 后，在 RAF 中停止旧脚本、清屏并执行新脚本：

- 取消它追踪到的 RAF、函数型计时器和事件监听。
- 事件包装明确覆盖 `window` 和原生 `data` 的 `addEventListener`；不能泛化成「所有对象的监听都自动清理」。canvas / document 监听是否落到这些原生入口，取决于宿主实现。
- 不提供通用页面销毁回调；`Image.onload`、Promise 回调、自定义异步任务、任意全局变量不在这套清理的完整覆盖范围内。

因此，用闭包隔离状态、使用可移除的具名事件函数，不覆盖桥包装的 RAF / 计时器 / 事件方法，不随意给原生对象打补丁。对于需要跨异步任务或自行管理监听的卡片，由应用脚本明确管理旧版本的清理和停止标记，避免旧任务修改新画面；不要假定桥提供了不存在的 `dispose` API。

每次执行都应从当前配置构建完整首屏。导航页、滚动位置属于该次运行的内存状态，默认会重置；需要持久化的业务状态放在来源 App，不依赖网页 `localStorage`。

### 脚本更新与资源更新是两条路径

| 变化 | 操作 |
| --- | --- |
| 数据、内联配置、参与合并的经典 JS 改变 | 同来源 App / key 导入完整 ZIP，已绑定组动态保存脚本 |
| 图片、字体等静态资源新增 / 修改 / 删除 | 前台点击「更新载体资源」，到系统移除旧卡并重新应用，然后确认 |
| 第一次使用来源 App / key | 前台选择扫描出的目标，安装载体，在系统重新应用 |

入口 HTML 和参与合并的脚本不计入静态资源变化；改变 HTML 的 CSS 布局仍不会改变原生界面。不要只改图片后反复热更新脚本，或只点确认按钮而未实际重新应用。

同一来源 App / key 要保持稳定：内部按 `(来源包名, key)` 分组，同一目标卡片只能被一个组占用。不要硬编码别人的 productId，也不要每次刷新生成新 key。

前台 Activity Result 与后台 `ContentProvider.call()` 的 API 2 集成、URI 授权和返回字段见 [README](<../README.md#前台-intentapi-2>)。后台必须已有绑定，调用方在 IO 线程执行并在调用期间保持 ZIP 可读；它不会弹页面替用户配置。

`SAVED` 只说明桥已保存。`CARRIER_WRITTEN` 只说明资源已写入，`SETTINGS_OPENED` 只说明设置页已打开；没有 `APPLIED` 成功状态。背屏休眠时，执行可能延后到点亮；载体 `updateInterval="1"` 也不是端到端每秒更新的保证。

## 8. 预览、验证与排错

网页预览适合验证配色、排版、文本截断、状态切换和命中算法。它不能证明宿主支持某个 API、触摸会以相同事件送达、异步绘制会发布，或静态资源已经在系统生效。

建议分层观察：

| 现象 | 优先检查 |
| --- | --- |
| 桥报缺少入口或脚本 | ZIP 根目录、canvas、经典脚本引用和路径 |
| 显示脚本执行失败 | 运行日志中的语法 / API 错误；不要先改触摸 |
| 初始画面正常，点击没有变化 | 是否收到 pointer 事件、坐标是否命中、状态是否改变、是否排到 RAF |
| 状态已改变、RAF 外直接画了，但画面没变 | 把绘制移进 RAF，不以持续刷屏或重装桥掩盖问题 |
| 点击位置偏移 | 绘图尺寸、预览缩放、输入坐标映射及滚动偏移 |
| 拖动结束误触按钮 | 点击移动阈值、方向锁定、cancel 清理 |
| 数据更新了，图片仍是旧图 | 静态资源状态、是否更新载体并真正移除旧卡重新应用 |
| 桥显示新版本，背屏仍旧画面 | 背屏是否休眠、绑定是否正确、载体是否读取并执行新 revision |
| 多次更新后一次点击执行多次 | 是否重复注册事件、多事件族并用、旧监听是否实际清理 |

可在开发时记录：收到事件、映射坐标、命中结果、状态变化、RAF 开始绘制。桥载体已有 `RearCardBridgeLive carrier ready`、`executed revision=...` 和 `script error revision=...` 日志；区分「保存」「执行」「屏幕显示」三步，不用一条成功日志替代全链路验证。不要记录凭据。

### 交付前检查

- [ ] 根目录入口和经典脚本齐全，资源路径与包内文件一致。
- [ ] 首屏、点击、滑动、图片回调、数据和时间变化都走同一个 RAF 重绘入口。
- [ ] 静态卡没有永久 RAF 循环；动画结束即停止。
- [ ] 使用目标宿主提供的指针事件，绘制坐标和命中坐标一致。
- [ ] 拖动不误触点击，取消手势后不残留按下状态。
- [ ] 镜头遮挡区和边缘没有关键内容 / 按钮，长文本不越界。
- [ ] 图片未加载或失败时仍有可用首屏。
- [ ] 新版本从当前数据完整初始化，反复更新不留下重复交互或旧异步绘制。
- [ ] 数据更新时间、空态和错误态清楚，ZIP 与日志不包含凭据。
- [ ] 纯脚本更新与静态资源更新分别验证，休眠后再点亮 / 重新打开也能显示当前内容。

设备安装、触摸测试和系统重新应用需要用户另行授权；本指南的代码核对和网页预览不能替代真机验证。

## 9. 实现依据与参考

- [输入包解析、资源布局与载体 XML](<../app/src/main/java/dev/finall/rearcardbridge/packageio/CardPackageBuilder.kt>)。
- [动态脚本加载、RAF 调度及旧会话清理](<../app/src/main/java/dev/finall/rearcardbridge/packageio/LiveCarrierRuntime.kt>)。
- [桥接 API 2、保存状态与静态资源更新流程](<../README.md>)。
- 邻接 ai-token 项目的 [卡片实现](<../../xiaomi-backscreen-ai-token/app/src/main/assets/card/main.js>)：图片加载回调（126–129 行）、合并重绘（476–487 行）、滑动与指针处理（509–615 行）。此相对链接依赖两个项目同级检出。
- [早期静态绘制示例](<../samples/rear-visible/main.js>) 只展示画面与可视区域，不是交互和异步重绘模板；开发交互卡请使用本指南的 RAF 结构。
