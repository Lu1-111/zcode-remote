# ZCode 远程控制技术笔记

> **读者**：以后维护「ZCode 遥控」这个 App 的人。
>
> 这份笔记把两次调查的结果整理成能直接用的知识：链接长什么样、手机页面怎样登录和连中转、电脑端什么时候在线、页面在 WebView 里缺什么、ZCode 升级后要检查什么。工程上的约定在 [ARCHITECTURE.md](ARCHITECTURE.md)，需求原文在 [WRAPPER-PLAN.md](WRAPPER-PLAN.md)。

## 0. 怎样读这份笔记

**调查的对象和时间。** ZCode 桌面版 4.0.0（构建时间 2026-11-22），调查在 2026-11-23 至 24。版本一变，这里的内容就可能过时，尤其是「文件加字符偏移」这样的定位。

**证据怎么引。** 正文里的 `[证据 n]` 指附录 A 的第 n 条，`[复核 n]` 指附录 B 的第 n 条。两份附录都是调查记录的原话。

**可信度怎么标。**

- 「实测」：调查时在本机执行命令或读文件得到的。
- 「读代码」：从 4.0.0 的压缩代码里读出来的，定位到文件和偏移。
- 「推断」：由前两条推出来，没有实际跑过。正文里会明写。
- 没查清的集中在第 12 节。

**两条容易踩的坑。**

- 4.0.0 的代码是压缩过的，字符串大多用反引号。证据里为了好读，有一部分写成了双引号。到线上的文件里检索时，不要带双引号，或者只搜标识符。
- 证据里提到的 `/tmp/…` 路径（挂载的安装包、解出来的文件、下载的页面、分析脚本）都在内存盘上，重启就没了。安装包的挂载点名字每次启动都会变。

## 1. 一页速览

- 电脑端弹窗里的二维码和「复制链接」是同一个地址：`https://zcode.z.ai/remote/v4?app_version=…&sid=…&mid=…&name=…`，四个参数都可选。链接里没有登录凭证，但加上同账号登录就能控制这台电脑，要当密码对待。
- `app_version` 决定手机页面的版本，必须原样保留。去掉它会落到一个 9 月的旧页面；旧页面要求的是另一种带 `hash` 和 `t` 的链接（读代码得出，没有拿真实链接试过）。
- 手机页面要单独登录，而且必须和电脑端是同一个账号。登录是整页跳转的授权流程，登录态和登录记录都存在 `zcode.z.ai` 同源的 localStorage 里。
- 电脑端打开一次远控弹窗就开启，关掉弹窗不停，重启后自动恢复；只有手动点「停止」或窗口被关闭才停。
- 手机页面自己完成了所有远程控制的业务。App 要做的是把它装进 WebView，并补上 WebView 默认缺的四样东西：文件选择、新窗口、返回键、保存副本。
- 同一台电脑同一时间只允许一个手机页面，多开的会把对方踢下线。

## 2. 链接

### 2.1 长什么样

```
https://zcode.z.ai/remote/v4?app_version=<桌面版本>&sid=<设备id>&mid=<机器码>&name=<电脑主机名>
```

四个参数都可选，顺序不固定 `[证据 1]`。`name` 是电脑的主机名，不是工作区名。

### 2.2 谁生成

桌面主进程里的 `buildWebRemoteControlExternalQrUrl`（`out/main/chunk-IERSWOXK.js`，压缩名 `Dve`，offset 860780）。它在基础地址上设 `sid`，主动删掉 `hash` 和 `t`，再把非空的 `mid`、`name`、`app_version` 设上去 `[证据 2]`。

二维码在连接进入「等待手机」或「已配对」状态时才生成。「复制链接」复制的是同一个地址 `[证据 3]`。

### 2.3 四个参数

| 参数 | 是什么 | 会不会变 | 证据 |
| --- | --- | --- | --- |
| `sid` | 云端设备 id。电脑用登录凭证调 `POST /api/v1/remote/device` 注册得到，请求体只有 `device_mid`。电脑把它和账号、端点一起存在 `~/.zcode/v2/setting.json` 里 | 同账号、同端点、没被要求重新注册时不变。换了账号或端点会换成新的，旧的随即失效 | `[证据 4]` `[证据 9]` `[复核 2]` |
| `mid` | 设备机器码（UUID）。第一次运行时生成，存在 `~/.zcode/v2/telemetry-state.json` 的 `deviceMid` | 不变，换账号也不变 | `[证据 6]` |
| `name` | `os.hostname()`，只用于显示 | 跟着主机名变 | `[证据 7]` |
| `app_version` | 桌面应用的版本，决定网关给出哪一套手机页面 | 跟着桌面升级变 | `[证据 1]` `[证据 10]` |

`mid` 在连接地址里是给网关的路由提示，让同一台电脑的桌面连接和手机连接落到同一个中转实例 `[证据 6]`。手机页面靠 `mid` 在新旧 `sid` 之间自动接续 `[证据 23]`。

`sid` 在客户端看不到任何有效期字段，服务端会不会清理长期不在线的设备，没查到。

### 2.4 和工作区的关系

`sid` 绑定的是「这台电脑 + 这个账号 + 这个端点」，和工作区无关。手机连上后拿到的是**开着远控的那个桌面窗口里已打开的全部工作区**，别的桌面窗口的工作区访问不到 `[证据 20]`。

### 2.5 页面怎样解析链接

4.0.0 页面的解析函数（`src-B6VfM4vn.js`，offset 54868）：带 `hash` 或 `t` 直接判为无效；只认 `sid`、`mid`、`name`、`app_version` 四个参数 `[证据 12]`。带 `hash`、`t` 的是旧版链接。

### 2.6 链接里没有凭证，但等同凭证

登录凭证和一次性令牌都不进链接 `[证据 5]` `[证据 9]`。但链接加上同账号登录就能控制这台电脑。App 里要按密码对待：不进日志，不在界面上显示，只显示电脑名。

## 3. 手机页面

### 3.1 从哪里加载

`GET https://zcode.z.ai/remote/v4`，网关按 `app_version` 选版本目录。调查时的探测结果（都不带 `sid`）`[证据 10]` `[复核 2]`：

| `app_version` | 结果 | 入口脚本 | 页面最后修改时间 |
| --- | --- | --- | --- |
| `4.0.0` | 200 | `/remote/v4/4.0.0/assets/index-FxFL5k5N.js` | 2026-11-22 |
| `3.17.2`、`3.18.2`、`3.19.1` | 200 | 各有各的入口脚本 | 没记录 |
| 不带 | 200 | `/remote/v4/latest/assets/index-B-ilXaCQ.js` | 2026-09-19 |
| `3.16.0`、`3.4.0`、`3.4.1`、`3.5.0`、`4.0.1`、`4.1.0`、`9.9.9` | 404，正文只有 `Not Found` | | |

线上没有版本清单（`release-manifest.json` 返回 404），有哪些目录只能逐个探测。

### 3.2 「latest」是个陷阱

不带 `app_version` 落到的 `latest` 目录名不副实，是 9 月的旧页面。它要求链接同时带 `sid`、`hash`、`t`，连的是 `wss://zcode.z.ai/ws`，也没有登录门禁 `[证据 11]`。4.0.0 电脑生成的链接不带 `hash` 和 `t`，所以丢掉 `app_version` 会被旧页面判成「缺少或无效的远控参数」。这一条是读代码得出的推断，没有拿真实链接试过。

### 3.3 页面的几个特点

- 缓存：HTML 是 `no-cache, no-store, must-revalidate`；入口脚本是 `public, max-age=31536000, immutable` `[证据 10]`。所以 App 平时不要清 WebView 缓存。
- 响应头里没有内容安全策略，也没有禁止内嵌的限制 `[复核 2]`。
- 不是 PWA：没有 manifest，没有 Service Worker `[证据 17]`。
- 和桌面端是同一套源码的网页构建 `[证据 17]`。
- 用安卓 Chrome、带 `; wv` 的 WebView、桌面 Linux 三种 UA 取 4.0.0 页面，结果逐字节相同 `[复核 2]`。UA 不影响拿到哪个页面。
- HTML 的 viewport 是 `width=device-width, initial-scale=1.0`，没有 `viewport-fit=cover` `[证据 35]`。刘海和系统栏的适配要 App 自己做。

## 4. 登录

### 4.1 要不要登录

要，而且必须和电脑端是同一个账号。页面启动时先看本地有没有登录凭证，没有就显示登录页 `[证据 14]`。登录页让用户选区域：「中国」或「全球」`[证据 15]`。

### 4.2 登录的六步

1. 页面调 `POST /api/v1/oauth/cli/init`，请求头里的凭证是页面本地生成的随机串，请求体只有区域。拿到授权地址、流程 id 和轮询间隔 `[证据 15]` `[复核 3]`。
2. 页面整页跳转到授权地址。页面只校验它是 https。授权地址由服务端返回；构建配置里写的是 `https://chat.z.ai`，实际返回的域名没有实测。
3. 用户在授权页完成登录，回到 `https://zcode.z.ai/remote/v4?code=…&state=…`。
4. 页面先确认这次回调属于自己发起的登录，再调 `GET /api/v1/oauth/cli/callback/{区域}` `[复核 3]`。
5. 轮询 `/api/v1/oauth/cli/poll/{流程id}`，拿到登录凭证，写进 localStorage `[证据 15]`。
6. 用 `location.replace` 回到原来的链接，只保留那四个参数，然后自动连接 `[证据 16]`。

