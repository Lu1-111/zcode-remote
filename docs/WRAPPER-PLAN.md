# 复核与套壳方案（原文存档）

> **本文件只做存档。** 下面各代码块里的文字，是「复核与套壳方案」里 `wrapperPlan`、`desktopSide`、
> `risks`、`unverified` 四个字段的原话，一字未改。标题、编号和本页顶部、末尾的说明是存档时加的，
> 不属于原文。

> 别处说的「方案第 N 条」，指的就是本页 `wrapperPlan` 的第 N 条（从 1 数起，顺序与原字段一致）。
> 实现要求以本页为准；工程怎样落地见 [ARCHITECTURE.md](ARCHITECTURE.md)，
> 背景事实与证据见 [remote-control-notes.md](remote-control-notes.md)。

存档来源：工作流「ZCode 手机遥控安卓套壳 App」里「远程控制复核员」的复核结果，2026-11-24 存档。

## 怎样核对本页没有被改动

下表是每个字段的 SHA-256。算法：列表字段把各项原话用一个换行符连接、末尾不加换行；
`desktopSide` 直接取原话；都按 UTF-8 编码后计算。拿到原始 JSON 时，可以这样重算：

```python
import hashlib, json

plan = json.load(open("review-plan.json", encoding="utf-8"))
for name in ("wrapperPlan", "risks", "unverified"):
    text = "\n".join(plan[name])
    print(name, hashlib.sha256(text.encode("utf-8")).hexdigest())
print("desktopSide", hashlib.sha256(plan["desktopSide"].encode("utf-8")).hexdigest())
```

| 字段 | 条数 | 字符数 | SHA-256 |
| --- | ---: | ---: | --- |
| `wrapperPlan` | 19 | 3843 | `8733d9ab399cabc563dc1e02cc7ea5cc5f584bf6fff37a856bb85dc9c089f2b6` |
| `risks` | 5 | 1216 | `3ff02c90ed95c1fe13d59041d88b3b914a7265cca50c66e440928391ed631ce8` |
| `unverified` | 9 | 839 | `b36b14d62e80687cc893629ce03bd021c8fc551d72d462c5db36fe03f6483ce2` |
| `desktopSide` | 1 | 763 | `47fdab8f1529f4c229f2464e2874f0907911521c66a00129bf8dec05b9964f40` |

## wrapperPlan：套壳方案（共 19 条）

套壳方案逐条列出，是工程实现的需求总纲。

### 第 1 条

````text
形态：单个 Activity 加一个 WebView（实例放 ViewModel，配置变更不重建）。App 只做两件事：把官方手机页装进来；补齐 WebView 缺的平台能力。配对、中转、消息全部交给页面。
````

### 第 2 条

````text
打开什么地址：加载电脑端二维码或「复制链接」给出的完整 URL，原样保存、原样加载，不拆开重建。当前契约是 https://zcode.z.ai/remote/v4?app_version=<桌面版本>&sid=<设备id>&mid=<机器码>&name=<主机名>，四个参数都可选，不能带 hash 或 t。界面上用 name 当电脑名。
````

### 第 3 条

````text
app_version 必须保留：去掉它会落到 9 月的 latest 旧页面，旧页面要求 sid+hash+t，会判参数无效。
````

### 第 4 条

````text
兜底入口：没有保存链接，或保存链接的主文档返回 404 时，加载 https://zcode.z.ai/remote/v4?app_version=<兜底版本>（不带 sid）。登录后进入「选择电脑」列表，最近用过的电脑在线会自动连。兜底版本做成常量且可配置（现为 4.0.0）。页面连上后会自己按桌面真实版本改写 app_version。
````

### 第 5 条

````text
登录全程留在同一个 WebView：shouldOverrideUrlLoading 对所有 http/https 返回 false，包括授权页和回跳 https://zcode.z.ai/remote/v4?...&code=…&state=…。不做域名白名单，因为授权域名由服务端返回并随版本变。只拦截非 http(s) 的 scheme（weixin://、intent://、market:// 等），提示改用页面内的验证码或密码方式。不要把授权页交给系统浏览器或 Custom Tab：登录记录在同源 localStorage，回调落到别的浏览器会被拒。
````

### 第 6 条

````text
WebView 必开项：javaScriptEnabled、domStorageEnabled（默认 false；不开就没有 localStorage，登录态和登录锁都失效）、setSupportMultipleWindows(true)。保持关闭或默认：allowFileAccess；不用 addJavascriptInterface 暴露通用桥；不覆写 onReceivedSslError；onPermissionRequest 保持默认拒绝；release 包不开 setWebContentsDebuggingEnabled。UA 去掉「; wv」和「Version/x.x」，按标准 Chrome Android UA 发。
````

