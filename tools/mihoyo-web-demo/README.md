# 米游社网页版实时便笺 Demo

独立的本地实验，不修改/安装 Android App。只支持国服《原神》《崩坏：星穹铁道》《绝区零》。

## 启动

在仓库根目录运行：

```sh
python3 -m venv tools/mihoyo-web-demo/.venv
tools/mihoyo-web-demo/.venv/bin/python -m pip install -r tools/mihoyo-web-demo/requirements.txt
tools/mihoyo-web-demo/.venv/bin/python tools/mihoyo-web-demo/server.py
```

浏览器访问 **http://127.0.0.1:18765**（不要换成 localhost，Host 校验只接受 127.0.0.1）。端口占用时可传 `--port 18766`。

1. 点击「生成网页登录二维码」，用米游社扫描并由本人确认官方登录提示；也可以在官方页面打开二维码对应链接。
2. 登录成功后自动加载绑定角色；也可点击「加载绑定角色」重试。
3. 选择角色，点击「查询实时便笺」。页面显示官方体力快照、回满倒计时、每日进度及原始 JSON。
4. 不用时点击「清除本地登录态」，然后在终端 Ctrl+C 停止服务。

不要把真实 Cookie 发到聊天、提交到仓库或写进脚本。若网页扫码不可用，可在 Demo 页面输入本人官方网页的 V2 Cookie；仅接受网页 Cookie 字段，不保留 SToken、GameToken 或其他无关字段。不会自动读取浏览器凭证。

## 实际链路

- `POST https://passport-api.miyoushe.com/account/ma-cn-passport/web/createQRLogin`
- `POST https://passport-api.miyoushe.com/account/ma-cn-passport/web/queryQRLoginStatus`
- 从确认响应的 `Set-Cookie` 取网页 V2 Cookie。保留同一进程生成的随机 device ID；二维码轮询 3 秒一次，最多 3 分钟。
- 通过 `getUserGameRolesByCookie?game_biz=...` 读取角色；只有 CookieToken 时选择 `getUserGameRolesByCookieToken`。角色 UID、server 取自官方绑定列表，不根据 UID 猜测。
- 原神：`GET https://api-takumi-record.mihoyo.com/game_record/genshin/api/dailyNote`
- 铁道：`GET https://api-takumi-record.mihoyo.com/game_record/hkrpg/api/note`
- 绝区零：`GET https://api-takumi-record.mihoyo.com/event/game_record_zzz/api/zzz/note`
- 查询参数均为 `role_id`、`server`。不开放任意上游 URL 或任意他人 UID 查询。

**使用纯网页 Cookie + 官方网页 DS1 通道，不使用 device_fp。** 首版不带 DS，星铁持续返回 `-10001`。用户随后明确允许仅在这个独立 Demo 中生成 DS，作为仓库“禁止自写哈希/摘要”的本次例外；代码仅对 `/game_record/` 纯网页接口使用官方 H5 的 `app_version=2.3.0` 与 DS1 算法。没有 DS2、设备指纹生成、SToken/GameToken 交换或 Widget 调用，不修改 Android 主工程。服务端是否接受仍以真实响应为准；前一轮调研中的通用风控推测不是可用性保证。

## 展示语义与限制