### 4.3 登录态存在哪里

全部在 `zcode.z.ai` 同源的 localStorage 里 `[证据 15]`：

| 键 | 内容 |
| --- | --- |
| `zcodejwttoken` | 登录凭证 |
| `oauth:zai:access_token`、`oauth:zai_cn:access_token`、`oauth:bigmodel:access_token` | 各区域的访问令牌 |
| `oauth:active_provider` | 上次选的区域 |
| `oauth_pending_logins` | 进行中的登录记录 |
| `oauth:browser_session` | 浏览器会话标识 |

**这决定了三件事。** 登录必须全程留在同一个 WebView 里，回调落到别的浏览器会被拒 `[证据 16]`；清掉 WebView 的数据就等于退出登录；DOM 存储必须打开，WebView 里它默认是关的 `[复核 15]`。

登录还依赖 Web Locks：没有 `navigator.locks` 就直接报「浏览器会话锁不可用」`[复核 3]`。

### 4.4 凭证会过期，网页不续期

页面按凭证自带的有效期判断，留 30 秒余量 `[复核 3]`。过期后页面自己清掉登录态、回到登录页 `[证据 24]`。网页模式下没有续期 `[复核 3]`。凭证过期或接口返回 401 时，页面关闭连接、清掉本地会话、回到登录页，并把当前链接留作重新登录后的返回地址 `[证据 24]`。有效期实际多长由服务端决定，没查到。

## 5. 连接中转

### 5.1 四步

1. 用登录凭证调 `POST /api/v1/remote/token`，请求体是 `{role:"terminal"}`，换一个一次性令牌。返回 401 就是需要登录 `[证据 13]` `[复核 3]`。
2. 连 `wss://zcode.z.ai/api/v1/remote/ws2?token=…&mid=…`。
3. 发 `auth_init`，带上 `device_sid` 和平台信息（`platform:"web"`、`name:"mobile-browser"`）`[证据 13]`。
4. 中转回确认和配对状态。配对成功后页面发启动请求、打开工作区桥，之后的业务消息都在这条连接上走 `[证据 20]`。

中转只做鉴权、配对、心跳和透传，不保存任务状态。心跳约 5 秒一次，15 秒没有应答判定失联。

### 5.2 接口一览

| 接口 | 用途 | 证据 |
| --- | --- | --- |
| `POST /api/v1/oauth/cli/init` | 发起登录 | `[证据 15]` |
| `GET /api/v1/oauth/cli/callback/{区域}` | 处理登录回调 | `[证据 15]` |
| `/api/v1/oauth/cli/poll/{流程id}` | 轮询登录结果 | `[证据 15]` |
| `POST /api/v1/remote/device` | 电脑注册设备 | `[证据 5]` |
| `GET /api/v1/remote/device` | 列出账号下的电脑 | `[证据 13]` |
| `POST /api/v1/remote/device/delete` | 删除设备 | `[证据 5]` |
| `POST /api/v1/remote/token` | 换一次性令牌 | `[证据 5]` |
| `wss://zcode.z.ai/api/v1/remote/ws2` | 中转连接 | `[证据 5]` `[证据 13]` |

### 5.3 鉴权是两层

账号凭证换出 terminal 角色的令牌；中转再核对 `device_sid` 是不是属于这个账号。不属于时页面显示归属失败，主按钮是「切换账号」`[证据 24]`。

### 5.4 一次只能有一个手机页面

每个 `sid` 同时只允许一个手机页面，新页面会踢掉旧的 `[证据 22]`。桌面端的提示原文是「当前 relay 配对同一时间只支持一个手机页面；如果已经在别的页面打开，请先关闭旧页面」。

### 5.5 中转的错误码

`[复核 3]`

| 中转返回 | 页面的结果 |
| --- | --- |
| `KICKED` | 先重试；短时间内第二次被踢就终止，显示会话冲突 |
| `AUTH_FAILED` | 设备不属于当前账号 |
| `WRONG_PARAM` | 链接参数无效 |
| `DEVICE_OFFLINE` | 进入「电脑离线」的恢复流程 |

### 5.6 不扫码也能进

打开 `https://zcode.z.ai/remote/v4?app_version=<可用版本>`（不带 `sid`），登录后进入「选择电脑」列表；最近用过的电脑在线的话自动连上 `[复核 3]`。前提是那个版本目录在线上存在。

### 5.7 页面会自己改写版本和设备

连上之后，页面拿电脑的真实版本和地址栏里的 `app_version` 比较，不一样就改写地址 `[证据 23]`。上次用的 `sid` 不在线、而同一台电脑恰好只有一个在线连接时，页面会自动接到新的 `sid` `[证据 23]`。所以 App 不需要自己处理电脑升级后的 `sid` 和版本变化，只要链接是原样保存的。

## 6. 前后台、超时

- 页面隐藏、被冻结时主动挂起连接，回到前台再恢复 `[证据 21]`。App 不需要保活，页面在后台本来就不保持连接。
- 等待配对的超时是 30 秒；收到「电脑离线」后再等 15 秒。超时都算终态，页面显示电脑已断开 `[复核 5]`。
- 所以电脑休眠、断网或关窗口时，手机端在 15 到 30 秒内显示断开；电脑恢复后自动重连。

## 7. 电脑端的生命周期

| 操作 | 远程控制的状态 | 启用记录 |
| --- | --- | --- |
| 打开远控弹窗 | 开启（前提是桌面端已登录） | 首次到达「已就绪」时写入 |
| 点弹窗右上角关闭 | **不停**，只是关掉弹窗 | 保留 |
| 点弹窗里的「停止」 | 停 | 清除 |
| 退出 ZCode 再启动 | 自动恢复 | 保留 |
| 退出登录 | 全部停，状态变为「需要先登录」；同一账号重新登录后自动恢复 | 保留 |
| 关闭那个桌面窗口 | 停掉这个窗口的连接 | 保留 |
| 切换端点 | 暂停 | 保留 |

证据：`[证据 25]` 到 `[证据 29]`。

几条细节：

- 同一时刻全局只有一个远程控制会话，覆盖那个桌面窗口里所有已打开的工作区。想在手机上看到某个工作区，先在电脑端把它开在那个窗口里。
- 窗口被销毁后，同一个进程里再开新窗口不会自动恢复，要再点一次远控入口，或者重启应用。这条是读代码得出的推断 `[证据 28]`。
- 弹窗里的「已就绪」只表示电脑已连上中转、在等手机配对，没有有效期或倒计时。
- 电脑端不需要任何额外的常驻程序。要让手机随时连得上，保证电脑端 ZCode 在运行、已登录、窗口没关、网络通。

弹窗里的「使用 Bot Channel」（微信、飞书、Lark、Telegram）和扫码是并列的两种入口。它走聊天机器人平台，和网页无关，这个 App 用不上 `[证据 31]`。

## 8. 手机页面能做什么

`[证据 35]` 及调查的能力清单：

| 能力 | 有没有 | 说明 |
| --- | --- | --- |
| 发消息、打断 | 有 | 完整的聊天界面；任务运行时发送键变成「停止生成」 |
| 流式回复 | 有 | 订阅会话索引，断线后可以补齐 |
| 权限审批、回答提问 | 有 | 允许一次、始终允许、拒绝；拒绝时可以附反馈 |
| 传附件 | 有，靠文件选择器 | 最多 8 个，带上传进度和失败重试 |
| 查看文件 | 有 | 文件树，文本、图片、Office 预览 |
| 下载文件 | 有，靠原生接手 | 「保存副本」，单文件上限 50MB，取数超时 30 秒 |
| 用本地应用打开 | 没有 | 网页模式下平台层直接返回不支持 |
| 切换工作区 | 有 | 只能在那个桌面窗口已打开的工作区里切；不能新建连接 |
| 新建或添加工作区 | 没有 | 网页模式下不支持 |
| 看历史任务 | 有 | 按项目或时间线列出 |
| 通知 | 基本没有 | 见第 10 节 |

## 9. 页面在 WebView 里缺什么

WebView 的默认行为来自官方文档和 MDN 的兼容数据 `[复核 15]`；「页面怎么做」来自 4.0.0 的代码 `[证据 35]` `[复核 5]`。