### 第 7 条

````text
凭证保持：JWT 只存在 WebView 自己的 localStorage，App 不读、不搬、不打印。平时不清 WebView 数据和缓存（静态资源是一年 immutable 缓存）。设置里放一个「退出登录」，等于 WebStorage.deleteAllData() 加 CookieManager.removeAllCookies()，二次确认，效果等同页面的「切换账号」。登录态过期后页面会自己回登录页并保留当前链接作回跳，重新授权一次即可。
````

### 第 8 条

````text
进程被杀的恢复：onSaveInstanceState 里 webView.saveState(outState)，重建时 restoreState，保证授权途中切去短信 App 取验证码再回来还停在原页面。处理 onRenderProcessGone：销毁并重建 WebView 后重新加载。
````

### 第 9 条

````text
保存的电脑链接放 App 私有存储（DataStore），不写日志，界面上只显示主机名。清单里 android:allowBackup="false"，并用 dataExtractionRules 在 cloud-backup 和 device-transfer 两段都排除 WebView 数据所在目录、sharedpref、file、database，因为那里有账号 JWT。可选：启动时生物识别锁，默认关。
````

### 第 10 条

````text
添加电脑——扫码：App 内相机扫码（ML Kit 内置模型版或 ZXing，都不依赖 GMS；需要 CAMERA 权限）。识别后校验是 https://zcode.z.ai/remote/v4 开头；若带 hash 或 t，提示「旧版链接，请在电脑端重新打开远控弹窗扫码」。
````

### 第 11 条

````text
添加电脑——分享或点链接：注册 ACTION_SEND（text/plain）和 ACTION_VIEW（https://zcode.z.ai/remote/v4）。zcode.z.ai 没有发布 assetlinks.json（实测 404），系统不会自动唤起本 App，用户要在「打开方式」里选一次并可设默认；界面上说明这一点。
````

### 第 12 条

````text
添加电脑——粘贴：「添加电脑」页提供粘贴框和「读取剪贴板」按钮，读取前让用户确认。
````

### 第 13 条

````text
切换电脑：原生「电脑」列表（主机名加最后使用时间），点选即 loadUrl 到该链接。全程只保持一个 WebView 页面在线；同一台电脑不要同时在浏览器、微信或另一台设备上打开。
````

### 第 14 条

````text
工作区：手机端不能新建或添加工作区（页面写明只支持访问桌面端当前窗口已打开的工作区，connectRemote 在 Web 模式直接返回不支持）。要让某个工作区出现在手机，先在电脑端把它作为标签页打开在开远控的那个窗口里；切换用页面内的导航面板。一个桌面窗口只有一个远控会话，要换到别的窗口的工作区，就在那个窗口再点一次远控入口。
````

### 第 15 条

````text
必须补的原生能力（P0）。① WebChromeClient.onShowFileChooser：用 fileChooserParams.createIntent() 配 ActivityResultLauncher，支持多选，取消时回调 null（附件和反馈截图都靠它，不实现则按钮无反应）。② onCreateWindow：建临时 WebView 取到 URL，用系统浏览器或 Custom Tab 打开后释放；isUserGesture 为 false 时返回 false（页面用 window.open 打外链）。③ 返回键：用 evaluateJavascript 读 document.querySelector('[data-mobile-page]')。是 chat 就 goBack()；是 home 或读不到就 moveTaskToBack(true)，不要再 goBack。属性读不到时退回「canGoBack 就 goBack，否则回桌面」。④ windowSoftInputMode=adjustResize，并在 edge-to-edge 下给 WebView 加系统栏 insets padding（页面没有 viewport-fit=cover）。⑤ 原生网络错误页和重试按钮（onReceivedError 时显示）；主文档 404 按兜底入口处理。
````

### 第 16 条

````text
建议补的原生能力（P1）：文件「保存副本」。页面用 blob URL 加 a download，WebView 不能直接保存。做法：WebViewCompat.addDocumentStartJavaScript 配 addWebMessageListener，allowedOriginRules 只写 https://zcode.z.ai；脚本包装 URL.createObjectURL 记住 Blob，拦截带 download 的 a 的点击，用 ArrayBuffer 分块 postMessage 给原生，原生用 ActivityResultContracts.CreateDocument 保存；任何一步不支持就退化为提示「暂不支持保存」。沿用页面 50MB 上限。另可在菜单里加「重新加载」和「复制当前链接」（带确认）。
````

