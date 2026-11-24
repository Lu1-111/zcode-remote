# ZCode 遥控：架构与工程约定

> **读者**：接手 M2、M3、M4 的工程师，以及写使用说明、做审查的人。
>
> - 需求原文在 [WRAPPER-PLAN.md](WRAPPER-PLAN.md)。本文件说的「方案第 N 条」，指的就是那里 `wrapperPlan` 的第 N 条。
> - 背景事实、证据和未验证事项在 [remote-control-notes.md](remote-control-notes.md)。
> - 本文件只讲工程怎样落地：结构、命令、红线、里程碑验收清单。改了这里写的约定，要同步改本文件，并在返回说明里写明改了哪一条、为什么。

## 1. 这个 App 是什么

一个原生安卓 App，把 ZCode 自带的「移动端远程控制」手机页装进一个 WebView，并补上网页在 WebView 里缺的原生能力。配对、中转、消息全部交给页面，App 不实现任何协议（方案第 1、18 条）。

M2 做完之后的样子：`MainActivity` 是全应用唯一的 中转站，自己不管 WebView，只做分发和接线；WebView 内核在 `web/` 里，入口是 `RemoteWebViewModel`。M3（电脑管理与扫码添加）也已做完：电脑列表、添加（扫码/粘贴/系统分享）在 `devices/` 里，启动时只有一台电脑就直接进它的页面，否则先进列表；打开 App 不再是「直接就是网页」。M4（保存副本）也已做完：页面脚本与原生之间的保存通道在 `save/` 里，见第 9.3 节。

## 2. 目录与模块

### 2.1 现在的样子

```
zcode-remote/
├── README.md                          使用说明（现为维护说明：兜底版本怎么换）
├── .gitignore
├── settings.gradle.kts                单模块工程；依赖仓库只在这里声明
├── build.gradle.kts                   把 Kotlin 钉在 2.4.21
├── gradle.properties                  Gradle 的 JVM 内存等；不写代理
├── gradle/libs.versions.toml          版本目录，依赖和版本都加在这里
├── gradlew · gradlew.bat · gradle/wrapper/    Gradle 9.9.2，wrapper 带哈希校验
├── app/
│   ├── build.gradle.kts               applicationId、SDK 版本、签名、lint、依赖
│   ├── proguard-rules.pro             规则留位（release 目前不混淆）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml            权限、备份与明文开关、返回键的派发方式、唯一的 Activity
│       │   ├── kotlin/com/lululu/zcoderemote/
│       │   │   ├── MainActivity.kt           全应用唯一的 Activity：只做分发和接线
│       │   │   ├── SmokeLog.kt               冒烟日志，格式钉死
│       │   │   ├── ui/AppTheme.kt            M3：固定深色的主题（与页面的深色风格一致）
│       │   │   ├── web/                      M2：WebView 内核，文件见 2.2
│       │   │   ├── devices/                  M3：电脑管理与扫码添加，文件见 2.2
│       │   │   └── save/                     M4：保存副本，文件见 2.2
│       │   └── res/                           文案、颜色、启动图标、备份规则
│       ├── debug/AndroidManifest.xml          只进 debug 包的清单合并规则
│       └── test/kotlin/com/lululu/zcoderemote/   单元测试（JUnit，纯 JVM）：SmokeLogTest，web/ 下每块纯逻辑一份
├── scripts/
│   ├── toolchain.env                  本机 JDK 与 SDK 的位置
│   ├── common.sh                      其余脚本共用的函数
│   ├── build.sh                       构建并复制到 dist/
│   ├── check.sh                       单元测试与 Lint
│   ├── verify-apk.sh · apk-audit.py   安装包静态验收
│   ├── make-keystore.sh               生成 release 签名密钥
│   ├── serve-apk.sh                   起下载地址，把安装包传到手机
│   └── emulator-boot.sh · emulator-kill.sh · emulator-smoke.sh
├── docs/
│   ├── WRAPPER-PLAN.md                需求原文存档
│   ├── remote-control-notes.md        技术笔记
│   └── ARCHITECTURE.md                本文件
├── out/plan.md                        M2 验收记录；本机 video2code 插件的钩子要求它在，见文末「关于 video2code 插件钩子」一节
├── dist/                              构建产物（不入库）
├── keystore/ · keystore.properties    签名密钥与口令（不入库）
└── artifacts/emulator/                模拟器实跑的截图与日志
```

`emulator-boot.sh`、`emulator-kill.sh`、`emulator-smoke.sh` 和 `artifacts/emulator/` 不属于骨架，是工作流里「准备安卓模拟器」那一步写的，用来在交付前把安装包真正启动一遍，用法见各自的文件头。其中 `emulator-smoke.sh <apk路径>` 会到 logcat 里找第 8 节约定的那一行 `pageLoaded`。

### 2.2 约定的包结构（M2 到 M4 按此落位）

```
com.lululu.zcoderemote
├── MainActivity.kt            唯一的 Activity，只负责分发：WebView、电脑列表（M3）、设置（M3）
├── SmokeLog.kt                冒烟日志
├── web/                       M2：WebView 内核
│   ├── RemoteWebViewModel.kt  内核本体：持有唯一的 WebView 实例、「当前电脑链接」、兜底版本；界面层只跟它打交道
│   ├── RemoteWebView.kt       Compose 外壳：挂载 WebView、留出系统栏和软键盘的位置、盖断网页、接返回键
│   ├── RemoteWebViewClient.kt 导航策略、断网页、404 转兜底、pageLoaded 日志
│   ├── RemoteWebChromeClient.kt 文件选择、新窗口（含接住外链的临时 WebView）
│   ├── ErrorPageState.kt      纯逻辑：断网页的状态（WebView 什么时候才能露出来，第 6.1 节）
│   ├── LoadAttemptTracker.kt  纯逻辑：一次主框架导航算不算加载完成（第 8 节）
│   ├── BackDecision.kt        纯逻辑：返回键决策（第 7 节）
│   ├── EntrySelector.kt       纯逻辑：入口选择（第 9.1 节）
│   ├── NavigationPolicy.kt    纯逻辑：地址留在 WebView 里还是拦下（方案第 5 条）
│   ├── UserAgent.kt           纯逻辑：UA 去掉两处 WebView 标记（方案第 6 条）
│   └── InputProbe.kt          软键盘弹出后把输入框滚进可见区域的那段只读脚本
├── devices/                   M3：电脑管理与扫码添加
│   ├── ComputerListScreen.kt  电脑列表 + 引导页（三种添加方式、免扫码入口）+「更多」菜单（M3 起设置项都放在这里）
│   ├── AddComputerScreen.kt   粘贴添加：输入框 +「读取剪贴板」（先确认）
│   ├── ScanScreen.kt          扫码添加：内嵌 zxing 的扫码视图（不用它自带的 CaptureActivity，仍然只有一个 Activity）
│   ├── RemoteLink.kt          纯逻辑：链接校验与归并、从分享文本里找链接（第 10 节）
│   ├── StartupSelector.kt     纯逻辑：冷启动去哪一屏（只有一台电脑直接进）
│   ├── ComputerCodec.kt       纯逻辑：电脑列表在 DataStore 里的打包/解包
│   └── ComputerStore.kt       电脑列表的存储（Preferences DataStore）
├── ui/
│   └── AppTheme.kt            固定深色的 Material 主题
└── save/                      M4：文件保存（方案第 16 条）
    ├── SaveCopyBridge.kt      页面脚本与原生之间的保存通道：装通道、收消息、写盘
    ├── SaveCopyScript.kt      注入页面的文档起始脚本和通道常量（JS 对象名、允许来源）
    ├── SaveProtocol.kt        纯逻辑：50MB 上限、分块计划、文件名清洗
    └── SaveTransferMachine.kt 纯逻辑：一次保存传输的状态机
```

文件名可以按实际调整，下面两条不要变：

- 纯逻辑（`BackDecision`、`EntrySelector`、`RemoteLink` 等）不引用任何 `android.*` 的类，这样能在电脑上直接跑单元测试。
- 需要的依赖加进 `gradle/libs.versions.toml`，版本必须是实际能解析到的；模块的 `build.gradle.kts` 里不写 `repositories`。

**M3 接进内核的几个口子**（M2 留好、M3 实际用到的，都在 `RemoteWebViewModel` 的公开方法里）：