| 页面怎么做 | WebView 默认 | App 要补的 | 里程碑 |
| --- | --- | --- | --- |
| 登录态和登录记录写在 localStorage；用 Web Locks | DOM 存储默认关闭 | 打开 DOM 存储；所有 http 和 https 留在 WebView | M2 |
| 附件用隐藏的 `<input type="file" multiple>`，另一处限定 `image/*` | 不实现文件选择回调时，所有文件请求都被取消，按钮没反应 | 实现文件选择回调，支持多选 | M2 |
| 外链用 `window.open` 打开 | 新窗口回调默认什么都不做、返回 false | 允许多窗口，取到地址后交系统浏览器 | M2 |
| 保存副本：取数据，转成 Blob，建临时地址，点一个带 `download` 的链接 | 不能直接保存这种临时地址（公开资料，没在真机上验证） | 页面脚本分块交给原生，原生弹系统保存框 | M4 |
| 页面自己管历史记录，用 `data-mobile-page` 标明当前页 | 返回键默认是历史后退 | 读属性后决定后退还是离开 | M2 |
| viewport 没有安全区设置 | 内容铺到系统栏下面；软键盘可能挡住输入框 | 系统栏留白；窗口随键盘调整 | M2 |
| 页面隐藏时挂起连接 | 渲染进程可能被系统回收 | 进程重建后重新加载；保存和恢复页面状态 | M2 |
| 本地路径选择、用本地应用打开 | 平台层不支持 | 不补 | 不做 |
| 网页通知 | WebView 不支持网页通知 API；页面也从不申请权限 | 不补 | 不做 |

另外几条对 App 有用的事实：

- 权限请求回调不覆写就是拒绝。保持默认。
- `androidx.webkit` 的 `addDocumentStartJavaScript`（1.9.0 起）和 `addWebMessageListener`（1.3.0 起）都能按来源限制注入，M4 只对 `https://zcode.z.ai` 生效。
- 应用的自动备份默认开启。`allowBackup="false"` 在部分厂商设备上拦不住设备到设备迁移，要同时配提取规则。
- `https://zcode.z.ai/.well-known/assetlinks.json` 返回 404。域名不是我们的，注册不了应用链接，系统浏览器里的链接不会自动唤起本 App。

## 10. 通知与推送

三条路都走不通，App 不做通知：

- **网页内通知**：页面只在「页面没有焦点且浏览器通知权限已授予」时才发，而且从不申请权限 `[证据 35]`。WebView 里这个 API 本来就不支持。
- **云端推送**：面向已登录的官方 App，按服务端登记的原生设备定向发送，密钥只在服务端 `[证据 34]`。网页里没有任何接收代码。
- **机器人渠道**：微信、飞书、Lark、Telegram 的推送，和网页无关。

需要提醒时，用电脑端自带的「使用 Bot Channel」。

## 11. ZCode 升级后的冒烟检查清单

来自方案第 19 条。下面的命令在 2026-11-24 对 4.0.0 实际执行过，表里的「当次数」是那次的结果。请求都不带 `sid`。

### 第 1 步：新版本的手机页面在不在

```bash
UA='<安卓 Chrome 的 UA>'
V='<新的桌面版本>'
curl -sS -A "$UA" -o "page-$V.html" -w 'http=%{http_code} size=%{size_download}\n' \
     "https://zcode.z.ai/remote/v4?app_version=$V"
curl -sI -A "$UA" "https://zcode.z.ai/remote/v4?app_version=$V" | grep -i -E '^HTTP/|last-modified'
```

| 结果 | 怎么办 |
| --- | --- |
| `http=200`，记下最后修改时间 | 继续。页面有更新，做第 2 步 |
| `http=404 size=10`，正文是 `Not Found` | 手机页面还没发布。App 里兜底入口的版本常量先不要改成新版本，继续用上一个可用的 |

4.0.0 当次的结果是 `http=200 size=18492`，`last-modified: Sun, 22 Nov 2026 14:45:05 GMT`。

### 第 2 步：页面里 App 依赖的东西还在不在

先取回入口脚本，再数关键标记。压缩代码的字符串用反引号，检索时别带双引号。