### 第 17 条

````text
可选（P2）：全屏图片或视频预览（onShowCustomView 与 onHideCustomView 成对实现）；主题跟随 App 深浅色（页面用 localStorage 的 zcode-theme）。
````

### 第 18 条

````text
不要做：① 不自己实现 token、WebSocket 或中转协议（未公开，随版本变）。② 不用系统浏览器或 Custom Tab 走授权。③ 不做通知：WebView 没有 Notification API，页面从不申请权限且切后台就断连；厂商的「手机推送通知」面向官方 App。需要提醒时用电脑端自带的「使用 Bot Channel」（微信、飞书、Lark、Telegram）。④ 不用前台服务或唤醒锁保活，页面自己在后台挂起连接，保活没有意义。⑤ 同一台电脑不开两个页面。⑥ 不改写、不补全链接参数，不把 app_version 写死成唯一入口。⑦ 不对页面做大段 JS 注入或改 DOM；所有原生钩子都做成「页面不配合就静默降级」。
````

### 第 19 条

````text
维护：每次 ZCode 桌面升级后跑一遍冒烟检查——`curl -sI 'https://zcode.z.ai/remote/v4?app_version=<新版本>'` 是否 200；页面里 data-mobile-page、input[type=file]、saveFile 适配层是否还在；登录能否走通。
````

## risks：风险与应对（共 5 条）

每条先说风险和成因，再说应对。

### 第 1 条

````text
登录在 WebView 里走不通，或授权回跳丢失。可能原因：App 自己把授权域名拦去外部浏览器；授权页拒绝 WebView UA 或依赖第三方 Cookie；登录途中进程被杀。应对：http/https 导航一律留在 WebView；UA 用标准 Chrome Android 串；用 saveState/restoreState 保存 WebView 状态；若授权页因 Cookie 失败，只对这个 WebView 开 setAcceptThirdPartyCookies。最后手段是改在系统浏览器完成授权，再把回跳链接手动交回 App（登录记录在 localStorage，同一个 WebView 仍能完成）；这条路未验证，只作排障入口，不作默认路径。
````

### 第 2 条

````text
桌面升级后链接或兜底失效（页面和登录流程随版本变）。事实：本机 autoDownloadAndInstallUpdates=True；latest 目录停在 9 月；4.0.1、4.1.0 目录 404；3.17.1 到 4.0.0 之间登录界面和流程都换过。应对：链接原样保存并带 app_version；页面连上后会自己改写到桌面真实版本；兜底版本可配置并在 404 时自动切换；App 不实现协议，JS 注入只限 zcode.z.ai 且可静默降级；每次桌面升级后跑冒烟检查。sid 在同账号同端点下不变，换账号或端点才变，页面能按 mid 自动接续新 sid。
````

### 第 3 条

````text
电脑休眠、断网或关窗口导致「连不上」。本机是会休眠的笔记本，防休眠和托盘都关着，出网依赖本地代理。表现：手机端 15 到 30 秒内显示桌面已断开；电脑恢复后自动重连。应对：打开「保持电脑运行」和「关闭窗口时隐藏到托盘」；不合盖；重启后手动启动 ZCode。App 的原生错误页写明「请确认电脑端 ZCode 正在运行且已登录」并给重试按钮，不要把它表现成登录问题。
````

### 第 4 条

````text
被踢下线。同一个 sid 同一时间只允许一个手机页面，新页面会踢掉旧的（KICKED 后 session-conflict）。常见触发：同一链接又在浏览器、微信或平板里打开；App 里开了两个 WebView；两边互相抢会造成反复断连。应对：App 只保留一个 WebView；「在浏览器中打开」入口带提示；收到 kicked 失败时原生提示「同一台电脑同一时间只能一个页面，请关掉别处的页面后重试」。
````

### 第 5 条

````text
凭证或链接泄露、登录态丢失。链接加同账号登录就能控制这台电脑；WebView 的 localStorage 里是账号 JWT。应对：链接和 WebView 数据都留在 App 私有存储；关闭云备份与设备迁移；链接不进日志、不在界面显示（只显示主机名）；可选生物识别锁；不用 addJavascriptInterface。登录态丢失（清数据、卸载重装、JWT 过期）只需重新授权一次。手机丢失时，在电脑端点「停止」即可让旧链接失效。
````