| M3 要做的事 | 用哪个 |
| --- | --- |
| 把一条电脑链接交给内核 | `openLink(link)`：原样加载；「它就是当前入口」时才不重复加载（从兜底入口换回同一台电脑会重新加载） |
| 当前是哪条电脑链接 | `currentLink`（`StateFlow`），只用来决定加载什么和标记「使用中」，不要显示到界面上 |
| 列表页选中一台电脑之后 | 先 `openLink`，原生界面盖在 WebView 之上（`MainActivity.AppRoot` 里 WebView 那层始终在底下）；不要另建 WebView |
| 返回键要回电脑列表 | `Host.returnToComputerList()`：M3 起由 `MainActivity` 实现，切回列表那一屏 |
| 打开免扫码入口 | `openFallbackEntry()`（引导页按钮）或 `openEntryLink(link)`（「打开链接」「分享」带进来的不带 sid 的链接）：原样加载，但**不计入**「当前电脑链接」 |
| 进程被杀后恢复 | `restoreFromState(state)` 只恢复 WebView 的状态，**不再自动加载兜底入口**（打开什么由界面层决定）；返回 false（没有状态、或恢复不了）时界面层照第一次打开处理；当时在用的电脑链接用 `adoptRestoredLink(link)` 补登记，不重新加载 |
| 「重新加载」菜单项 | `retry()` |
| 「在浏览器中打开」 | `openCurrentPageInBrowser()`：当前页面交给系统浏览器，没有已打开的页面返回 false |
| 「复制当前链接」 | `currentComputerLink()`：当前电脑链接的原样文本，只在用户确认过的复制里用 |
| 原生界面盖在 WebView 上时的返回键 | `RemoteWebView(…, backHandlingEnabled = false)`：盖着的时候返回键归原生界面 |

## 3. 全局不变量（任何里程碑都不得破坏）

1. **只有一个 Activity、一个 WebView。** WebView 实例由 ViewModel 持有，界面重建不重建 WebView（方案第 1 条）。同一个 sid 同一时间只允许一个手机页面，多一个页面就会互相踢下线（方案 risks 第 4 条）。转屏、深浅色、字号、分屏这几种变化连界面都不重建（清单里的 `configChanges`）：界面一重建，WebView 就得从窗口上摘下来再挂回去，页面会先被当成「隐藏」再「显示」，手机页一隐藏就挂起连接。
2. **http 和 https 一律留在 WebView 里**，包括登录授权页和回跳；只拦非 http(s) 的 scheme（方案第 5 条）。
3. **链接原样保存、原样加载。** 不拆开重建，不补全，不改写，尤其是 `app_version`（方案第 2、3、18 条）。
4. **登录态只存在于 WebView 自己的 localStorage。** App 不读、不搬、不打印（方案第 7 条）。
5. **所有原生钩子都是「页面不配合就静默降级」。** 不做大段 JS 注入，不改页面 DOM（方案第 18 条）。
6. **日志和界面上不出现链接和凭证**（第 6、8 节）。WebView 自己那张错误页会把整条链接写在屏幕上，所以主框架加载失败之后 WebView 一直藏着，直到之后某一次主框架真正加载成功（第 6.1 节）。

`MainActivity` 在清单里声明为 `android:launchMode="singleTask"`，就是为了第 1 条：M3 注册「打开链接」「分享」之后，从别的 App 再次唤起时走 `onNewIntent`，不会再叠出第二个 Activity 去创建第二个 WebView。

## 4. 构建与验收命令

都可以在任意目录下运行，脚本自己找到工程根目录。下表按工作区的根目录写。

| 命令 | 做什么 | 通过的标准 |
| --- | --- | --- |
| `bash zcode-remote/scripts/check.sh` | `gradlew testDebugUnitTest lintDebug` | 退出码 0 |
| `bash zcode-remote/scripts/build.sh debug` | 构建并复制为 `dist/zcode-remote-debug.apk` | 退出码 0 |
| `bash zcode-remote/scripts/build.sh release` | 没有密钥先生成；构建（含 `lintVitalRelease`）并复制为 `dist/zcode-remote-release.apk` | 退出码 0 |
| `bash zcode-remote/scripts/verify-apk.sh zcode-remote/dist/zcode-remote-release.apk` | 安装包静态验收，逐项打印通过或不通过 | 退出码 0，9 项全过 |
| `bash zcode-remote/scripts/serve-apk.sh` | 起一个 HTTP 服务，打印手机可用的下载地址 | 一直运行，Ctrl-C 停止 |
| `bash zcode-remote/scripts/make-keystore.sh` | 生成或沿用 release 密钥 | 退出码 0 |

**退出码**

- `build.sh`、`verify-apk.sh`：0 成功；1 失败；2 用法不对。
- `check.sh`：就是 `gradlew` 的退出码。Lint 有 error 时 `lintDebug` 失败；warning 不拦，但要看报告。
- `verify-apk.sh`：1 也用于「安装包或清单读不了」——读不了的包不算是通过的包。

**前提**

- JDK 17、Android SDK（`platforms/android-37.1`、`build-tools/36.0.0`），位置记在 `scripts/toolchain.env`，换机器只改它。
- `python3`。`verify-apk.sh` 和 `serve-apk.sh` 用到。
- 首次解析依赖需要代理。代理已经写在用户级的 `~/.gradle/gradle.properties`，工程里不要重复写。

**可以用环境变量改的行为**

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `ZCODE_REMOTE_KEEP_DAEMON=1` | 不留 | 让 Gradle daemon 留在后台，加快重复构建。这台机器内存紧，默认不留 |
| `ZCODE_REMOTE_BUILD_TOOLS=<版本>` | 版本最高的 | 让 `verify-apk.sh` 用指定的 build-tools |
| `PORT=<端口>` | 8765 | `serve-apk.sh` 的端口 |
| `BIND=<地址>` | 0.0.0.0 | `serve-apk.sh` 只监听这个地址 |

**`verify-apk.sh` 查的 9 项**（规则在 `scripts/apk-audit.py`）

| 检查项 | 通过的条件 |
| --- | --- |
| 包名 | `com.lululu.zcoderemote` |
| 不是可调试包 | 清单里不是 debuggable |
| allowBackup 为 false | `android:allowBackup="false"` |
| 权限只在白名单内 | 见第 6.4 节 |
| 没有允许明文流量 | `usesCleartextTraffic` 不是 true；网络安全配置里没有任何一处 `cleartextTrafficPermitted="true"` |
| 除主 Activity 外没有导出组件 | 导出的组件只有 `com.lululu.zcoderemote.MainActivity` |
| 主 Activity 的 intent-filter 与方案一致 | 恰好三个过滤器：MAIN/LAUNCHER；VIEW 的 data 恰为 `scheme=https`、`host=zcode.z.ai`、`pathPrefix=/remote/v4`（不许多收 host、去掉 pathPrefix 之类）；SEND 的 data 恰为 `mimeType=text/plain`（方案第 11 条。丢失或放宽都判不通过） |
| 备份规则排除全部存储域 | `dataExtractionRules` 指向的资源里，`cloud-backup` 和 `device-transfer` 两段都把 root/file/database/sharedpref 整域 `<exclude path=".">`；两段里只允许 exclude（见第 6.6 节） |
| 安装包已签名 | `apksigner verify` 通过，并打印证书的 SHA-256 |

最后一项是任务清单之外加的：没签名的 release 包装不上手机，验收不应该放过它。每一项如果读不出来或判断不了，都算不通过。

**要知道的四件事**

- 构建输出里会有一行 `Unable to strip the following libraries … libandroidx.graphics.path.so`，原因是本机没装 NDK，不影响使用。
- 工程路径里有中文。JDK 17 在 `LC_ALL=C` 下读不了这样的路径（`apksigner` 实测报找不到文件），所以 `scripts/common.sh` 和 `apk-audit.py` 都把 locale 钉成 `C.UTF-8`。想直接跑 `./gradlew`，先 `source zcode-remote/scripts/toolchain.env`，并且不要在 `LANG=C` 的环境里跑。
- 改过资源目录的名字（比如增删 `-v26` 这样的限定符）之后，如果 AAPT 报某个资源找不到，是增量构建留下的旧状态，不是资源真的没了。搭骨架时把图标从 `mipmap-anydpi-v26/` 并到 `mipmap-anydpi/`，之后第一次构建就是这样失败的，同样的目录布局在干净的目录里构建正常。清理一下再构建即可：

  ```bash
  ( source zcode-remote/scripts/toolchain.env; export LC_ALL=C.UTF-8; cd zcode-remote && ./gradlew clean --no-daemon )
  ```
- `emulator-kill.sh` 关模拟器，对模拟器里的系统来说不是正常关机：最后写进 `/data`、还没落盘的东西会丢。M2 实测：装完包立刻关，再开机包就不在了（`emulator-smoke.sh` 跑完的包也是这样，不影响它自己的结论，因为它在一次开机里装好就测）；装完先 `adb shell sync`、等十几秒再关，再开机就还在。要在模拟器上验证「数据能不能跨重启保留」（M3 的 DataStore），关之前先这样做。