```bash
ENTRY=$(grep -o -E '<script[^>]*src="[^"]+"' "page-$V.html" | sed -E 's/.*src="([^"]+)".*/\1/' | head -1)
curl -sS -A "$UA" -o entry.js "https://zcode.z.ai$ENTRY"
for p in 'data-mobile-page' 'zcodeMobilePage' 'attachmentInputRef' 'type:`file`' 'accept:`image/*`' \
         'createObjectURL' '50*1024*1024' 'oauth/cli/init' 'oauth/cli/poll/' 'zcode-browser-oauth' \
         'oauth_pending_logins' 'zcodejwttoken' 'auth_init' 'desktopAppVersion' 'requestPermission'; do
    printf '%-24s %s\n' "$p" "$(grep -o -F -- "$p" entry.js | wc -l)"
done
```

| 标记 | 4.0.0 当次数 | App 依赖它做什么 | 变成 0 意味着 |
| --- | ---: | --- | --- |
| `data-mobile-page`、`zcodeMobilePage` | 2、5 | 返回键判断当前页 | 返回键的判断要重新适配 |
| `attachmentInputRef`、``type:`file` `` | 2、36 | 文件选择 | 附件入口变了 |
| ``accept:`image/*` `` | 1 | 反馈截图的文件选择 | 同上 |
| `createObjectURL`、`50*1024*1024` | 18、3 | M4 的保存副本 | 保存副本的脚本要重新适配 |
| `oauth/cli/init`、`oauth/cli/poll/` | 1、1 | 登录流程 | 登录流程变了 |
| `zcode-browser-oauth`、`oauth_pending_logins`、`zcodejwttoken` | 1、1、4 | 登录态存在同源存储里 | 「退出登录」的效果要重新确认 |
| `auth_init`、`desktopAppVersion` | 2、1 | 页面自己处理版本和设备接续 | 要重新确认链接是否还能原样使用 |
| `requestPermission` | 0 | 页面不申请通知权限 | 页面开始用通知了，结论要重看 |

`entry.js` 很大（4.0.0 是 7,411,071 字节），数完可以删掉。

链接解析和中转地址不在入口脚本里，在分块里。分块名带哈希，每个版本都不同。入口脚本以 `from"./src-….js"` 的形式直接引用它们，所以可以这样找出名字：

```bash
grep -o -E '(src|validation)-[A-Za-z0-9_-]{8}\.js' entry.js | sort -u
```

4.0.0 当次得到三个：`src-B6VfM4vn.js`、`src-DkINpCYq.js`、`validation-DzR_QiMq.js`。把它们从 `https://zcode.z.ai/remote/v4/<版本>/assets/` 取回来，再搜下面的片段。

| 分块 | 4.0.0 的名字 | 要搜的片段 | 4.0.0 当次数 |
| --- | --- | --- | ---: |
| 链接解析 | `src-B6VfM4vn.js` | ``has(`hash`)``、``has(`t`)``、``deviceSid`,`sid`` | 各 1 |
| 接口地址 | `validation-DzR_QiMq.js` | `/api/v1/remote/token`、`/api/v1/remote/ws2` | 1、2 |

`src-…` 里还有 ``has(`hash`)``，就说明旧版链接仍被拒绝；`validation-…` 里还有 `/api/v1/remote/ws2`，中转地址就没变。中转地址在这个分块里是由 `https:` 改写成 `wss:` 再拼上路径得到的，搜完整地址搜不到。

### 第 3 步：电脑端生成的链接变没变

在电脑端打开远控弹窗，用「复制链接」，对照第 2 节：还是 `https://zcode.z.ai/remote/v4`，还是那四个参数，没有 `hash` 和 `t`。变了就先更新这份笔记。

### 第 4 步：在手机上走一遍

用真实链接：登录能走通并回到原来的页面；能连上电脑；能发一条消息并打断；能选附件；聊天页和首页的返回键表现正常；断网后能出现原生断网页并重试成功；「保存副本」（M4 之后）能存下文件。同时看一眼 logcat 里有 `pageLoaded` 那一行，而且搜不到链接和凭证。

### 第 5 步：收尾

- 新旧版本目录哪个可用心里有数了，再决定要不要改 App 里兜底入口的版本常量。
- 把这一轮的日期、版本、结果记在团队约定的地方。这份笔记里和版本相关的表格（3.1、第 11 节）顺手更新。

## 12. 没查清的事

完整的清单在附录 C（调查列出的 8 条）和 WRAPPER-PLAN.md 的 `unverified`（复核列出的 9 条）。下面是最影响这个 App 的几条：

- **授权页。** 实际域名、登录方式、在 WebView 里的表现都未知。它会不会拒绝 WebView 的 UA、是否依赖第三方 Cookie、会不会拉起第三方 App，只有在真机上走一遍才知道。
- **WebView 里的行为没有实测。** 登录回跳、文件选择、新窗口、保存副本、返回键、键盘和刘海适配，调查时都是按代码和文档推出来的。
- **登录凭证的有效期**由服务端决定，客户端看不到。
- **旧版本目录保留多久**不知道。调查时 3.17.2 到 4.0.0 都在，更早的已经 404。兜底版本迟早要跟着换。
- **服务端会不会清理长期不在线的设备**（`sid`）没查到。
- 4.0.0 页面代码里还留着一条旧的登录路径（跳 `bigmodel.cn/login`），但登录界面上只有「中国」「全球」两个按钮，这条路有没有入口没查到。
- 旧页面（`latest`）拿到 4.0.0 链接时的具体报错，是读代码得出的，没有实测。

---

## 附录 A：调查的证据（原话，共 36 条）

证据文字是调查记录的原话，里面的「文件加偏移」都针对 4.0.0 的压缩代码。

### 证据 1

````text
[1] 包信息：解析 /tmp/.mount_ZCode-SGFRC9/resources/app.asar 头并提取 /out 下全部文件（自写 Python 脚本，输出 extracted 4252 files 69462222 bytes，落在 /tmp/zcode-remote-investigation/asar-extract）。out/metadata/build-meta.json 内容：appVersion 4.0.0、minCompatibleVersion 4.0.0、buildCommitId 3bc8a247、buildTime 2026-11-22T11:30:02.578Z。package.json：name @zcode/desktop，main out/main/index.js。
````

### 证据 2

````text
[2] 链接构造函数，app.asar 内 out/main/chunk-IERSWOXK.js offset 860780（line 64）：function Dve({baseUrl:t,deviceSid:n,deviceMid:r,deviceName:a,appVersion:o}){let i=new URL(t);return n!==void 0&&i.searchParams.set("sid",n),i.searchParams.delete("hash"),i.searchParams.delete("t"),r?.trim()&&i.searchParams.set("mid",r),a?.trim()&&i.searchParams.set("name",a),o?.trim()&&i.searchParams.set("app_version",o),i.toString()}（函数名标注 buildWebRemoteControlExternalQrUrl）。
````

### 证据 3

````text
[3] 二维码生成时机，out/main/index.js line 419 offset 559436 起：onStateChange:i(Se=>{…(Se==="waiting_terminal"||Se==="paired")&&(be.qrUrl=be.connectUrl=eL(Ze,Ur)),QM(be,Se),… nh??=e.startupRestoreStorageProvider.save({workspacePath:b.workspacePath,workspaceIdentity:b.workspaceIdentity,initialTaskId:b.initialTaskId})…；createQrUrl eL 在 offset 557662，调用 Yw({baseUrl:b,deviceSid:h,deviceMid:e.deviceMid,deviceName:e.deviceName,appVersion:e.appVersion})。
````

### 证据 4

````text
[4] 设备身份存储与复用，out/main/index.js：offset 506525 createWebRemoteControlDeviceStorage（load:(await loadSettings()).webRemoteControlRelayV2Device；save:patchSettings({webRemoteControlRelayV2Device:r})；clearLegacy 清 webRemoteControlExternalRelayDevice）；offset 534350 resolveRegistration：先 deviceStorage.load()，若 !M&&Q?.userId===we.userId&&Q.endpointOrigin===b 则返回 Q.deviceSid，否则 await X.delete(Q.deviceSid,…) 再 await X.register(e.deviceMid,we.jwt,N)，成功后 deviceStorage.save({deviceSid:se,userId:we.userId,endpointOrigin:b})。
````

### 证据 5

````text
[5] 中转 HTTP 接口，out/main/chunk-IERSWOXK.js offset 533605：{register:{method:"POST",path:"/api/v1/remote/device"},list:{method:"GET",path:"/api/v1/remote/device"},delete:{method:"POST",path:"/api/v1/remote/device/delete"},token:{method:"POST",path:"/api/v1/remote/token"}}；请求体 register 为 {device_mid}，token 为 {role:"device"|"terminal"}，响应 data {token, expires_in}。out/main/index.js offset 506940 createWebRemoteControlV2Http：请求头 Authorization: Bearer <jwt>；offset 507581 reportNotification：POST /api/v1/remote/notification/events，body {device_sid:n,event:s}。WS 地址解析在 chunk-IERSWOXK.js offset 475504 resolveWebRemoteControlRelayWsUrl：协议换 wss、pathname="/api/v1/remote/ws2"。
````

### 证据 6

````text
[6] mid 来源，out/main/index.js offset 158639（line 7）：function un(){…let r=zN(r,"telemetry-state.json"),n=VN(o);if(typeof n.deviceMid=="string"&&n.deviceMid)return n.deviceMid;let s=t();return n.deviceMid=s,qN(o,n),s…}（标注 ensureDesktopDeviceMidSync）。WS 连接带 mid：offset 513019 s.searchParams.set("token",n),s.searchParams.set("mid",this.options.deviceMid)。
````

### 证据 7

````text
[7] name 来源，out/main/index.js offset 421937：import{…,hostname as u5}from"node:os"；offset 983389 装配：deviceMid:Pt,deviceName:u5(),appVersion:Ng；offset 982677 Ng=ge||ee.getVersion()。设备 meta：offset 559436 起 meta:{platform:process.platform,version:e.appVersion,name:e.deviceName}。
````

### 证据 8

````text
[8] 本机设置与设备状态文件结构（只输出键名和类型，未输出任何值）：python3 -I shape.py ~/.zcode/v2/setting.json → webRemoteControlLastEnabledContext.workspacePath: str(len=20)；webRemoteControlRelayV2Device.deviceSid: str(len=24)；webRemoteControlRelayV2Device.endpointOrigin: str(len=18)；webRemoteControlRelayV2Device.userId: str(len=16)；mobilePushEnabled: bool。telemetry-state.json → deviceMid: str(len=36)；lastDailyActiveDate: str(len=10)。路径依据：out/main/index.js offset 566161 resolveApplicationLocaleBootstrapSettingsFile = <home>/.zcode/v2/setting.json。
````

### 证据 9

````text
[9] 命令行源码树 /home/lululu/.local/share/zcode-cli/source-3.17.1（版本 3.17.1，用作交叉印证）docs/web-remote-control/relay-v2-pairing.md:22「注册不接收 meta，同 MID 每次注册都产生新 SID…删除后的 SID 无法再认证」；:24「Token…绑定用户与角色，不绑定 SID…有效期 60 秒。在 WS Upgrade 前原子消费，失败不恢复，重连必须重新申请…JWT/token 不进入二维码或业务消息」；:28「入口 /api/v1/remote/ws2?token=...，可携带网关路由提示 mid」；:69-71 二维码契约与「带 hash 或 t 的旧链接返回 null」；web-remote-control-architecture.md:101「二维码为 /remote/v4?app_version=<ver>&sid=<sid>&mid=<mid>&name=<hostname>，不含 hash/t 或 JWT/token」。
````

### 证据 10

````text
[10] 页面入口实测（均不带 sid，curl -A <Android Chrome UA>）：GET https://zcode.z.ai/remote/v4 → 200，entry src="/remote/v4/latest/assets/index-B-ilXaCQ.js"，last-modified: Sat, 19 Sep 2026 05:28:02 GMT；GET /remote/v4?app_version=4.0.0 → 200，entry src="/remote/v4/4.0.0/assets/index-FxFL5k5N.js"，last-modified: Sun, 22 Nov 2026 14:45:05 GMT；?app_version=3.17.1 → 200；?app_version=3.4.0 / 3.4.1 / 3.5.0 / 3.16.0 / 4.0.1 / 4.1.0 → 404。资源 cache-control: public, max-age=31536000, immutable；HTML 为 no-cache, no-store, must-revalidate。
````

### 证据 11

````text
[11] latest 旧页面的协议，/tmp/zcode-remote-investigation/web/v4/files/：src-wmk2orCZ.js offset 275444 function pO(e){let t=lO(e,`sid`),n=lO(e,`hash`),r=lO(e,`t`)…return!t||!n||!Number.isFinite(i)?null:{deviceSid:t,passHash:n,timestamp:i,…}}；offset 134343 Yd=`wss://zcode.z.ai/ws`；index-B-ilXaCQ.js offset 4798185 入口 K4t 对该路由不检查登录。
````

### 证据 12

````text
[12] 4.0.0 页面参数解析，/tmp/zcode-remote-investigation/web/v4-4.0.0/files/src-B6VfM4vn.js offset 54868：function so(e){if(e.has(`hash`)||e.has(`t`))return null;…for(let[n,r]of[[`deviceSid`,`sid`],[`deviceMid`,`mid`],[`deviceName`,`name`],[`appVersion`,`app_version`]])…}；index-FxFL5k5N.js offset 7124247 kbr=new Set([`app_version`,`sid`,`mid`,`name`])，紧随其后的 Rbr 只保留这四个参数。
````

### 证据 13

````text
[13] 4.0.0 手机端连接代码，index-FxFL5k5N.js：offset 7208400 connect(){…let n=await this.options.getToken(t.signal)…r.searchParams.set(`token`,n),…r.searchParams.set(`mid`,this.options.deviceMid)…}；offset 7208881 send({type:`auth_init`,device_sid:this.options.deviceSid,meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},client_ts:…})；offset 7219714 GSr：POST Ar.token.path，Authorization: Bearer ${n}，body {role:`terminal`}；offset 7205304 RSr：GET Ar.list.path，Authorization: Bearer ${n}；offset 7270191 uwr(){return hr({endpointOrigin:window.location.origin})}。validation-DzR_QiMq.js offset 128094 给出 wss://zcode.z.ai/api/v1/remote/ws2。
````

### 证据 14

````text
[14] 4.0.0 入口控制器，index-FxFL5k5N.js offset 7223493：async start(){…if(nCr(e)){this.options.onView({kind:`callback`});return}if(!this.options.auth.getZCodeJwtToken()){this.options.onView({kind:`login`});return}let t=Vr(e.searchParams);if(!t){…invalid-mobile-connection…}if(!t.deviceSid){await this.openDevices(!0);return}this.connect({...t,deviceSid:t.deviceSid})}；offset 7227215 getToken:async e=>{let t=this.options.auth.getZCodeJwtToken()…if(!t)throw new v(`auth-required`)…}。
````

### 证据 15

````text
[15] 4.0.0 登录页，index-FxFL5k5N.js：offset 7161925 文案表 kxr["zh-CN"]：loginTitle「登录后继续使用 Web 远程控制」、loginDescription「请选择与电脑端相同的登录平台，并使用同一账号。」、authorizeAction「通过浏览器授权登录」、loginRegion {zai_cn:「中国」,zai:「全球」,bigmodel:「中国」}；offset 7165106 登录组件 Pxr，区域按钮 [Mi(=zai_cn),`zai`]；offset 7171424 认证服务 Jxr：startLogin → zxr(o,a,t.signal) 调 /api/v1/oauth/cli/init → this.runtime.assign(f.toString()) 整页跳转；completeRegionalCallback 调 /api/v1/oauth/cli/callback/{provider}?code&state 并轮询 /api/v1/oauth/cli/poll/{flowId}；存储键 oauth:active_provider、zcodejwttoken、oauth:zai:access_token、oauth:bigmodel:access_token、oauth:zai_cn:access_token、oauth_pending_logins、oauth:browser_session。
````

### 证据 16

````text
[16] 回跳净化，index-FxFL5k5N.js offset 7125469 T9（只允许同源 /remote/v4 等路径，query 仅 Rbr 白名单）；offset 7260181 ewr：成功后 window.location.replace(e??`/remote/v4`)。文档 docs/web-remote-control/web-remote-login.md:7-13（入口与认证、query 白名单）、:34（存储键）、:88（「缺少 app_version 的入口仍依赖 latest」）、:103（「回调落到另一个浏览器 App 时仍拒绝」）。
````

### 证据 17

````text
[17] 同源构建与 PWA 判定：桌面 renderer/assets 有 chunk-BO2N2NFS-BlNlXiN8.js、chunk-ICPOFSXX-B2F6lA8f.js、chunk-5FUZZQ4R-CJ8i7Lg7.js，手机 4.0.0 有 chunk-BO2N2NFS-BMtN-ZZr.js、chunk-ICPOFSXX-BaYwILS3.js、chunk-5FUZZQ4R-SntEDn9A.js；IntlProvider 两边都是 2,998,748 字节（内容哈希不同）。4.0.0 的 page.html 无 manifest.json；对 index-FxFL5k5N.js 搜索 serviceWorker / workbox / manifest.json 均 0 命中；Notification.requestPermission 0 命中。
````

### 证据 18

````text
[18] 桌面设备连接状态机，out/main/index.js offset 511357（WebRemoteControlDeviceTransport）：心跳 pair_status_query；auth_ack/pair_status_ack 映射 paired 或 waiting_terminal；KICKED 走 kickedDelay（60 秒内第二次返回 null，否则 2–5 秒）；握手 15 秒超时；首次就绪等待 X2=3e4。chunk-IERSWOXK.js offset 534804-535553 重试策略（1 秒起步、最大 30 秒）。文档 relay-v2-pairing.md:141-164 状态图与「保留上次启用意图，既不全局登出也不清除启用记录…重注册后更新 SID」；:173「刷新二维码的按钮、确认弹窗、平台方法和 IPC 删除」；grep 确认 4.0.0 的 styles-D2WmIVpU.js、preload/index.cjs、main/index.js 中 refreshQr 均 0 命中。
````

### 证据 19

````text
[19] 源码交叉印证（3.17.1）：packages/shared/src/web-remote-control.ts:168-191 buildWebRemoteControlExternalQrUrl、:193-208 parseWebRemoteControlExternalQrParams；packages/shared/src/zcodeEndpoint.ts:14 DEFAULT_WEB_REMOTE_CONTROL_RELAY_WS_URL="wss://zcode.z.ai/api/v1/remote/ws2"、:15 WEB_REMOTE_CONTROL_V4_MIN_APP_VERSION="3.4.0"、:372-380 remoteUrl/relayWsUrl；packages/shared/src/validationAppSettings.ts:148-167、580-582 三个设置键的 schema。
````

### 证据 20

````text
[20] 4.0.0 手机主流程，index-FxFL5k5N.js offset 7270455 起 pwr：bootstrap-request / bootstrap-response、platform-request、workspace-list-request、workspace-bridge-open → workspace-bridge-ready、workspace-reconnect-request、mobile-view-state-update、mobile-task-view-update、rpc-frame / rpc-frame-ack、bridge-degraded、workspace-bridge-error、app-error、mobile-diagnostic。文档 web-remote-control-architecture.md:105-115 列出同一组 zcode_type。
````

### 证据 21

````text
[21] 后台行为，index-FxFL5k5N.js offset 7228198 cCr：监听 visibilitychange（hidden → suspend）、freeze、resume、pageshow、pagehide、online。文档 relay-v2-pairing.md:273-288 挂起期间与回前台的恢复规则。
````

### 证据 22

````text
[22] 单页面与踢出：桌面 i18n webRemoteControl.singlePageNote「当前 relay 配对同一时间只支持一个手机页面；如果已经在别的页面打开，请先关闭旧页面。」；文档 relay-v2-pairing.md:63「先验证归属，再替换同角色旧 owner、发送 KICKED 并关闭旧连接」、:660「两台手机接管可能出现一次反向抢回」。
````

### 证据 23

````text
[23] 版本校验，index-FxFL5k5N.js offset 7230071 起 pCr：读 bootstrap 的 desktopAppVersion，若与 URL 的 app_version 不同，改写 URL 并 window.location.replace / 提示确认后重载。文档 relay-v2-pairing.md:520-527 旧 SID 自动接续（同 MID 唯一在线 SID 时自动换到新 SID）。
````

### 证据 24

````text
[24] 归属与失败处理，index-FxFL5k5N.js：offset 7222892 附近 oCr=new Set([`device-not-owned`,`desktop-disconnected`])；offset 7259606 QCr：device-not-owned 时主按钮「切换账号」、次按钮「选择其他设备」。文档 relay-v2-pairing.md:271「HTTP401 或重连前 JWT 缺失：关闭连接、清除本地会话、回登录页，当前完整 URL 作为再次登录返回地址」。
````

### 证据 25

````text
[25] 桌面弹窗开启逻辑，out/renderer/assets/styles-D2WmIVpU.js（L365）：offset 2709367 弹窗组件 bLt；offset 2709957 起 open 后 getWebRemoteControlStatus()，条件 YFt(e,n,r,i)||XFt(e) 为真则沿用现状，否则 startWebRemoteControl({workspacePath,workspaceIdentity,remoteSessionId,initialTaskId})；offset 2710984 window.setInterval(…,1e3) 轮询状态；offset 2631346 YFt（同一工作区会话）、XFt（已有手机连接的会话）。
````

### 证据 26

````text
[26] 关闭与停止，styles-D2WmIVpU.js：offset 2712931 内 D=()=>{…J.info(`[WebRemoteControlDialog] 用户点击右上角关闭按钮`,{…}),E(!1)}，E 只做 onOpenChange；offset 2712017 w 调 o.stopWebRemoteControl() 后提示「已关闭 Web 远程控制」并关闭弹窗；offset 2711548 C 把 c.connectUrl 写入剪贴板。
````

### 证据 27

````text
[27] 状态灯与标签，styles-D2WmIVpU.js：offset 2632679 function $Ft(e){return e.status===`error`?`bg-destructive`:e.status===`active`?`bg-success`:e.status===`idle`?`bg-border`:`bg-warning`}；offset 2632796 eIt：已连接显示手机平台/名称或「手机」，idle「未开启」，error「连接失败」，否则「已就绪」。桌面状态序列化 out/main/index.js offset 539285 buildRuntimeStatus（无 expiresAt 字段）；offset 540215 手机断开 3 秒宽限（Y2=3e3）。
````

### 证据 28

````text
[28] 启用记录的清除与保留，out/main/index.js（L419）：offset 562222 async stop(h){P+=1,T+=1,y=!0,…await fe(h,"manual-stop"),await e.startupRestoreStorageProvider.clear()}；offset 562919 restorePreviouslyEnabled：if(…,y||t.has(h)||o.has(h))return!1，成功后 y=!0；offset 565107 disposeWindow：fe(h,"window-disposed") 且不调用 clear；offset 562233 notifyAuthStateChanged：signed-in 时逐窗口 restorePreviouslyEnabled 且成功即 break，signed-out 时全部 requireAuthentication。源码对应 webRemoteControlManager.ts:2019-2029、2031-2036、2038-2096、2098-2163、2277-2284；文档 web-remote-control-architecture.md:260-287、relay-v2-pairing.md:229「同一进程只认领一次上次启用目标…手动停止清除启用记录，后续登录不复活它」。
````

### 证据 29

````text
[29] 恢复触发点，out/main/index.js：offset 920520（L1649）oe.on(R.SyncWebRemoteControlWorkspaces,…)；offset 1011156（L1671）syncWebRemoteControlWorkspaces:(p,w)=>{zt.syncAvailableWorkspaces(p,w),zt.restorePreviouslyEnabled(p,w)}；offset 927436（L1649）IPC StartWebRemoteControl / StopWebRemoteControl / GetWebRemoteControlStatus / WebRemoteControlAuthStateChanged；offset 749342（L1107）窗口 closed 事件调用 webRemoteControlManager.disposeWindow；offset 996270（L1671）仅在切换端点时 suspend(e.id,"endpoint-changed")。
````

### 证据 30

````text
[30] 通知资格与上报，out/main/index.js：offset 528511 MobilePushConnection（每 60 秒 refresh；GET /api/v1/remote/notification/status，需 protocol_version===1 且 event_transport==="http"）；offset 986242 zt.startNotifications(); fd.onMobilePushEvent=e=>zt.sendNotification(e,()=>Ve.get().then(t=>({enabled:t.mobilePushEnabled!==!1,…})))；out/host/index.js offset 2252940 tge（session 观察器，running 时 onRunStarted，交互变化 onNotification）经 channel mobile-push:event 广播到主进程。
````

### 证据 31

````text
[31] Bot Channel，styles-D2WmIVpU.js：offset 2708811 vLt=[{provider:`weixin`},{provider:`feishu`},{provider:`lark`},{provider:`telegram`}]；卡片 onClick:()=>O(e.provider)，O 只 set 入口 provider 并打开 BotsDialog（日志「打开 Bot Channel 配置入口」）；文件末尾挂载 J(_Lt,{open:m,onOpenChange:h,entryProvider:g})；offset 2659706 MIt：lark→「全球」标签、feishu→「中国」标签。文档 docs/web-remote-control/remote-control-bot-channel-entry.md:3-13。
````

### 证据 32

````text
[32] 桌面 i18n（out/renderer/assets/IntlProvider-CWWc_lf7.js line 23）关键条目：webRemoteControl.description「扫码或在手机上打开链接，即可远程控制当前工作区。」；webRemoteControl.authRequired.description「登录后即可使用手机远程控制。手机和电脑需登录同一账号。」；webRemoteControl.statusTag.ready「已就绪」；webRemoteControl.botChannel.description「连接聊天 Bot，适合更长时间的移动端访问。」；webRemoteControl.failure.unsupportedAction「…只支持访问桌面端当前窗口已经打开的工作区。」；settings.mobilePush「手机推送通知」；settings.mobilePushDescription「桌面不在身边时，任务完成、失败或需要确认会推送到已登录的 ZCode App。」。
````

### 证据 33

````text
[33] 本机日志统计（只读 ~/.zcode/v2/logs/2026-11-*.log，只取枚举字段，未输出任何 id）：[mobile-push] eligibility (supported,status) 计数 {(True,'available'):161, (True,'unavailable'):72, (False,'unknown'):185}，最近一条 2026-11-23 23:43:42 为 (True,'available')；receipt (kind,event_state,status)：open 事件全部 rejected（task_completed 86、task_failed 13、question 7、approval 6、plan 1），resolved 事件全部 accepted（question 7、approval 6、plan 1）；dispatch skipped 原因均为 eligibility_unavailable。
````

### 证据 34

````text
[34] 云端推送规格，docs/web-remote-control/mobile-event-push.md:5「默认关闭，部署方需填写推送服务地址和 secret」；:10「桌面登录后自动发现通知资格，以 HTTP 上报，无需开启远控」；:44-50 五类事件 question / approval / plan / task_completed / task_failed；:60-67 HTTP 契约、「Relay WebSocket 只承载远控，不携带通知字段或消息」；:97、:124-127 按服务端登记的原生 App 登录设备定向发送（注册标识 zbot*<device_id>*<user_id>），密钥只在服务端；:183 通知点击载荷 open_target=zcode_remote_control。
````

### 证据 35

````text
[35] 原生适配相关实测与代码：curl https://zcode.z.ai/.well-known/assetlinks.json → 404；4.0.0 page.html 的 viewport 为 width=device-width, initial-scale=1.0。index-FxFL5k5N.js offset 3800333：jsx("input",{ref:Kt.attachmentInputRef,type:"file",multiple:!0,className:"hidden",onChange:Kt.handleAttachmentInputChange})（无 accept）；offset 3522552-3522886 附件上限 8；offset 7137623 uxr（saveFile：fetch → Blob → createObjectURL → a.download），F9=50*1024*1024、sxr=3e4；offset 7138413 起平台适配：canSelectFilePath:!1,canOpenLocalFiles:!1,selectFiles:()=>Promise.resolve([]),openExternal:e=>{window.open(e,"_blank","noopener,noreferrer")},openExternalFile:「Not supported in web mode」,stopWebRemoteControl:()=>Promise.resolve()；offset 7141038 showTaskNotification:e=>{if(!document.hasFocus()&&!(window.Notification===void 0||window.Notification.permission!=="granted"))try{new window.Notification(e.title,{body:e.body,silent:!0}),Sbr()}catch{}}；offset 5426880-5427841 移动壳 history 管理（BQn pushState、VQn、HQn、data-mobile-page）。
````

### 证据 36

````text
[36] 调查边界：全程只读。没有开启或停止电脑端远程控制，没有改任何设置；网络请求只有不带 sid 的 GET/HEAD（其中一次用了占位参数 mid=x&name=y）；没有用真实链接连中转；设置、日志只看键名、类型和枚举字段，没有抄录任何令牌值。提取的归档内容与下载的页面放在 /tmp/zcode-remote-investigation/，分析脚本放在 /tmp/zcode-remote-investigation-scripts/。4.0.0 包是压缩代码，定位用「文件 + 字符偏移」；3.17.1 源码树和文档只作交叉印证，两者有出入时以 4.0.0 包为准。
````

## 附录 B：复核的事实（原话，共 15 条）

复核是为了独立确认调查的结论，事实文字是复核记录的原话，偏移同样针对 4.0.0。

### 复核 1

````text
复核① 链接契约（自己读 app.asar）。取证：用自写脚本解析 /tmp/.mount_ZCode-SGFRC9/resources/app.asar（out/metadata/build-meta.json：appVersion 4.0.0、minCompatibleVersion 4.0.0、buildTime 2026-11-22T11:30:02.578Z），提取 out/main/index.js、out/main/chunk-IERSWOXK.js 后按字符偏移阅读。读到 out/main/chunk-IERSWOXK.js:64（offset 861179）：`function Dve({baseUrl:t,deviceSid:n,deviceMid:r,deviceName:a,appVersion:o}){let i=new URL(t);return n!==void 0&&i.searchParams.set("sid",n),i.searchParams.delete("hash"),i.searchParams.delete("t"),r?.trim()&&i.searchParams.set("mid",r),a?.trim()&&i.searchParams.set("name",a),o?.trim()&&i.searchParams.set("app_version",o),i.toString()}`，标注 buildWebRemoteControlExternalQrUrl。name 是电脑主机名：out/main/index.js:400（offset 421925）`hostname as u5`from"node:os"；index.js:1670（offset 983863）`deviceName:u5(),appVersion:Ng`。二维码和「复制链接」是同一个地址：index.js:419（offset 559949），状态为 waiting_terminal 或 paired 时 `be.qrUrl=be.connectUrl=eL(Ze,Ur)`。结论：链接只有 sid/mid/name/app_version 四个可选参数，不含 hash、t 或 JWT；name 不是工作区名。
````

### 复核 2

````text
sid 何时变（out/main/index.js:419，offset 535243 resolveRegistration）：`if(N.throwIfAborted(),!M&&Q?.userId===we.userId&&Q.endpointOrigin===b)return Q.deviceSid;`，否则先 `X.delete(Q.deviceSid,we.jwt,N)` 再 `X.register(e.deviceMid,we.jwt,N)` 并 `deviceStorage.save({deviceSid:se,userId:we.userId,endpointOrigin:b})`。即同账号、同端点、未被要求重注册时 sid 不变；换账号或换端点会换成新 sid。状态里没有有效期字段（index.js:419 offset 539533 buildRuntimeStatus 只有 status/sessionId/windowControlSessionId/mobileConnected/qrUrl/connectUrl/workspacePath/error/failure 等）。mid 来自 telemetry-state.json 的 deviceMid（index.js:7 offset 158713 ensureDesktopDeviceMidSync）。
````

### 复核 3

````text
复核② 页面版本目录（自己 curl，均不带 sid）。命令：`curl -sS -A '<Android Chrome UA>' -o <file> -w 'http=%{http_code} size=%{size_download}' 'https://zcode.z.ai/remote/v4?app_version=<v>'` 加一次 `curl -I` 看 last-modified。结果：不带 app_version → 200，入口 /remote/v4/latest/assets/index-B-ilXaCQ.js，last-modified Sat, 19 Sep 2026；app_version=4.0.0 → 200，入口 /remote/v4/4.0.0/assets/index-FxFL5k5N.js，last-modified Sun, 22 Nov 2026；3.19.1、3.18.2、3.17.2 → 200（各 200 自有入口脚本）；4.0.1、4.1.0、9.9.9 → 404，正文只有 10 字节 `Not Found`。用 Android Chrome UA、含 `; wv` 的 WebView UA、桌面 Linux UA 取 4.0.0 页面，cmp 逐字节相同。响应头没有 Content-Security-Policy 和 X-Frame-Options；HTML 为 cache-control: no-cache, no-store, must-revalidate；入口脚本为 public, max-age=31536000, immutable。HTML 的 viewport 为 `width=device-width, initial-scale=1.0`，没有 viewport-fit=cover。本机请求经本地代理（响应首行 HTTP/1.1 200 Connection established，remote_ip=127.0.0.1）。
````

### 复核 4

````text
不带 app_version 会落到旧协议页面：/remote/v4/latest/assets/src-wmk2orCZ.js:46（offset 275590）`function pO(e){let t=lO(e,"sid"),n=lO(e,"hash"),r=lO(e,"t"),i=r?Number(r):NaN,…return!t||!n||!Number.isFinite(i)?null:{deviceSid:t,passHash:n,timestamp:i,…}`，同文件:41 `Yd="wss://zcode.z.ai/ws"`。4.0.0 页面反过来拒绝带 hash/t 的链接：/remote/v4/4.0.0/assets/src-B6VfM4vn.js:6（offset 54867）`function so(e){if(e.has("hash")||e.has("t"))return null;…[["deviceSid","sid"],["deviceMid","mid"],["deviceName","name"],["appVersion","app_version"]]}`。所以 app_version 必须原样保留。（latest 页面对 4.0.0 链接的具体报错是读代码得出的，没有用真实链接实测。）
````

### 复核 5

````text
复核③ 登录流程（自己读 index-FxFL5k5N.js:763）。入口 start()（offset 7343166 起）：URL 带 code/authCode/error+state 就进 callback 视图；没有 JWT 就显示 login；参数解析失败报 invalid-mobile-connection；没有 sid 就 `await this.openDevices(!0)`；否则 connect。登录步骤：POST /api/v1/oauth/cli/init（Bearer 是页面本地生成的随机串，body 只有 provider）→ 取 authorize_url → `this.runtime.assign(f.toString())`，即 `window.location.assign` 整页跳转（offset 7172681）→ 回到 /remote/v4?code=…&state=… 后 completeRegionalCallback（offset 7175018）先校验 `ownsLoginAttempt(nonce,expectedSession)`，再 GET /api/v1/oauth/cli/callback/{provider} 并轮询 /api/v1/oauth/cli/poll/{flowId}。登录记录和登录态都存在同源 localStorage：`oauth_pending_logins`、`oauth:browser_session`、`zcodejwttoken`、`oauth:zai:access_token`、`oauth:zai_cn:access_token` 等（offset 7129401 起）。登录还依赖 Web Locks：`withSessionLock(e){return navigator.locks?navigator.locks.request("zcode-browser-oauth",e):Promise.reject(Error("Browser session locking is unavailable"))}`。JWT 是否过期按自带 exp 判定并留 30 秒余量（src-B6VfM4vn.js:3 offset 33415：`t+Math.max(0,n)>=l?{kind:"expired",expiresAt:l}:{kind:"valid",expiresAt:l}`），过期后页面回登录页；Web 模式没有续期（index-FxFL5k5N.js:763 offset 7268295 `refreshToken:async()=>{}`）。登录文案（offset 7162112）：「登录后继续使用 Web 远程控制」「请选择与电脑端相同的登录平台，并使用同一账号。」，区域按钮「中国」「全球」。桌面端自己的登录也走同一个 init 接口并轮询，只校验 https（out/host/index.js:198 offset 1320936 startOAuthWithPolling），授权地址由服务端返回。
````

### 复核 6

````text
连接方式（index-FxFL5k5N.js:763）：POST /api/v1/remote/token，头 `Authorization: Bearer ${n}`，body `{role:"terminal"}`，401 即 auth-required（offset 7219714）；`new WebSocket(relayWsUrl?token=…&mid=…)`，open 后 `send({type:"auth_init",device_sid:…,meta:{platform:"web",version:…,name:"mobile-browser"},client_ts:…})`（offset 7208875）；桌面侧同一个地址 wss://zcode.z.ai/api/v1/remote/ws2（out/main/chunk-IERSWOXK.js:62 offset 475668），路由表 register/list/delete/token 在同文件 offset 533634。中转错误处理（offset 7333624）：`KICKED`→`this.retry.kickedDelay()`，为 null 则终态 session-conflict；`AUTH_FAILED`→device-not-owned；`WRONG_PARAM`→invalid-mobile-connection；`DEVICE_OFFLINE`→recoverFromDeviceOffline。交叉印证（3.17.1 源码树 docs/web-remote-control/relay-v2-pairing.md，仅作参照）：:24 token 有效期 60 秒、WS 升级前原子消费、JWT/token 不进二维码；:63 同角色新连接替换旧 owner 并发 KICKED；web-remote-login.md:103「回调落到另一个浏览器 App 时仍拒绝」。与 4.0.0 包代码一致。
````

### 复核 7

````text
版本和设备自愈（index-FxFL5k5N.js:763）：pCr（offset 7349935）拿到 bootstrap 后比较 `desktopAppVersion` 与 URL 的 app_version，不同就改写 URL 并跳转或提示确认；aCr：上次用的 sid 在线就直连，否则同一 mid 恰好只有一个在线连接就自动接到那个新 sid；refreshDevices（offset 7224275）命中就 `this.selectDevice(s)`，否则显示设备列表；选中后把 sid/mid/name 写回 URL 并 rememberDevice。所以 `https://zcode.z.ai/remote/v4?app_version=<可用版本>`（不带 sid）是免扫码入口。
````

### 复核 8

````text
复核④ 电脑端生命周期（自己读代码）。开弹窗即开启：out/renderer/assets/styles-D2WmIVpU.js:365 offset 2710021，`getWebRemoteControlStatus()` 后若不是 auth-required、不是同工作区会话、也没有手机连着，就 `startWebRemoteControl({workspacePath,workspaceIdentity,remoteSessionId,initialTaskId})`，并 `window.setInterval(…,1e3)` 轮询。右上角关闭不停：offset 2806162 `D=()=>{J.info("[WebRemoteControlDialog] 用户点击右上角关闭按钮",…),E(!1)}`，E 只做 onOpenChange；refreshQr 0 命中。只有手动停止才清记录：out/main/index.js:419 offset 561461 起 `async stop(h){…await fe(h,"manual-stop"),await e.startupRestoreStorageProvider.clear()}`。重启自动恢复：index.js:419 offset 562946 restorePreviouslyEnabled，`if(y||t.has(h)||o.has(h))return!1`，否则读启用记录，保存的工作区不在本窗口就 `we.find(Bc)??we[0]`（退到第一个可用工作区），再 `this.start(h,…)`。退出登录停掉全部并置 auth-required，重新登录时 notifyAuthStateChanged（offset 562205）逐窗口恢复。窗口关闭就停：index.js:1107 offset 749342，窗口 `closed` 时 `e.webRemoteControlManager.disposeWindow(n)` 并杀掉该窗口的 host 进程。
````

### 复核 9

````text
本机日志实证（只读 ~/.zcode/v2/logs/2026-11-23.log 与 2026-11-24.log，输出时把路径和长标识打码，共解析 81 条 [web-remote-control] 记录）：2026-11-23 每个新主进程启动时都有 `[web-remote-control] restoring previous enabled state window=1 target=<r> workspace=<r> remoteSession=none`，随后 `V2 device token acquired`，共 5 次（12:22:45、13:02:36、14:56:07、15:25:10、22:06:46）；14:59:46 有 `mobile diagnostic`、`attaching workspace bridge window=1 …`、`workspace bridge active window=1 …`，说明手机真实配对并桥接成功过；窗口关闭时记 `stopped window=1 session=<r> reason=window-disposed`（12:38、14:55、15:01、22:06:40）；当前 4.0.0 主进程（pid 4073958）23:39:16 记 `reason=restart`，23:39:17 重新拿到 token，此后到 2026-11-24 00:25:22 没有 stopped 记录。
````

### 复核 10

````text
本机设置与关窗口行为。取证：`python3 -I settings_facts.py ~/.zcode/v2/setting.json`，脚本只打印白名单布尔值和两个键的子键名，结果是 closeToTrayOnWindows=False、closeToTrayLinuxMigrationInitialized=True、keepAwakeWhileRunning=False、mobilePushEnabled=True、autoDownloadAndInstallUpdates=True；webRemoteControlLastEnabledContext 存在（子键只有 workspacePath），webRemoteControlRelayV2Device 存在（子键 deviceSid/endpointOrigin/userId）。代码：index.js:1038 offset 734438，Linux 上 closeToTray 取 `e.stored??!1`；index.js:1107 offset 750896 OA，win32/linux 且 `closeToTraySupported===!0&&e.closeToTray&&!e.forceQuit&&!e.explicitQuitRequested` 才 hideWindow，否则最后一个窗口走退出流程；index.js:1670 offset 973450，「保持电脑运行」的实现是 `powerSaveBlocker.start("prevent-app-suspension")`。设置文案（out/renderer/assets/IntlProvider-CWWc_lf7.js）：「关闭窗口时隐藏到托盘」，说明「点击关闭按钮或关闭窗口快捷键时隐藏窗口，托盘中的退出仍会完全退出应用。Windows 默认开启；Linux 上取决于桌面环境是否提供系统托盘。」；「保持电脑运行」说明「打开后阻止系统因空闲进入休眠（仍可手动睡眠/合盖休眠）。桌面端全局生效。」。主进程里 setLoginItemSettings、openAtLogin、autostart 均 0 命中（没有开机自启功能），requestSingleInstanceLock 1 命中（单实例）。
````

### 复核 11

````text
本机环境：Ubuntu 26.04.1 LTS，XDG_CURRENT_DESKTOP=ubuntu:GNOME，Wayland。`gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.NameHasOwner org.kde.StatusNotifierWatcher` 返回 (true,)，`gnome-extensions list` 里有 ubuntu-appindicators@ubuntu.com，托盘可用。`systemctl status suspend.target` 显示 11月22日 17:16:56 曾 Reached target sleep.target，这台机器确实会休眠。出网经本地代理：curl 到 zcode.z.ai 的 remote_ip=127.0.0.1，去掉代理环境变量并 --noproxy '*' 后解析到 198.18.0.15，`ss -ltn` 有 *:7897 监听。进程：4.0.0 主进程 pid 4073958（11月23日 22:06:40 启动）；3.19.1 的 Electron 主进程已退出，只剩 AppImage 外壳 pid 3634099 和 crashpad handler pid 3634199 占着旧挂载点 /tmp/.mount_ZCode-yjUlco。
````

### 复核 12

````text
复核⑤ 原生能力对应的页面行为（index-FxFL5k5N.js:763 的 Web 平台适配层，offset 7138547 起）：`saveFile:uxr`，实现是 fetch→Blob→`URL.createObjectURL`→`a.download` 点击，上限 `F9=50*1024*1024`、取数超时 3e4 毫秒；`canSelectFilePath:!1,canOpenLocalFiles:!1`，`selectFile:()=>Promise.resolve(null)`，`selectFiles:()=>Promise.resolve([])`；`connectRemote` 在 Web 模式直接返回 `Remote connect is not supported in Web remote control mode yet`。附件靠隐藏 input：`jsx("input",{ref:Kt.attachmentInputRef,type:"file",multiple:!0,className:"hidden",onChange:Kt.handleAttachmentInputChange})`（line 522 offset 3800293，无 accept），另一处 accept 为 image/* multiple（line 342 offset 1948849）。外链：`openExternal:e=>{window.open(e,"_blank","noopener,noreferrer")}`（offset 7140216）。通知：`showTaskNotification:e=>{if(!document.hasFocus()&&!(window.Notification===void 0||window.Notification.permission!=="granted"))try{new window.Notification(…)}`（offset 7141038），整份脚本里 Notification.requestPermission 和 serviceWorker 都是 0 命中。后台：offset 7228483，`visibilitychange` 为 hidden 时挂起，visible/pageshow/online/resume 时恢复，freeze/pagehide 时挂起。超时：等待配对 `waitingTimeoutMs??3e4`，收到 DEVICE_OFFLINE 后 `desktopOfflineGraceMs??15e3`，超时都是终态 desktop-disconnected（offset 7214916、7216323）。
````

### 复核 13

````text
返回键相关（index-FxFL5k5N.js:651，offset 5426708 到 5428489）：进聊天页 `window.history.pushState({zcodeMobilePage:"chat"},"")`，若当前 state 是 home 则 replaceState；页内的返回按钮是 `v("home")` 加 `window.history.replaceState({...e,zcodeMobilePage:"home"},"")`，不是 history.back()；DOM 是 `<section data-mobile-page="home">` 或 `"chat"`，返回按钮 aria-label 对应 i18n 键 webRemoteControl.mobileShell.backHome；popstate 监听按 `zcodeMobilePage` 是否为 chat 切换。
````

### 复核 14

````text
WebView 平台事实（查官方文档和 MDN 兼容数据）。developer.android.com/reference/android/webkit/WebSettings：setDomStorageEnabled「Sets whether the DOM storage API is enabled. The default value is false.」；…/WebChromeClient：onShowFileChooser「The default behavior is that WebView will cancel all file requests.」；onCreateWindow「The default implementation of this method does nothing and hence returns false.」；onPermissionRequest「If this method isn't overridden, the permission is denied.」。MDN browser-compat-data（raw.githubusercontent.com 当日拉取后解析）：api.Notification 的 webview_android 为 {"version_added": false, "impl_url": "https://crbug.com/40443309"}；api.Navigator.locks 的 webview_android 为 mirror（Chrome 69 起）。androidx.webkit 的 WebViewCompat.addDocumentStartJavaScript（1.9.0 起，需 DOCUMENT_START_SCRIPT）和 addWebMessageListener（1.3.0 起，需 WEB_MESSAGE_LISTENER）可按 allowedOriginRules 限定注入来源。Auto Backup 文档：allowBackup 默认 true；targetSdk 31+ 时 allowBackup=false 在部分厂商设备上不禁用设备到设备迁移，要同时配 dataExtractionRules。blob 下载：公开资料（Stack Overflow 53647087、StreamSaver.js issue #236）说明 WebView 不能直接保存 blob: 地址，要在页面 JS 里读出再交给原生。`curl https://zcode.z.ai/.well-known/assetlinks.json` → 404。
````

### 复核 15

````text
取证范围披露：全程只读。没有开启或停止电脑端远程控制，没有打开远控弹窗，没有用真实链接连中转，没有抄录任何令牌或密钥的值；网络请求只有不带 sid 的 GET/HEAD，另有下载 MDN 兼容数据 JSON。读 setting.json 只输出 4 个布尔值和两个键的子键名；读日志时打码了路径与长标识。有一处要说明：为确认版本我执行了两次 `<挂载点>/zcode --version`（无输出），之后 ps 确认两个主进程都还在、没有带 --version 的残留进程；这次调用可能经单实例锁转给了运行中的实例，影响应仅限于聚焦窗口，但我无法完全排除。下载文件和脚本放在 /tmp/zcode-remote-verify-1795454595-328633/（scripts 与 data 分目录）。`find /home/lululu/lu的学习空间 -maxdepth 2 -newer <我的脚本> -not -path '*/.zcode/*'` 输出为空，工作区无改动；setting.json 的 mtime 仍是 2026-11-24 00:44:28（早于我开始取证的时间）。
````

## 附录 C：调查列出的未知事项（原话，共 8 条）

调查明确列为「没查到」的事项，原话如下。

### 未知事项 1

````text
授权页的实际域名和登录方式：authorize_url 由 POST /api/v1/oauth/cli/init 返回，页面只校验 https；发起该请求会在服务端创建登录流程，属于有副作用的操作，没有执行。授权页里是手机号验证码、扫码还是密码，不在页面代码中，没查到。
````

### 未知事项 2

````text
服务端对设备 sid 是否有过期或清理策略：客户端和文档只说明「同 MID 每次注册产生新 SID」「删除后不可再认证」「只能删除自己的离线设备」，没有有效期说明，没查到。
````

### 未知事项 3

````text
云端推送对本人账号实际失败的原因：本机日志里 open 事件的回执全部是 rejected，但代码只记录 status 不记录原因，没查到；线上 mobile_push 总开关和设备登记接口的实际配置也看不到。官方「ZCode App」的推送设备注册协议不在所查代码内，第三方 App 能否接入没有依据。
````

### 未知事项 4

````text
没有做真机端到端验证：按规则没有拿真实链接打开页面，所以「latest 旧页面对 4.0.0 链接报参数无效」「窗口销毁后同进程内不自动恢复」「4.0.0 恢复时回退到第一个可用工作区」都是从代码读出的推断。
````

### 未知事项 5

````text
桌面关闭到托盘时窗口是否被销毁（从而停止远控）没有追；只确认了窗口 closed 事件会触发 disposeWindow。
````

### 未知事项 6

````text
安卓 WebView 对 blob 下载、window.open、网页通知 API 的具体行为属于平台常识，本次没有在真机或模拟器上验证。
````

### 未知事项 7

````text
4.0.0 页面代码里还保留一条 bigmodel 旧式登录路径（跳 bigmodel.cn/login?redirect=…&appId=zcode），但 4.0.0 登录界面只有「中国」「全球」两个区域按钮，这条路径在 4.0.0 界面上是否有入口没查到。
````

### 未知事项 8

````text
release-manifest.json 实测返回 404，线上有哪些版本目录只能靠逐个探测；除 4.0.0 和 3.17.1 外是否还有其他可用版本没有穷举。
````