- 数值与上限均来自返回值，不硬编码树脂/开拓力/电量上限。
- 恢复倒计时从获取时刻按本机时钟递减；体力本身保持官方快照，不假装实时感知游戏内消耗。
- 原神委托进度和额外奖励是否领取分开显示；历练点、奖励条目保留在原始 JSON。
- 铁道、绝区零显示活跃度，**不据此断言所有任务或奖励已完成/领取**。
- 未返回的字段显示「未知」。没有自动游戏数据轮询，同一角色 60 秒内返回缓存。
- `10102` 提示在官方页面手动开启实时便笺，不自动修改隐私开关。
- `1034` / `10035` 显示「手动完成人机验证」。按官方 H5 流程，调用不带 `/app/` 的 `/game_record/card/wapi/createVerification?is_high=true`，携带刚才失败请求的 `x-rpc-challenge_game` / `path` / `trace`。用户手动完成 Geetest 后调用同前缀 `verifyVerification`，把官方返回的 challenge 用于同角色查询，提交成功后自动重查一次。绝区零使用独立的 `https://api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest` / `verifyGeetest`，指定 `app_key=game_record_zzz`，不能复用原神的验证码接口。不会自动识别或拖动验证码、不持续重试，也没有打码平台。
- Geetest SDK 仅在用户点击验证按钮后加载，在不含 `allow-same-origin` 的 iframe 沙箱中执行，只接收验证码临时参数，不接收 Cookie、角色 UID 或本地 API 校验令牌。验证码会话/结果也只保存在内存。若官方不返回可交互参数，会明确失败，不伪造成功。
- 首版真实测试：登录与三个游戏的绑定列表成功；带 `/app/` 的原神便笺返回 `1034`，铁道返回 `-10001`，绝区零返回 `10035`。随后核对官方 H5 脚本，将原神/铁道改为纯网页路由，并补充上述人工验证链路。不能把首版的错误认定为纯网页路由一定不可用。
- 退出/改密/风控后需重新登录，不承诺 Cookie 固定有效期或刷新成功。

## 凭证与本地服务边界

- 登录 Cookie、二维码 ticket、角色数据仅存当前 Python 服务内存，不写磁盘；服务重启即丢失。服务本身不输出访问日志或账号数据。操作系统的交换区、浏览器/系统转储等不在此承诺范围。
- 前端只接收 Cookie 字段名，不显示值；手动导入输入框提交后清空。二维码在本地生成，不发送给在线二维码服务。
- Cookie 是可复用的网页登录凭证，**不等于只读 OAuth、也不等于仅授权本工具查看体力**。扫码前请核对官方提示。
- 服务只监听 `127.0.0.1`，检查 Host / Origin / 本地随机校验头，不允许 CORS，不提供任意文件读取。官方请求禁止跟随重定向，避免转发凭证。
- 清除内存不等于撤销官方会话；需要撤销时在官方账号安全页面处理。
- 这是本地单人开发 Demo，不适合暴露到公网、多用户部署或生产托管。

## 测试

```sh
cd tools/mihoyo-web-demo
.venv/bin/python -m unittest -v
node --check app.js
node --check captcha.js
```

测试使用明确的合成数据，不访问真实账号；覆盖扫码转换、Cookie 提取/过滤、三游戏摘要、缓存、风控错误、退出清理和本地 HTTP 访问控制。真实便笺端到端验证必须由本人扫码后进行，不能用单元测试代替。

路由与人工验证流程另核对过[米游社官方 H5 页面](https://webstatic.mihoyo.com/app/community-game-records/index.html)的当前脚本：纯网页基础路径为 `/game_record`，在 App 内才追加 `/app`。不要把两者混用。

参考：[网页扫码接口](https://github.com/UIGF-org/mihoyo-api-collect/blob/main/hoyolab/login/qrcode_hoyolab.md)、[游戏便笺接口](https://github.com/UIGF-org/mihoyo-api-collect/blob/main/hoyolab/user/game_account_info.md)、[genshin.py](https://github.com/seriaati/genshin.py)。社区接口文档可能过时。另核对[官方星铁 H5](https://webstatic.mihoyo.com/app/community-game-records/rpg/index.html)的网页 DS1 与[官方绝区零 H5](https://act.mihoyo.com/app/mihoyo-zzz-game-record/m.html)的独立 risk 接口。

开发诊断仅保留最近 12 次便笺/验证码操作的阶段、游戏标识、retcode 与时间；不含 Cookie、UID、challenge 或验证码内容，可从 `/api/state` 查看。