## 5. 发布签名

| 东西 | 位置 | 说明 |
| --- | --- | --- |
| 密钥 | `keystore/zcode-remote-release.p12` | PKCS12，RSA 4096，别名 `zcode-remote`，有效期 10950 天 |
| 口令 | `keystore.properties` | 四个键：`storeFile`、`storePassword`、`keyAlias`、`keyPassword` |

- 两个文件都不入库（`.gitignore`），权限都是 600，由 `scripts/make-keystore.sh` 生成。口令是 24 字节随机数的十六进制，共 48 个字符，经环境变量交给 `keytool`，不出现在命令行里。
- 脚本只在两个文件都不存在时才生成。已经有的就沿用，什么都不做。只存在其一时报错退出，绝不悄悄换钥匙：钥匙一换，手机上已装的包就无法覆盖升级，只能卸载重装，App 里的数据全丢。确实要重新开始，先手动把这两个文件移走。
- `storeFile` 写的是相对工程根目录的路径，也可以写绝对路径。
- `app/build.gradle.kts` 只在 `keystore.properties` 四个键齐全、密钥文件也在时才配置签名；否则 AGP 打出的是 `app-release-unsigned.apk`，`build.sh` 发现后直接报错退出。
- debug 包用的是 Android SDK 的调试密钥，只能自己装着试，不要分发。

**怎样确认安装包是哪把钥匙签的**：`verify-apk.sh` 最后会打印签名证书的 SHA-256，和下面这条命令的输出对得上就是同一把钥匙（它会提示输入口令，就是 `keystore.properties` 里 `storePassword` 的值）：

```bash
keytool -list -v -keystore zcode-remote/keystore/zcode-remote-release.p12 -alias zcode-remote
```

骨架第一次生成的证书 SHA-256 是 `1b976fce6ddc89fd891bcbd0bfd3553bc0f760bce2eb1ef2c6ace6789a9c62e2`（2026-11-24 记录；以后换了钥匙，以 `verify-apk.sh` 的输出为准）。

**发新版本前**：把 `app/build.gradle.kts` 里的 `versionCode` 加一（M4 发的包是 3，`versionName` 是 `0.3.0`）。系统只允许安装 `versionCode` 更大的包来覆盖旧版本。

**release 安装包不是逐字节可重现的。** 实测同样的源码、同样清理后连续构建两次：release 包大小相同，SHA-256 不同；debug 包那两次相同。所以不要把某个哈希写死在文档里，要核对下载是否完整，以当次 `build.sh` 打印的哈希为准。

release 关闭了混淆和收缩（`isMinifyEnabled = false`），并且不可调试。将来要打开混淆，规则写在 `app/proguard-rules.pro`，文件头列了必须先补的两类规则。

## 6. 安全红线

### 6.1 链接和登录凭证当作密码

链接加上同账号登录就能控制这台电脑，WebView 的 localStorage 里是账号的登录凭证。

- 不写日志，不打印，不在界面显示完整链接；界面上只显示电脑名。
- 电脑链接放 App 私有存储（DataStore），不写日志。
- 清 WebView 数据和缓存只放在「退出登录」里，并且要二次确认。
- **WebView 自己那张错误页从不露出来。** 主框架加载失败时 WebView 会画一张「Webpage not available」，把整条链接连同查询串写在上面。所以加载失败之后要把 WebView 藏起来（`INVISIBLE`），并且一直藏到之后某一次主框架真正加载成功；按「重试」、换一条链接都不能提前放出来。这条规矩在 `ErrorPageState` 里，有单元测试，放出来的代码全应用只有一处（`RemoteWebViewModel.onPageLoadSucceeded`）。M2 实测过：不这样做的话，按了「重试」之后到新页面出来之前，屏幕上就是那张错误页。

### 6.2 不暴露通用 JS 桥

- 不用 `addJavascriptInterface`。
- 注入页面的脚本只在确有需要时写（目前只有 M4 的「保存副本」），用 `WebViewCompat.addDocumentStartJavaScript` 配 `addWebMessageListener`，`allowedOriginRules` 只写 `https://zcode.z.ai`。
- 任何一步页面不配合，就静默降级，不报错打扰用户。

### 6.3 不绕过证书校验

- 不覆写 `onReceivedSslError`。真要覆写，只能一律 `cancel()`。
- Lint 里和证书校验相关的检查保持 error 级别，不许用 `@SuppressLint` 或 `tools:ignore` 压掉。
- 代价要知道：证书有问题时 WebView 默认取消加载，主框架的 `onReceivedError` 不会来（模拟器上实测，WebView 停在空白页，也没有记 pageLoaded），App 的断网页出不来，屏幕是一片空白；链接不会露出来，按返回可以离开。因为约定不覆写 `onReceivedSslError`，M2 没有补这个提示。

### 6.4 权限白名单

`verify-apk.sh` 只放行：

- `android.permission.INTERNET`（M2 已经加上，WebView 联网要用）；
- `android.permission.CAMERA`（M3 已加上：扫码用，进扫码页时才在运行时申请；同时清单里声明了
  `uses-feature android.hardware.camera required=false`，没有后置摄像头的设备也能装，扫码页会引导改用粘贴）；