## unverified：复核时没能验证的事项（共 9 条）

这些事在复核时只是读代码或查文档得出的，没有实测。

### 第 1 条

````text
授权页的实际域名和它在 WebView 里的表现。authorize_url 由 POST /api/v1/oauth/cli/init 返回，我没有调用（会在服务端创建登录流程，不是只读操作）。授权页会不会拒绝 WebView 的 UA、是否依赖第三方 Cookie、是否靠拉起微信等第三方 App 登录，都未知。
````

### 第 2 条

````text
没有任何真机或模拟器验证。本机 android_list_avds 返回 Missing Android tool: emulator，按规则也不安装。所以 WebView 里的登录回跳、文件选择、window.open 转外部浏览器、blob 下载转存、返回键、软键盘与刘海适配，都是按代码和文档推出来的，没有跑过。
````

### 第 3 条

````text
JWT 的实际有效期由服务端决定，客户端代码里看不到；服务端会不会清理长期离线设备的 sid 也没查到。桌面端登录态过期后能否自动续期没有追（手机端确定没有续期）。
````

### 第 4 条

````text
「打开『关闭窗口时隐藏到托盘』后关窗口远控不停」是读代码的推断（走 hideWindow 而不是 close），我没有改设置实测。
````

### 第 5 条

````text
当前 4.0.0 实例的远控此刻是否已就绪，只能从日志推断（截至 2026-11-24 00:25 没有 stopped 记录）；按规则我没有打开弹窗确认。日志里 23:39:16 那次 reason=restart 的触发原因没有追。
````

### 第 6 条

````text
WebView 数据目录 app_webview 是否被 Auto Backup 默认包含，官方文档没有点名；所以方案里直接建议整体关闭备份。
````

### 第 7 条

````text
旧版本目录会保留多久不知道（3.17.2、3.18.2、3.19.1、4.0.0 现在在，3.4.x、3.5.0、4.0.1、4.1.0 是 404）；用兜底版本的页面去连更新或更旧的桌面，协议是否兼容没有验证。
````

### 第 8 条

````text
手机页面要求的最低系统 WebView 版本没有查。
````

### 第 9 条

````text
兜底登录手段（系统浏览器完成授权后把回跳链接手动交回 App）只是按代码推断可行，没有验证。
````

## desktopSide：电脑端要做什么

````text
不需要任何额外常驻服务或自研程序。桌面 ZCode 自己就是常驻端：打开一次远控弹窗就开启，关掉弹窗不停，退出重启后自动恢复（代码加本机日志 5 次实证），只有手动点「停止」才清除。所以不存在「让远程控制开机自动开启」的额外步骤。手机要随时连得上，只需保证桌面端的运行条件，全部是 ZCode 自带设置或使用习惯：
1. ZCode 保持运行并登录，窗口不能关。窗口 closed 会停掉该窗口的远控并杀 host（index.js:1107）。本机 Linux 上「关闭窗口时隐藏到托盘」是关的（closeToTrayOnWindows=False），关最后一个窗口就是退出应用。建议在设置里打开它；本机托盘可用（StatusNotifierWatcher 有 owner，装了 ubuntu-appindicators）。最小化窗口不影响。
2. 防休眠：本机是会休眠的笔记本，且「保持电脑运行」是关的（keepAwakeWhileRunning=False）。建议打开，它只防空闲休眠，合盖仍会睡。要真正随时可连，就得不合盖、不断电、不断网。
3. 重启电脑后要手动启动 ZCode（桌面端没有开机自启功能），启动后远控自动恢复。想省这一步可以自建用户级 autostart 项，属于系统配置，非必需，我没有做。
4. 网络要通：本机经本地代理出网（127.0.0.1:7897），代理客户端停了，电脑端到中转的连接就断。
5. 不要点弹窗里的「停止」；退出登录会停，重新登录同一账号自动恢复。只运行一个版本的 ZCode。
6. 要访问的工作区要在开远控的那个窗口里打开。
电脑休眠、断网或关窗口期间，手机端 15 到 30 秒内显示桌面已断开；电脑恢复后自动重连，手机回到前台会自动接上，不需要在电脑上补做任何操作。
````

## 存档说明

- `risks` 和 `unverified` 的条目标题也是「第 N 条」，与原字段顺序一致。
- 本页不随工程演进而更新。实现上对原文作出的细化或取舍（例如返回键约定的细化），记录在
  [ARCHITECTURE.md](ARCHITECTURE.md) 里，并且在那里注明对应的是第几条。