- 唯一的例外：`com.lululu.zcoderemote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。

**这条例外的来由。** 它不是我们申请的，是 AndroidX（`androidx.core`）打包时自动加入清单的一条应用私有权限：名字在本包名下，由本 App 自己声明为签名级（`protectionLevel=0x2`），只有和本 App 用同一张证书签名的程序才可能持有，也不对应任何系统资源。它是 AndroidX 用来保护「不导出的动态接收器」的加固措施，不是对外申请的能力，所以放行它是准确的，不算放宽。只要用 Compose 就去不掉。

- 放行要两个条件同时满足：名称精确相等；清单里由本 App 声明、保护级别恰为签名级。差一样就判不通过。
- 不要用 `tools:node="remove"` 把它去掉，那会削弱 Android 13 以下 `RECEIVER_NOT_EXPORTED` 的保护。
- 以后再出现别的 AndroidX 自动加入的私有权限，`verify-apk.sh` 会判不通过并指名是哪一条。要不要加例外由人决定：决定加，再改 `scripts/apk-audit.py` 和本节，并写明来由。

要给白名单加新的系统权限，同样改 `scripts/apk-audit.py` 里的 `ALLOWED_PERMISSIONS` 和本节，并在返回说明里写明理由。

### 6.5 除主 Activity 外没有导出组件

AndroidX 的库清单会自动带进导出组件，骨架已经去掉了两个，release 包和 debug 包现在都只剩主 Activity 一个导出组件。

| 组件 | 来源 | 为什么去掉 | 代价 | 怎样恢复 |
| --- | --- | --- | --- | --- |
| `androidx.profileinstaller.ProfileInstallReceiver` | `androidx.profileinstaller`，所有包都有 | 让验收的「导出组件只有主 Activity」成立。它原本受 `android.permission.DUMP` 保护，其实并不算暴露面 | 基线配置文件不会被安装，冷启动会略慢一点。对一个以 WebView 为主的 App 无关紧要 | 删掉 `app/src/main/AndroidManifest.xml` 里那个 `<receiver … tools:node="remove">` |
| `androidx.compose.ui.tooling.PreviewActivity` | `androidx.compose.ui:ui-tooling`，只在 debug 包里 | 同上。Compose 预览功能靠这个库，所以库留着，只去掉它带进来的 Activity | Android Studio 预览面板里「把预览跑到设备上」这类功能预计不可用（我没有在 Android Studio 里验证过具体影响） | 删掉 `app/src/debug/AndroidManifest.xml` |

以后再冒出别的导出组件，`verify-apk.sh` 会指名。处理办法是照上面的样子用 `tools:node="remove"` 去掉，或者在清单里把它设成不导出；不要去放宽验收脚本。

### 6.6 不备份、不迁移、不走明文

清单里 `android:allowBackup="false"`、`android:dataExtractionRules="@xml/data_extraction_rules"`、`android:usesCleartextTraffic="false"`，其中备份规则在 `app/src/main/res/xml/data_extraction_rules.xml`，云备份和设备迁移两段都把全部存储域排除了。

`allowBackup="false"` 在部分厂商设备上拦不住设备到设备迁移，所以两段都写了。这三项都在 `verify-apk.sh` 的检查里，其中备份规则不止查清单上有没有引用 `dataExtractionRules`，还会展开资源核对：两段里 root/file/database/sharedpref 都整域排除、且只允许 exclude（第 4 节的表）。清单上为了消除一条误报写了 `tools:ignore="DataExtractionRules"`：Lint 建议再配一份 `fullBackupContent`，但 Android 12 以下 `allowBackup="false"` 已经关掉了全部备份，不需要。

### 6.7 日志

- 严禁出现在日志里：完整或部分的链接、`sid`、`mid`、登录凭证、一次性 token、授权码。
- release 包不调用 `setWebContentsDebuggingEnabled(true)`。M2 的做法是 `if (BuildConfig.DEBUG) …`，release 包的两个 dex 里连这个方法名都没有（`strings | grep` 为 0）。
- 冒烟日志的格式见第 8 节。发日志统一走 `SmokeLog`，不要在别处手拼。
- WebView 自己有两种行为跟系统构建有关，查日志、看调试口时先想到它：
  - 调试口（`webview_devtools_remote_<进程号>`）：WebView 124 在 `eng`/`userdebug` 构建的系统上，或者 App 是 debuggable 的时候，对所有 App 强制打开，而且关不掉（Chromium 源码 `WebViewChromiumAwInit`，按 `BuildInfo.isDebugAndroidOrApp()`）。所以 **release 包装到这台模拟器上（userdebug）也能用 chrome://inspect 连上，这不是 release 包的问题**；release 包在正式系统（user 构建）上的表现 M2 没法在这台模拟器上验证。
  - 页面的 `console` 输出：同样这两种情况下，WebView 会把页面自己的 console 输出写进 logcat。debug 包会，release 包在正式系统上不会。所以别拿 debug 包去看真实链接的日志。

### 6.8 验收脚本不放宽

`verify-apk.sh` 说一不二：任何一项不符就是非零退出。它拦住了，说明包不合要求，先修包。确实要改规则，按 6.4、6.5 的办法改，并写明理由。

### 6.9 给写使用说明的人

权限这一点对用户只需说明：**申请的权限只有网络和相机；另有一条 AndroidX 自动加入的应用内部签名级权限，不涉及任何系统资源。**

## 7. 返回键的整体约定

M2 与 M3 共同遵守。页面自己管历史记录：进聊天页时 `pushState({zcodeMobilePage:"chat"})`，页内的返回按钮用 `replaceState` 而不是 `history.back()`；DOM 上用 `<section data-mobile-page="home|chat">` 标明当前页。

| 用户在哪里 | 按返回键做什么 |
| --- | --- |
| 原生的电脑列表（M3 起有；添加页、扫码页返回也是回列表） | 回到桌面：`moveTaskToBack(true)` |
| 页面在聊天页（`data-mobile-page="chat"`），并且 WebView 能后退 | 网页后退：`webView.goBack()` |
| 页面在自己的首页（`data-mobile-page="home"`） | 回到原生的电脑列表（M3 已实现：列表盖在原来的 WebView 之上，不另建 WebView） |
| 聊天页但 WebView 已经不能后退 | 同首页：回电脑列表 |
| 页面里没有 `data-mobile-page`（登录页、授权页、设备列表、错误页等） | 同首页：回电脑列表，**不调用 `goBack()`** |
| 读属性的那段脚本没有返回结果 | 退回普通浏览器的做法：能后退就 `goBack()`，否则回桌面 |

把这张表写成一个不依赖 Android 的纯函数，M2 实现并配单元测试：

```kotlin
enum class MobilePage { CHAT, HOME, ABSENT, UNKNOWN }   // UNKNOWN：脚本没有返回结果
enum class BackTarget { WEB_BACK, COMPUTER_LIST, DESKTOP }

fun decideBack(page: MobilePage, canGoBack: Boolean): BackTarget = when (page) {
    CHAT -> if (canGoBack) WEB_BACK else COMPUTER_LIST
    HOME, ABSENT -> COMPUTER_LIST
    UNKNOWN -> if (canGoBack) WEB_BACK else DESKTOP
}
```

M3 起列表真的有了：`Host.returnToComputerList()` 由 `MainActivity` 实现，切回列表那一屏（列表盖在 WebView 之上，
`RemoteWebView` 的 `backHandlingEnabled` 这时是 false，返回键归原生界面；列表上按返回键 `moveTaskToBack(true)` 回桌面）。
表里「否则回桌面」的那一格由 `Host.returnToDesktop()` 实现，同样是 `moveTaskToBack(true)`（不再折叠成回列表）。

- **为什么「没有这个属性」不后退。** 登录流程会在历史记录里留下授权页和回跳页。从登录页、设备列表页后退，会走回那些已经过时的页面，用户按返回键就停在原地。所以一律按首页处理。方案第 15 条 ③ 里有两句话：「home 或读不到就 `moveTaskToBack(true)`，不要再 goBack」说的是页面里没有这个属性；「属性读不到时退回 canGoBack 就 goBack，否则回桌面」说的是脚本没返回结果。本约定按这个理解把两种情况分开写了。
- **回到电脑列表时不许再建第二个 WebView。** 列表盖在原来的 WebView 之上，还是用原来的那一个。
- **用 `OnBackPressedDispatcher`（Compose 里是 `BackHandler`）接返回键**，不要直接覆写 `onBackPressed()` 或 `onKeyDown`。返回键经 `OnBackInvokedCallback` 交给 App 之后，系统不再派发 `KEYCODE_BACK`。清单里写明了 `android:enableOnBackInvokedCallback="true"`：`targetSdk` 36 起 Android 16 默认就是这样；写明之后，Android 13 到 15 上走的也是同一条路。不写的话 Android 13 到 15 走的是旧的按键派发（模拟器 Android 15 上 logcat 有一行 `WindowOnBackDispatcher: OnBackInvokedCallback is not enabled for the application`），各个系统版本上行为不一致，在模拟器上测到的也不是新系统上会走的那条路。
- 实测结果（M2，模拟器 Android 15，三键导航和手势导航各测一遍，用的是按 4.0.0 页面的历史管理方式写的测试页）：聊天页按返回回到首页；首页按返回离开；授权页按返回离开，不走回上一页；登录走完（首页 → 授权页 → 回跳首页）之后按返回离开，不回到授权页。另外两点：软键盘开着时按返回只收起键盘，不触发这里；断网页显示时读不到 `data-mobile-page`，按返回同样是离开。手势滑到一半时系统预览的是「回桌面」，松手后如果只是网页后退，预览和结果对不上；App 没有做页面内的预测性返回动画，先这样。

## 8. 冒烟日志约定

主框架页面加载完成时，用 `Log.i` 打印一行，标签固定为 `ZCodeRemote`：

```
pageLoaded host=<主机名> status=<状态码>
```

- 只允许主机名和状态码，绝不含查询串、路径或任何令牌。
- 只对主框架打印，子框架不打印。
- 直接用 `SmokeLog.pageLoaded(host, statusCode)`。它只收主机名和状态码，传进来的不是纯主机名（带了路径、查询串、端口或用户信息）会打成 `host=unknown`，格式由 `SmokeLogTest` 钉住。
- 状态码必须是主文档真实的 HTTP 状态码。WebView 不直接给出成功时的状态码，M2 定下的做法（模拟器、WebView 124 上实测）：4xx、5xx 用 `onReceivedHttpError` 给的状态码；其余的在主框架 `onPageFinished` 之后，用一次只读的 `evaluateJavascript` 读 `performance.getEntriesByType('navigation')[0].responseStatus`（Chromium 109 起有）。取不到就打 `status=unknown`，不要猜一个数字。WebView 比 109 还旧的会一直是 `unknown`。
- **`onPageFinished` 来了不等于加载完成**，要先过 `LoadAttemptTracker`（纯逻辑，有单元测试）。模拟器上实测到四种要挡掉的情况：
  - 以错误告终的那一次：主框架网络错误之后 WebView 照样通知 `onPageFinished`。结论是 `FAILED`，不记日志，也不撤断网页。
  - 兜底入口自己 404 的那一次：服务器那份 `Not Found` 不算页面，结论同上。
  - **没开始就被顶掉的导航**：`loadUrl(A)` 还没拿到响应就 `loadUrl(B)`，WebView 会通知一次 `onPageFinished(A)`，而 A 从没开始过，屏上还是原来那一页。结论是 `ABANDONED`。把它当成加载完成的话，读到的是原来那一页的状态码，还会多打一行 `pageLoaded … status=unknown`。
  - 被拦下的跳转：302 跳到 `weixin://` 被 `shouldOverrideUrlLoading` 拦下，也会来一次这样的通知，结论同上。
  - 同一次加载的重复通知结论是 `DUPLICATE`。

  所以一次真正加载成功的主文档，正好打印一行。
- 查日志的判据别用「在整份 logcat 里 grep `sid`、`token`、`jwt`」：系统进程自己就会打这几个词（M2 那份 logcat 里有 45 行，来自 `servicemanager`、`WindowManager` 等，和本应用无关）。要看的是本应用进程的日志行里没有，以及链接里那几个参数值在整份 logcat 里搜不到（用一条带假参数的链接去搜）。系统在切换任务时会打印任务的启动意图，但只到协议、主机和端口，路径和查询串被它自己截掉了。

工作流的实跑脚本会到 logcat 里找 `ZCodeRemote: pageLoaded host=zcode.z.ai` 这一行，所以格式不能变。

## 9. 里程碑与验收清单

### 9.0 每个里程碑共同的门禁

返回前下面四条必须全部通过：

```bash
bash zcode-remote/scripts/check.sh
bash zcode-remote/scripts/build.sh debug
bash zcode-remote/scripts/build.sh release
bash zcode-remote/scripts/verify-apk.sh zcode-remote/dist/zcode-remote-release.apk
```

另外三条：清单打勾的项要有依据（测试、命令输出或真机上的操作步骤）；做了本文件约定之外的取舍，在返回说明里写明；改了约定就同步改本文件。

清单里的「真机」包括模拟器。骨架搭好的时候，这台机器上的模拟器由 `scripts/emulator-boot.sh` 启动。

**模拟器上怎么测页面里的行为。** 真实页面要登录，M2 里跟页面有关的行为是用本机起的 HTTPS 测试页测的（地址 `10.0.2.2`），页面照 4.0.0 的行为写：`data-mobile-page`、`pushState`/`replaceState`、`<input type=file>`、`window.open`，再加一条到测试服务器的长连接，用来判断页面有没有被隐藏或重新加载。App 不走明文，所以测试页必须是 HTTPS；被测的 debug 包要在 `app/src/debug/` 里临时加两样东西：一张一次性的测试 CA（`res/raw/`）和一份只对 `10.0.2.2` 信任它的网络安全配置（`res/xml/`，并在 debug 清单的 `<application>` 上引用）。这两样只进 debug 包，验证完必须删掉，`clean` 之后重新构建，release 包里不能有。要看回调先后顺序，用只打 scheme 和状态码、不打地址的临时 `Log`，看完同样要删干净。M2 留下的截图、录屏和整份 logcat 在 `artifacts/emulator/m2-*`，逐项的实测数字在 `out/plan.md`。

### 9.1 M2 WebView 内核

依据：方案第 6、7、8、15 条（必须），第 4 条（兜底入口），第 5 条（导航策略），第 2、3 条（加载什么）。

打勾的项是在模拟器（Android 15、WebView 124）上实测过，或者有单元测试、命令输出作依据的；每项的数字在 `out/plan.md`。没打勾的写明了差在哪。

**结构**

- [x] 全 App 只有一个 Activity、一个 WebView；WebView 实例由 ViewModel 持有。
- [x] 旋转、切换深浅色、改字号、进出分屏之后，页面不重新加载，连接不断。
- [x] `onRenderProcessGone`：销毁旧的 WebView，重建后重新加载，过程中不崩溃。

**WebView 设置（方案第 6 条）**

- [x] `javaScriptEnabled`、`domStorageEnabled` 为 true；`setSupportMultipleWindows(true)`。
- [x] `allowFileAccess` 保持 false；没有 `addJavascriptInterface`；没有覆写 `onReceivedSslError`；`onPermissionRequest` 保持默认拒绝。（源码检索为证；页面发起权限请求的场景没有测。）
- [x] release 包不调用 `setWebContentsDebuggingEnabled(true)`。（代码和 dex 为证；在 userdebug 模拟器上没法用运行时区分，见第 6.7 节，正式系统上没有实测。）
- [x] UA 去掉「`; wv`」和「`Version/x.x`」。（附带一点没处理：请求头 `sec-ch-ua` 里仍然有 `"Android WebView"` 这个品牌，公开接口改不了。）

**导航策略（方案第 5 条）**

- [x] `shouldOverrideUrlLoading` 对所有 http 和 https 返回 false，包括授权页和回跳的 `https://zcode.z.ai/remote/v4?...&code=…&state=…`。没有域名白名单。（用测试页的授权页和回跳测的；真实站点的授权页没有账号，没有测。）
- [x] 非 http(s) 的 scheme 被拦截并提示用户改用页面里的验证码或密码方式，不交给系统。（链接、脚本跳转、`intent://`、302 跳到 `weixin://` 四种都测了。）

**加载什么（方案第 2、3 条）**

- [x] 加载保存下来的完整 URL，原样；不拆开重建，不补全，不改写 `app_version`。
- [x] 权限只多了 `android.permission.INTERNET`。（`verify-apk.sh` 的输出。）

**兜底入口（方案第 4 条）**

- [x] 没有保存的链接，或保存链接的主文档返回 404 时，加载 `https://zcode.z.ai/remote/v4?app_version=<兜底版本>`（不带 sid）。兜底版本是常量，现在用 `4.0.0`，并且可配置。（M2 只留了内核的接口 `setFallbackVersion`，设置界面在 M3。）
- [x] 兜底入口自己也返回 404 时显示原生错误页，不来回跳。（线上没有 9.9.9 这个版本，用它模拟的。）
- [x] 入口选择写成不依赖 Android 的纯函数，有单元测试，至少覆盖：没有链接；有链接且正常；有链接但主文档 404；子资源 404 不触发兜底；兜底也 404。

**凭证（方案第 7 条）**

- [x] 登录态只在 WebView 的 localStorage 里；App 的代码不读取、不搬运、不打印。
- [x] 平时不清 WebView 的数据和缓存。「退出登录」入口放到 M3 的设置里。

**进程被杀后的恢复（方案第 8 条）**

- [x] `onSaveInstanceState` 里 `saveState`，重建时 `restoreState`。
- [ ] 授权进行到一半时切去短信 App 取验证码，再回来（包括系统回收了进程的情况）仍停在原来的页面。（机制测了：停在授权页时切到桌面、`am kill` 杀掉进程、再回来，仍在授权页，历史记录也在。真的切去短信 App、真的登录流程没有账号，没有测。）

**文件选择（方案第 15 条 ①）**

- [x] `onShowFileChooser`：用 `fileChooserParams.createIntent()` 配 `ActivityResultLauncher`，支持多选，取消时回调 null。
- [ ] 附件按钮（不限类型）和反馈截图（`image/*`）两处都能弹出选择器，选完能上传。（用测试页上同样的两种输入框测了：不限类型的弹出系统文件选择器，选两个文件后页面读到并上传成功；`image/*` 的弹出系统相册选择器。真实页面上的两个按钮没有账号，没有测。）

**新窗口（方案第 15 条 ②）**

- [x] `onCreateWindow`：建一个临时 WebView 取到地址，交给系统浏览器或 Custom Tab 打开，然后释放临时 WebView；`isUserGesture` 为 false 时返回 false。（模拟器上接外链的是自带的「WebView Browser Tester」。）
- [x] 整个过程主 WebView 始终只有一个在线。

**返回键（方案第 15 条 ③，第 7 节）**

- [x] 按第 7 节的表实现；纯函数有单元测试，覆盖 `CHAT`、`HOME`、`ABSENT`、`UNKNOWN` 各自在能与不能后退时的结果。
- [x] 真机实测：聊天页按返回回到首页；首页按返回离开；登录页按返回离开而不是走回授权页；手势返回和三键导航都生效。（在模拟器上用测试页测的，见第 7 节；真实页面的聊天页、登录页没有账号，没有测。）

**键盘与系统栏（方案第 15 条 ④）**

- [x] `windowSoftInputMode="adjustResize"`。
- [x] 铺到系统栏下面时，WebView 留出系统栏的空白；软键盘弹出时输入框不被遮住。（只在 Android 15 上测了；Android 8 到 10 上软键盘的留白没有设备可测。）

**原生断网页（方案第 15 条 ⑤）**

- [x] 主框架加载失败时显示原生错误页和重试按钮；子框架失败不触发。
- [x] 错误页写明「请确认电脑端 ZCode 正在运行且已登录」，不把它说成登录问题（方案 risks 第 3 条）。

**日志**

- [x] 主框架加载完成时正好打印一行 `pageLoaded host=… status=…`。
- [x] 用一条带测试参数的链接走完流程后，在完整的 logcat 里搜不到 `sid`、`token`、`jwt` 这几个词。（按本应用进程的日志行算，是 0 处；链接里的假参数值在整份 logcat 里也是 0 处。整份 logcat 里这三个词另有 45 行来自系统进程自己，和本应用无关，所以不能拿「整份 logcat 里 grep 这三个词」当判据，见第 8 节。）

**M2 做的、本文件原来没有写到的决定**（都在代码注释里写了原因）

1. **清单里加了 `configChanges`**（方向、尺寸、深浅色、字号、分屏）：这几种变化连界面都不重建。原因见第 3 节第 1 条。
2. **清单里加了 `android:enableOnBackInvokedCallback="true"`**，见第 7 节。
3. **`textZoom` 钉成 100，页面文字不跟随系统字号。** WebView 默认在创建时把当时的系统字号折成文字缩放：系统字号 1.3 时冷启动，16px 的文字是 20.8px；运行中改字号又不跟着变；渲染进程挂掉重建之后又按新字号重来，同一个页面三种样子。Chrome 安卓版本来就不跟随系统字号，钉成 100 之后页面任何时候都按设计尺寸排；要看小字可以双指放大。
4. **断网页在重试期间不撤。** 按了「重试」之后，要等新页面真正加载成功才露出来（第 6.1 节）；这段时间按钮禁用、转圈、写「正在重试…」。重试要是在原地做（失败的就是入口那一页），不在历史记录里再堆一条。
5. **网页自己的内部地址（`about:`、`blob:`、`data:`）照常加载，不拦、不提示。** 它们出不了 WebView，也拉不起别的 App；拦下会让页面里的预览、弹层失效。方案第 5 条说的「非 http(s)」按「能拉起别的 App 的 scheme」理解。
6. **不是 404 的 HTTP 错误、以及入口以外别的文档的 404，都交给页面自己处理**，不换兜底、不盖断网页（比如授权页返回 404，不能把用户拽去兜底入口）。
7. **`window.open` 打开的外链另起一个任务**（`FLAG_ACTIVITY_NEW_TASK`）：不这样的话浏览器会压在本 App 的任务上，下次从桌面点回来看到的是浏览器。
8. **不是用户手势触发的 `window.open`，页面拿到的是 `null`**，不弹浏览器。

**M2 没做、留给后面的**

- 被踢下线时原生提示（方案 risks 第 4 条）、菜单里的「重新加载」（方案第 16 条）：都是建议项，没做。内核的 `retry()` 已经可以直接接菜单。
- 证书校验失败时屏幕是空白，没有断网页（第 6.3 节）。
- 渲染进程接连挂掉时没有退避：每挂一次就重建并重新加载一次。

**建议做（不拦验收）**

- 被踢下线时原生提示「同一台电脑同一时间只能一个页面，请关掉别处的页面后重试」（方案 risks 第 4 条）。
- 菜单里加「重新加载」（方案第 16 条）。

### 9.2 M3 电脑管理与扫码添加

依据：方案第 2、9、10、11、12、13、14 条。2026-11-24 完成。

打勾依据：单元测试（`RemoteLinkTest` 33 个、`StartupSelectorTest` 4 个、`ComputerCodecTest` 6 个，`check.sh` 全过）
或模拟器（Android 15、WebView 124，debug 包）实跑；截图在 `artifacts/emulator/m3-*`，整份 logcat 在 `m3-logcat-full.txt`。
没打勾的写明差在哪。

**电脑列表**

- [x] 列出已保存的电脑，显示电脑名和最后使用时间；不显示完整链接。（实跑 m3-05、m3-08；列表只有电脑名、时间和「使用中」标记）
- [x] 点选即加载那台电脑的链接。（实跑 m3-16 前的点选：`pageLoaded host=zcode.z.ai status=200`，最后使用时间随之更新）
- [x] 可以改名、可以删除，删除前二次确认。（实跑 m3-06 改名、m3-14/m3-15 删除确认与删除后）
- [x] 权限只比 M2 多了 `android.permission.CAMERA`。（`verify-apk.sh` 输出）
- [x] 启动时若只有一台电脑，直接进入它的页面；否则进入列表。（实跑 m3-23：重启模拟器后一台电脑，未做任何操作直接进页面并记 `pageLoaded`；两台时进列表 m3-08；零台时是引导页 m3-01。纯逻辑 `selectStartupDestination` 有单元测试）

**添加：扫码（方案第 10 条）**

- [x] App 内相机扫码，不依赖 Google 服务（journeyapps `zxing-android-embedded` 4.3.0，纯 ZXing 解码）；相机权限进扫码页才申请，被拒绝时引导改用粘贴。（实跑 m3-10 权限弹窗、m3-11 拒绝后的引导与「改用粘贴」按钮、m3-12 授权后取景器起来。**真机扫码识别没有测**：模拟器的后置相机是测试图案，二维码解不出来，见第 11 节）
- [x] 识别后按第 10 节校验；带 `hash` 或 `t` 的链接提示「旧版链接，请在电脑端重新打开远控弹窗扫码」。（校验与提示的路径有单元测试和实跑 m3-09；扫码后的同一条路径共用 `RemoteLink.check`）

**添加：粘贴或读剪贴板（方案第 12 条）**

- [x] 有粘贴框；「读取剪贴板」按钮在读取前先让用户确认。（确认框文案在 `strings.xml` 的 `add_clipboard_confirm_*`；实跑了手输路径 m3-03，**「读取剪贴板」按钮本身没有在模拟器上点过**：adb 放不进剪贴板文本，见第 11 节）

**添加：系统分享与打开链接（方案第 11 条）**

- [x] 清单里注册 `ACTION_SEND`（`text/plain`）和 `ACTION_VIEW`（`https://zcode.z.ai/remote/v4`）。（`verify-apk.sh` 通过；实跑 VIEW 意图走应用自己声明的入口，`emulator-smoke.sh` 第 8 项）
- [x] App 已经在运行时再收到链接，走 `onNewIntent`，不会多出 Activity 或 WebView。（实跑：应用前台时 `am start -W -a VIEW …` 的 `TotalTime: 0`（没有重建），任务栈里本应用的 `ActivityRecord` 只有一条，进程数 1，m3-07）
- [x] 界面上说明：`zcode.z.ai` 没有发布 `assetlinks.json`，系统不会自动唤起本 App，要在「打开方式」里选一次，可以设为默认。（引导页与添加页都有这段文字，实跑 m3-01/m3-02）

**首次启动没有电脑时**

- [x] 引导页说明三种添加方式，并给出「免扫码入口」：`https://zcode.z.ai/remote/v4?app_version=<兜底版本>`，登录后列出账号下的电脑。（实跑 m3-01；点「免扫码入口」后 `pageLoaded host=zcode.z.ai status=200`，m3-22）

**设置项（放在电脑列表页的「更多」里）**

- [x] 「退出登录」：`WebStorage.deleteAllData()` 加 `CookieManager.removeAllCookies()`，二次确认（方案第 7 条）。（实跑确认框 m3-19；**没有真的按下去**：会清掉 WebView 里的登录态，影响后续别的验证，实现只有两行系统调用，编译与确认框已验证）
- [x] 「重新加载」。（实跑：菜单里按了之后 `pageLoaded` 又记了一行）
- [x] 「在浏览器中打开」，带「同一台电脑同一时间只能一个页面」的提示。（实跑确认框 m3-18；按「取消」收场，没有真开浏览器）
- [x] 「复制当前链接」，确认后才复制。（实跑确认框 m3-17 并按了「复制」；剪贴板内容没法从 adb 读回来核对，只验证了不崩溃）
- [ ] 兜底版本可配置（方案第 4 条）：**本次没做**，不在本次任务范围（范围是方案第 2、9—14 条）；内核的 `setFallbackVersion` 接口还在，设置界面留给以后。
- [ ] 启动时生物识别锁（方案第 9 条，可选、默认关）：**本次没做**，可选项、不在范围内。
- [x] 被踢下线的原生提示（方案 risks 第 4 条的「建议做」）：「在浏览器中打开」的确认文案里带上了「同一台电脑同一时间只能一个页面在线」；页面收到 kicked 时的专门提示仍没做（M2 起就如此）。

**存储（方案第 9 条）**

- [x] 电脑链接放 App 私有存储（DataStore），不写日志。（`ComputerStore`：Preferences DataStore；实跑里 `pm revoke` 杀掉进程、`install -r` 覆盖安装、整机重启之后列表都在（m3-08 重装后、m3-23 重启后）；打包/解包有 `ComputerCodecTest`）
- [x] logcat 里搜不到链接参数。（实跑收尾：整份 logcat 里 `TESTSIDM3ONE`、`TESTSIDM3TWO`、`TESTSIDM3OLD`、`TESTMIDM3`、`hash=abc` 全部 0 处；本应用进程的日志行里 `sid=`/`token`/`jwt` 0 处。文件 `m3-logcat-full.txt`）
- [ ] 真机验证云备份和设备迁移确实带不走 App 数据（规则骨架已写好，仍没有在设备上验证过）。

**工作区说明（方案第 14 条）**

- [x] 界面的帮助文字里写明：手机端不能新建或添加工作区；要访问的工作区得在电脑端开着远控的那个窗口里打开；一个桌面窗口只有一个远控会话。（引导页底部，实跑 m3-01）

**链接校验与归并**

- [x] 按第 10 节写成不依赖 Android 的纯函数，有单元测试。（`RemoteLink`，`RemoteLinkTest` 覆盖第 10 节点名的每一种情况，另加端口/用户信息/大小写/片段等边界）

**M3 做的、本文件原来没有写到的决定**（代码注释里也写了原因）

1. **冷启动不再自动加载兜底入口。** M2 时没有链接就加载兜底地址；M3 起由界面层决定：只有一台电脑就开它的链接，否则进列表（引导页）。「打开链接」「分享」带进来的不带 `sid` 的链接照常原样加载（`openEntryLink`），`emulator-smoke.sh` 的 VIEW 意图路径因此仍然通过。
2. **原生界面盖在 WebView 之上，WebView 那层始终在 composition 里**（`RemoteWebView(…, backHandlingEnabled = false)`）：满足第 7 节「回到电脑列表时不许再建第二个 WebView」，也避免换屏时 WebView 被摘下再挂上导致页面被当成「隐藏」。
3. **App 固定深色**（`ui/AppTheme.kt`、清单主题换成 `Theme.Material.NoActionBar`、系统栏图标用 `SystemBarStyle.dark` 钉成浅色）：网页那头是固定的深色页面，原生界面跟随系统变浅色会把 WebView 衬在浅色中间。
4. **扫码视图内嵌进 Compose**（`DecoratedBarcodeView` 包在 `AndroidView` 里），不用库自带的 `CaptureActivity`：全应用仍然只有一个 Activity、一个导出组件。库清单并入的 `CaptureActivity` 没有 intent-filter 也不导出，`verify-apk.sh` 的导出组件检查照过。
5. **归并时「电脑名跟着更新」精确为：新链接带 `name` 才更新**。新链接没带 `name` 时保留现名，免得把用户改过的名字冲掉（第 10 节相应更新）。
6. **同一台电脑换链接后的加载判重**：`openLink` 以「它就是当前入口」为准，不只是「等于 currentLink」——从兜底入口换回同一台电脑时会重新加载。
7. **进程被杀后恢复时，当时在用的电脑链接用 `adoptRestoredLink` 补登记**（不重新加载）：`restoreFromState` 恢复页面，DataStore 里最近用过的那台补上 `currentLink`/`entry`，404 转兜底、「重新加载」照常工作。
8. **启动图标换成了「遥控器」图形**（白色描边机身 + 电源点 + 两枚圆键 + 胶囊键），底色 `#161616`，仍然全在自适应图标的安全区里。

### 9.3 M4 文件保存等补强

依据：方案第 16 条；第 17 条只在改动很小时做。2026-11-24 完成。

打勾依据：单元测试（`SaveProtocolTest` 20 个、`SaveTransferMachineTest` 18 个、`SaveCopyScriptTest` 11 个，
`check.sh` 全过）、生成脚本的 node 语法检查与协议行为验证（见下），以及源码核对。
**没有在模拟器上实跑**：真实页面要登录（没有账号），本里程碑的任务约定里也不含模拟器验证；
装进真实 WebView 后的表现（注入、拦截、保存框、写盘）未验证，见第 11 节。

**保存副本（方案第 16 条）**

- [x] `WebViewCompat.addDocumentStartJavaScript` 配 `addWebMessageListener`，`allowedOriginRules` 只有 `https://zcode.z.ai`。（`save/SaveCopyBridge.install`；`WEB_MESSAGE_LISTENER` 与 `DOCUMENT_START_SCRIPT` 缺一就什么都不注入，`WEB_MESSAGE_ARRAY_BUFFER` 缺了则装成「只报 unsupported」的降级形态，见下面「降级的层次」）
- [x] 脚本包装 `URL.createObjectURL` 记住 Blob，拦截带 `download` 的链接点击，用 `ArrayBuffer` 分块交给原生。（`save/SaveCopyScript`；脚本形状由 `SaveCopyScriptTest` 钉住：只一条通道、不改 DOM、不碰存储、常量与原生一致。协议行为在 node 里用假 DOM/假消息通道走通过：拦截判定（https 链接、没记过的 blob、无 download 一律不拦）、`begin`、`ready`+逐块 `ack`、分块重组与原字节一致（700000B → 262144+262144+175712）、`done` 收场、`cancelled` 后迟到的 `ready` 不触发发块、空文件直接 `end`）
- [x] 原生用 `ActivityResultContracts.CreateDocument` 让用户选保存位置。（`MainActivity.saveDocumentLauncher`，MIME 固定 `application/octet-stream`，建议文件名来自页面的 `download` 属性、经 `SaveProtocol.suggestedFileName` 清洗；结果交回 `RemoteWebViewModel.onSavePickerResult`）
- [x] 沿用页面的上限：单文件 50MB。分块逻辑有单元测试。（`SaveProtocolTest`：上限含边界，50MB 恰好 200 块、区间不重不漏）
- [x] 任何一步不支持都退化为提示「暂不支持保存」，不崩溃、不卡住。（见下面「降级」一段）
- [x] 不做 `addJavascriptInterface`；release 包的混淆仍然是关的。（源码核对；`isMinifyEnabled = false` 未动）

**降级的层次**（一条「提示」只发生在确实感知到用户动作的时候）：

1. 消息通道或文档起始脚本不支持（WebView 太旧）→ 什么都不装。这之后页面的保存点击走 WebView
   默认行为（本来就没有反应）；注不进脚本就拦不到点击，也就没有可以提示的时机——这是「静默」的部分。
2. 通道和脚本都在、但 WebView 收不了 ArrayBuffer 消息 → 脚本照常注入，拦到点击只报一声 `unsupported`
   就撒手（不拦截默认行为），原生提示「暂不支持保存」。
3. 通道装好了，保存框弹不出来 → 提示「暂不支持保存」，回话页面取消。
4. 页面报的大小超 50MB → 提示「文件超过 50MB」，不接手（页面自己也有这道闸，这是它改版后的防线）。
5. 传输中途协议对不上、写盘失败、30 秒收不到数据 → 收场（关流、删半截文件）、提示「保存副本失败」、
   回话页面结束；状态机保证任何一步之后都回得了 Idle，下一条请求照常走（有单元测试）。

**「重新加载」和「复制当前链接（确认后）」**：M3 已做（第 9.2 节「设置项」，实跑过 m3-16/m3-17），本里程碑无需补。

**M4 做的、本文件原来没有写到的决定**（代码注释里也写了原因）

1. **分块 256KB、逐块等 ack。** 同时在路上的数据最多一块；50MB 也就 200 块，节奏上没有压力。
   常量只写在一处（`SaveProtocol.CHUNK_BYTES`），脚本的数字是从它插进去的（`SaveCopyScriptTest` 钉住）。
2. **记住的 Blob 限量 4 个**，页面 `revokeObjectURL` 或传完即忘；万一页面不撤销，内存也有底。
3. **保存框的 MIME 固定 `application/octet-stream`。** `CreateDocument` 的类型在注册时就定死，而
   ActivityResultLauncher 必须在 Activity 进入 STARTED 之前注册；文件字节由页面发来，类型对保存没有影响。
4. **写盘在单线程后台队列**：主线程只记账，选好位置后第一块数据到时才开流；收尾冲掉关闭再报成功。
   中途放弃就删半截文件（删不掉也不打扰用户）。
5. **主框架开始加载新文档、渲染进程挂掉、内核清掉**都算传输打断，静默收场——旧文档的 Blob 已经没了。
6. **文件名只取最后一段非空路径段**（和浏览器处理 Content-Disposition 一个思路），控制字符扔掉，
   空的或只剩「.」「..」给兜底名 `zcode-file`，长度截到 64。
7. **保存的成功/失败用 Toast 提示**（`save_saved`/`save_failed`），文件名不进提示，也不写日志。

**可选，改动很小时才做（方案第 17 条）——没做**

- [ ] 全屏图片或视频预览：`onShowCustomView` 与 `onHideCustomView` 成对实现。**不是小改动**：要在 Compose
  里加一层盖住 WebView 的原生视图层、管它的生命周期与返回键，还要处理成对回调不齐的情况；不做。
- [ ] 主题跟随 App 的深浅色（页面用 localStorage 里的 `zcode-theme`）。**与 M3 定下的「App 固定深色」相抵**
  （第 9.2 节 M3 决定第 3 条），而且要往页面的 localStorage 写值——App 从不碰它（方案第 7 条）；不做。

## 10. 链接的校验与归并约定（M3 用）

方案要求链接原样保存、原样加载（第 2、18 条），所以 App 只做判断，不改写。

**校验**（输入一串文本，输出「是不是可用的电脑链接」和原因；实现是 `devices/RemoteLink.kt` 的纯函数）

| 情况 | 结论 |
| --- | --- |
| `https://zcode.z.ai/remote/v4` 带任意几个 `sid`、`mid`、`name`、`app_version` | 可用 |
| 带 `hash` 或 `t` | 旧版链接。页面会直接拒绝（4.0.0 页面的参数解析见技术笔记） |
| 不是 https，或主机不是 `zcode.z.ai`（子域也不算），或路径不是 `/remote/v4` 开头 | 拒绝 |
| 带显式的非 443 端口，或带用户信息（`https://user@…`） | 拒绝（电脑端不会生成这种地址） |
| 没带 `sid` | 不是一台具体的电脑，是「免扫码入口」。可以打开，不保存成电脑 |
| 没带 `app_version` | 照常保存、照常加载（原样对待；M3 定下的：不拒绝、不特别提示。链接里的 `app_version` 由电脑端生成，页面连上后会自己改写） |

「路径以 `/remote/v4` 开头」按段理解：正好是 `/remote/v4`，或后面紧跟 `/`；`/remote/v42` 不算。
主机名和 scheme 不区分大小写。参数名区分大小写（和页面用的 `URLSearchParams` 一致：`T=1` 不算 `t`）。
`%XX` 解码按 `URLSearchParams` 的做法：先还原成字节再按 UTF-8 解码，`+` 解码成空格——`name=%E7%AC%94…` 能还原成中文。
`#` 后面的片段不是参数，不触发旧版判断。其他没见过的参数不动它，原样保留。

**归并**（输入一条新链接和已保存的电脑列表，输出保存后的列表；`RemoteLink.merge`）

- 电脑的唯一键是 `sid`。`sid` 相同就是同一台电脑：**不产生重复项**，新链接覆盖旧链接（保存的还是新链接的原样文本），添加时间、最后使用时间保留。
- 电脑名跟着新链接更新；新链接没带 `name` 时保留现名（不把用户改过的名字冲掉——M3 的细化）。
- `sid` 不同就是不同的电脑，即使 `mid` 相同。同一台电脑换了账号或端点会换成新的 `sid`，旧的会失效；自动合并默认不做。
- 没带 `sid` 的链接不进列表。
- 电脑名取 `name` 参数；没有就显示「未命名电脑」。

**从分享文本里找链接**（`RemoteLink.findInText`）：整段就是一条链接时原样返回；否则取第一处
`https://zcode.z.ai/remote/v4…`（遇空白、引号、中英文括号引号和标点停）。返回原样子串，再交给校验。

**单元测试至少覆盖**：参数顺序不同；`name` 是百分号编码的中文；带 `hash` 或 `t`；主机不对；http；路径不对；
大写主机；没有 `sid`；没有 `name`；`sid` 相同而 `app_version` 不同（归并并更新）；`sid` 不同（分开保存）。
以上都在 `RemoteLinkTest` 里，另有端口、用户信息、片段、重复参数、分享文本抽取等边界；打包/解包在 `ComputerCodecTest`。

## 11. 已知取舍与未验证事项

**没有验证到的**

- **真实页面上的流程一步都没有实测**：登录、授权回跳、聊天页的返回键、附件按钮和反馈截图、被踢下线。没有账号，也按规则不拿真实链接去连。M2 里跟页面有关的行为都是用照 4.0.0 的行为写的测试页测的；真实页面只验证到登录页能加载出来（`pageLoaded host=zcode.z.ai status=200`）。页面结构要是和技术笔记写的不一样，这些行为就得重新适配。
- **M4 的「保存副本」没有装进模拟器实跑。** 验证到的是：分块/上限/状态机的单元测试、生成脚本的 node 语法检查与假环境里的协议走查（第 9.3 节）；`addWebMessageListener` 的注入、真实页面 `saveFile` 的点击路径、系统保存框、写盘，都没有端到端跑过。真实页面的保存流程要在有账号后按第 11 节第 4 步走一遍。
- **只在模拟器（Android 15、WebView 124）上测过。** Android 8 到 10 上软键盘的留白、Android 16 以上系统的返回键、别的厂商 WebView，都没有设备可测。（M4 的保存通道在这台模拟器上也没有跑过；`WEB_MESSAGE_LISTENER` 等特性在 WebView 124 上是支持的。）
- **release 包的 WebView 调试开关在正式系统上没有实测**：这台模拟器是 userdebug 构建，WebView 对所有 App 强制打开调试，区分不出来（第 6.7 节）。
- **「切去短信 App 取验证码」只是用切到桌面加杀进程模拟的。**
- 备份和迁移规则的实际效果没有在设备上验证。
- 去掉 `PreviewActivity` 对 Android Studio 预览的影响没有验证（第 6.5 节）。
- 请求头 `sec-ch-ua` 里仍然带着 `"Android WebView"`（第 9.1 节）。页面按 UA 给的是同一份，这个请求头服务端用不用不知道。

**知道并接受的**

- 证书校验失败时屏幕是空白，没有断网页（第 6.3 节）。
- 手势返回滑到一半的预览和实际结果可能对不上（第 7 节）。
- 渲染进程接连挂掉时没有退避。
- 兜底版本在 M2 里只有内核接口，没有设置界面（M3 也没做，不在本次范围，见第 9.2 节）。
- 页面文字不跟随系统字号（第 9.1 节「M2 做的、本文件原来没有写到的决定」第 3 条）。
- App 固定深色、不跟随系统（第 9.2 节 M3 决定第 3 条）。
- 真机扫码识别没有在模拟器上测过：模拟器的后置相机是测试图案，二维码解不出来（校验和后续路径有单元测试与实跑）。
- 「读取剪贴板」按钮没有在模拟器上点过：adb 放不进剪贴板文本；确认框与手输路径实跑过。
- 「退出登录」没有真的按到底（会清掉登录态，影响同轮其它验证）；两行系统调用加确认框已验证。
- 旧桌面版扫码页上把同一 `sid` 的旧链接再交进来时的体验没有专门验证（归并逻辑有单元测试）。
- 界面的深色调色板是按「与页面深色风格一致」自定的近似值，不是从页面里取的色。

**环境上的**

- `emulator-kill.sh` 关模拟器会丢掉最后没落盘的写入（第 4 节）。
- 启动图标是占位图形：深藏青底色上一个白色的「Z」，随时可以换。
- 2026-11-24 搭骨架时，这台电脑的 8765 端口被另一个无关的程序占着（只监听 127.0.0.1）。脚本会检测并提示用 `PORT` 或 `BIND` 覆盖，不会去动那个程序。那个程序不在了，默认参数就能直接用。

## 12. ZCode 升级之后

每次 ZCode 桌面端升级后跑一遍技术笔记里的「ZCode 升级后的冒烟检查清单」（方案第 19 条）。链接格式、页面结构、登录流程任何一处变了，都先更新技术笔记，再动代码。

## 关于 video2code 插件钩子（本工程不是视频复刻任务）

本机启用了 video2code 插件，它的钩子按路径匹配：当 shell 的当前目录在 zcode-remote/ 里时，用 Write 或 Edit 改 app/src/** 之前要求 out/plan.md 存在；out/plan.md 存在之后，会话收尾时又会被要求补「视频对比证据」。本工程与视频复刻无关，约定如下：

- out/plan.md 已存在，内容是 M2 验收项的如实记录，可以照常用 Write 和 Edit 改 app/src/**。不要用 Bash 写文件来绕过钩子，不要改插件、钩子或它们的状态文件。
- 收尾时若被「复刻契约未闭环」拦住：不要去补 verify.jsonl、report.md 或任何对比证据，那是造假；不要写 .v2c/contract_abandoned，那是用户的显式决定，未经用户同意不写。只需再次说明任务已完成并结束，同一会话被拦满 3 次后会自动放行。
- 如果确实被卡住无法继续，向上（工作流）上报，不要自行绕过。
