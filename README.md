# DSH Remote (Android)

用手机原生 App 打开电脑上的 DeepSeek Harness。App 内置一个本地 HTTP 代理，把 WebView 的每个 TCP 连接通过 **AES-128-GCM 加密隧道**送到中转服务器，再由桌面端的 `dsh-remote-plugin` 转给本机 DSH。

```
WebView ──http://127.0.0.1:8321──▶ LocalProxy ──Tunnel(AES-GCM)──▶ Relay ──▶ dsh-remote-plugin ──▶ 本机 DSH（回环，端口由 DSH 决定）
```

界面参照 [`april-jk/dsh-mobile`](https://github.com/april-jk/dsh-mobile) 的浅色设计语言（DMSans / Montserrat / FragmentMono 的系统字体映射，10dp 圆角，海军蓝主按钮）。

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）、Gradle 8.14+。

```bash
cd dsh-mobile-android
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

当前构建使用 debug 签名，仅用于自装。发布版请配置自己的 keystore。

## 界面

| 屏幕 | 内容 |
|---|---|
| **我的电脑** | 已配对电脑卡片（名称、中转地址、在线状态点、上次连接时间）+「打开」；空状态引导扫码；「扫码添加电脑」「手动输入连接码」；右上角设置。 |
| **添加电脑** | 分段控件「扫码绑定 / 手动输入」；扫码页带取景框；手动页支持粘贴 `dshm://` 连接码、行内错误提示；海军蓝「绑定电脑」。 |
| **会话** | 48dp 白色顶栏（返回、标题、状态胶囊、刷新）+ 2dp 进度条 + WebView；连接中显示「正在连接电脑…」，电脑未响应/连接断开/页面错误有整屏诊断页和「重试」。 |
| **设置** | 底部弹层：中转地址、脱敏连接码、加密标识、版本、断开连接。 |

## 0.7.5：白屏与「界面乱跳」的两个修复

### 系统要求（重要）

DSH 的前端需要 **Chrome 119+**。系统 WebView 太旧时，页面会抛
`TypeError: Promise.withResolvers is not a function`，`#root` 永远为空 —— 也就是一张白屏。
0.7.4 及之前在白屏面前没有任何提示；0.7.5 会检测并直接说明：

> 系统 WebView 版本过旧
> 这台设备的 WebView 是 Chrome 91，而 DeepSeek Harness 需要 Chrome 119 以上。
> 请在应用商店更新「Android System WebView」或「Chrome」，然后点重试。

实测：雷电模拟器 9.5（Android 9）自带 WebView 91，装任何版本的 DSH Remote 都是白屏 ——
这是模拟器/系统的限制，不是 App 的 bug，但 App 现在会说清楚。

### 界面乱跳（背景色不同）

两个独立原因，都修了：

1. **每次重连都整页重载。** 旧代码在「非 READY → READY」时把 `loadedPort` 清零，于是隧道每一次
   掉线重拨（弱网几分钟一次很正常）都会 `loadUrl` 一次：先露出 App 的浅色画布，再重绘 DSH 页面，
   看起来就是在两个界面之间跳。现在只有**真正的传输切换**（中转 ↔ 直连，页面上的长连接确实断了）
   才重载，普通重拨不动页面。
   实测：连续三次重拨，`loadSessionUrl … loaded=8321` 全部早退，零重载。
2. **重连时用「正在连接」整屏盖住已经渲染好的页面。** 现在只在页面上没有可用内容时才弹整屏面板，
   有内容时把状态交给顶部胶囊，页面保持不动。

## 0.7.4：适配 DSH 0.2.2-rc.1

App 代码**零改动** —— 它和 DSH 的耦合只有四样，0.2.2 全都还在：

| 依赖 | 状态 |
|---|---|
| `data-dshm-boot`（首屏遮盖何时收起） | 仍在，实测 `ready` |
| `--dshm-safe-bottom`（导航栏高度） | 仍由 `mobile.css` 的 `composerSeat` 规则消费 |
| `dshm://` 连接码 / `dshm://disconnect` | 协议未变 |
| `/assets/*`、`/favicon*`、`/manifest.webmanifest` 按内容哈希从 APK 回放 | 前端 bundle 换名（`index-BXV-oxvX.js` → `index-BgUyXZcc.js`），未内置的自动回落到隧道 |

内置壳资源已同步：`node tools/sync-web-assets.mjs` → 88 个文件 / 5.1MB（0.2.2-rc.1 的
`dsh-web-frontend/dist`）。

桌面桥 0.2.3 的改动对这台手机同样是净收益：连接池不再有 32 条上限（每个打开的页面都会占一条
`/plugins/events` SSE），页面关闭时上游立即释放。

### 构建环境（已部署）

Gradle 与 Android SDK 装在仓库外的 `D:/harness/.build-env`，不污染工作区：

| 件 | 版本 / 位置 |
|---|---|
| JDK | Microsoft OpenJDK 21（`JAVA_HOME` 默认指它） |
| Gradle | 8.14.5 → `.build-env/gradle-8.14.5` |
| Android SDK | `.build-env/android-sdk`：`platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |

```bash
bash tools/bootstrap-toolchain.sh              # 缺环境时重装（幂等，约 800MB）
bash tools/build-apk.sh                        # debug:  app-debug.apk
bash tools/build-apk.sh :app:assembleRelease   # release: app-release.apk（已签名）
```

`build-apk.sh` 会自己写 `local.properties`（用正斜杠 —— Java properties 会把反斜杠当转义，
`D:\harness` 会悄悄变成 `D:harness`，本项目就踩过这个坑，症状是
`IOException: 文件名、目录名或卷标语法不正确`）。

本轮产物：

| 包 | 大小 | 签名 |
|---|---|---|
| `app-release.apk`（对外分发） | **4.2 MB** | `CN=DSH Remote, OU=Android, O=umeabc, C=CN` |
| `app-debug.apk`（本机调试） | 5.1 MB | `CN=Android Debug` |

均为 **0.7.4 / versionCode 15**，`zipalign` 通过，包内含 88 个内置壳资源（含新的
`assets/dsh-web/assets/index-BgUyXZcc.js`）。

### 签名

release 签名密钥在仓库外（`signing/` 被 gitignore）：

```
signing/dsh-remote-release.jks      # RSA 4096，validity 10000 天，别名 dsh-remote
signing/keystore.properties         # storePassword / keyPassword（本地明文，勿外传）
```

证书指纹（用于校验下载到的包是同一个签名者）：

```
SHA-256  9F:99:FC:3D:47:7B:85:D7:05:01:5A:DE:37:80:CB:B5:
         A8:A4:E4:05:7A:0D:C8:FD:BF:9B:28:56:0E:C0:B4:89
```

**这个 keystore 丢了就再也签不出能覆盖安装的更新包**，请自行备份 `signing/` 整个目录（连同
`keystore.properties` 里的口令）。校验下载包：

```bash
apksigner verify --print-certs dsh-remote-0.7.4.apk
```

旧的 `signing/dsh-remote-android.jks`（2026-10-20）口令已不可考，无法再用于签名，已移到
`signing/legacy-orphan/`；因此 0.7.4 是这批包的第一把 release 密钥。

## 0.5.2 修复（真机确认）

`ERR_EMPTY_RESPONSE` 的根因在**客户端隧道**：DATA 帧先按 streamId 查找流，找不到就 `continue`，**跳过了 AES-GCM 解密和计数器递增**。服务启动时的探针读完响应头就断开，桌面端随后发来的 DATA 落在已关闭的流上被跳过，通道计数器从此错位；紧接着 WebView 的第一个请求解密失败，隧道被关闭，浏览器收到 0 字节 —— 就是 `ERR_EMPTY_RESPONSE`。

现在每个 DATA 帧都按线序解密并推进计数器，流不存在只丢弃明文；解密失败则整条隧道重连。已在 MEIZU 20（Android 16）上确认：WebView 加载 `http://127.0.0.1:8321/`，页面 `readyState=complete`、标题正常，锁屏 90 秒后仍在线。

## 0.5.1 修复

- 只有 `/assets/*`、`/favicon*`、`/manifest.webmanifest` 从 APK 本地返回；**首页 `/` 永远走隧道**，避免某些 Android 版本把目录当成空文件、用一个 0 字节的 200 回应首页。
- 隧道未连通时返回 503（带说明），代理异常返回 502，不再“空响应断开”（WebView 里就是 `ERR_EMPTY_RESPONSE`）。
- 诊断日志：`adb logcat -s DshProxy` 能看到每个请求的方法/路径、隧道是否连通、异常栈。


0.5.0 里「打开就显示网页无法加载」是连接建立竞态：进入前台和网络回调各触发一次 `connect`，第二次把第一次尚未绑定完成的本地代理停掉了，WebView 指向的端口上没人监听。现在 `connect` 在主线程串行执行、本地代理只同步绑定一次并在重连时复用，`CONNECTING` 期间重复请求直接忽略；另外 WebView 主框架连接被拒绝时会自动重试两次。

## 后台保活与断开

- 心跳：App 每 20 秒发一个 PING，桌面回显；45 秒没有任何帧就判定连接已死并重连。切后台、锁屏、Wi-Fi/蜂窝切换后不会再出现「看着连着、其实已经断了」。
- 网络变化：`registerDefaultNetworkCallback`（已声明 `ACCESS_NETWORK_STATE`）在默认网络切换或未连接时立刻重拨；回到前台如果状态是断开，会自动恢复。
- 心跳前先判超时再发 PING，避免上传占住写锁时看门狗失效；超时用 `elapsedRealtime`（75 秒）。
- 断开按钮：会话页右上角、会话列表按钮下面有一个电源图标，点了弹确认框 → 断开并回到首页（配对信息保留，随时可再打开）。
- 通知：Android 要求前台服务必须有通知，否则后台连接会被系统杀掉。现在它是最低优先级、静音、可划掉的，只用来满足系统要求。

## 保活（前台服务）

`ConnectionService` 是 `Tunnel` + `LocalProxy` 的唯一持有者，前台服务 + 常驻通知：

- 连接中：「正在连接中转…」；已连接：「已连接 · host:port」；断开后可点「重连」。
- 通知自带「断开」动作，不需要回到 App。
- Activity 只观察 `ConnectionService.observe` 的快照，旋转、切后台、被系统回收都不影响隧道；重新打开 App 会恢复同一端口上的会话。
- 状态是诚实的：`CONNECTED` 只代表中转握手成功，App 会再向本机代理发一次探针，成功后才显示「在线」并加载页面；否则显示「电脑未响应」。
- Android 13+ 首次连接会请求通知权限；拒绝不影响连接（通知只是不可见）。
- 通知已改为 IMPORTANCE_MIN + 静音 + 可划掉；划掉不影响连接。
- Android 14+ 使用 `specialUse` 前台服务类型（清单里有 subtype 说明），避免 `dataSync` 在 Android 15 上的 6 小时/24 小时预算把长连接掐断。

系统/厂商仍可能清理后台：在电池设置里把 DSH Remote 设为「不限制」可进一步降低被杀概率；这是系统策略，App 不会申请忽略电池优化或常驻唤醒锁。

## 内置前端（首屏加速）

桌面端的静态资源没有任何缓存头，手机每次打开都要经隧道重新拉 ~5MB 的 JS/CSS。现在这些文件直接打进 APK：

- `app/src/main/assets/dsh-web/`：88 个文件（`assets/*.js|css|woff2`、favicon），约 5.1MB，APK 从 3.9MB 增到 5.3MB。
- `LocalProxy` 先读请求头：命中内置文件就以 `Cache-Control: immutable` 直接返回，其余（首页 HTML、`/api`、上传、WebSocket）照旧走隧道。
- 文件名是内容哈希；桌面端升级后哈希变了，请求自动落回隧道，不会白屏。
- 刷新资源：`node tools/sync-web-assets.mjs [app.asar路径]`，默认读 `D:/dsh/resources/app.asar`。

## 连接速度

手机端经中转首次打开要拉的是**客户端插件 bundle**（约 8.9MB 原始）。之前 bridge 把 `Accept-Encoding` 删掉了，等于全量明文传输；现在：

- 转发浏览器的 `Accept-Encoding`，上游 gzip 后约 4.1MB（2.2x），HTML 需要注入时先解压再处理。
- `/plugins/*`、`/assets/*` 的响应补上 `Cache-Control: immutable`（URL 里带 `rev`），WebView 第二次启动直接命中缓存，不再过网。
- 内置的 4.8MB 壳资源仍然从 APK 读，不走网络。

实测（本机 → 中转）：index 40KB/0.2s，插件 bundle 8.9MB→4.1MB、3.7s，静态 asset 940KB→313KB 且本地已内置。首次装好后第二次冷启动基本只剩 index + API + WebSocket。

## 图片与文件上传

输入框左侧的「添加文件或调用指令」会触发隐藏的 `<input type="file">`；WebView 通过 `onShowFileChooser` 拉起 Android 文件/相册选择器，选中的图片或文件由 DSH 客户端经同一条加密隧道上传到桌面端，然后随消息发给模型。支持多选。

## DSH 设置页（手机布局）

DSH 自己的设置弹窗是 342px 卡片 + 188px 导航列，手机上内容区只剩 ~150px。bridge 注入的布局把它改成：

- 全屏面板，标题 + 横向可滚动标签页（通用设置 / 模型 / 手机访问…）；
- 内容区占满宽度、独立滚动。

## 远程文件与图片

选图后 App 会把文件复制到自己的缓存，并把 `FileProvider` URI 交给页面，避免某些相册/云盘 provider 不授予 WebView 读取权限导致的 `failed to fetch`；下载同理走 `FileProvider` 打开。

## 远程文件

WebView 里点开可下载的文件（zip、docx、pdf 等）时，`setDownloadListener` 会把请求发到本机代理 → 隧道 → 桌面端，下载到 `cache/downloads/`，再用 `FileProvider` + `ACTION_VIEW` 交给手机上的原生应用打开。没有能打开该类型的应用时会提示。

## 配对

1. 电脑：DSH → 设置 → 手机访问 → 中转服务器，填好地址，确认「已连接」。
2. 手机：打开 App →「扫码添加电脑」扫二维码，或「手动输入」粘贴连接码。
3. 绑定后回到「我的电脑」，点「打开」。

连接码格式：

```
dshm://relay?url=relay.example.com%3A9443&room=<32位hex>&key=<base64url 16字节>
```

## 代码结构

| 文件 | 作用 |
|---|---|
| `Tunnel.kt` | 握手、帧编解码、HKDF-SHA256、AES-128-GCM、多路流、断线重连；计数器分配与加密、写帧在同一把锁内，避免并发流乱序导致 GCM 校验失败 |
| `LocalProxy.kt` | `127.0.0.1:8321` 上的 TCP 代理：先解析请求头，内置前端本地返回，其余一条连接 = 一条隧道流；可热切换 Tunnel |
| `HttpHead.kt` | HTTP/1.1 请求头读取与解析（可单测），识别 WebSocket 升级与 Range 请求 |
| `WebAssets.kt` | 内置前端的 MIME 与读取，缺失时回退隧道 |
| `ConnectionService.kt` | 前台服务：持有 Tunnel + LocalProxy、通知、探针、START_STICKY 恢复 |
| `Pairing.kt` | 解析 `dshm://` 连接码 |
| `MainActivity.kt` | 三个屏幕 + 设置弹层、WebView、扫码、权限、返回栈 |

设计稿与实施计划在 [`../mobile-design`](../mobile-design)：`design.md`（tokens/线框/状态）、`plan.md`（架构/验收）、`MainActivity.proposed.kt`（设计侧的参考实现）。

## 测试

```bash
# 协议向量（Kotlin ↔ Node 逐字节一致）
gradle :app:testDebugUnitTest

# 真实中转联调（会连到部署好的 relay）
DSH_ROOM=<room> DSH_KEY=<key> DSH_RELAY_HOST=<relay-host> DSH_RELAY_PORT=<relay-port> \
  gradle :app:testDebugUnitTest --tests "dev.dsh.mobile.RelayIntegrationTest" --rerun-tasks
```

已验证：`ProtocolTest` 5/5；`RelayIntegrationTest` 对着线上中转取回真实 DSH 首页；`assembleDebug` 成功。

**未验证**：没有真机/模拟器，所以扫码、WebView 真机渲染、通知样式和后台保活的实际表现没有跑过；这些需要装到手机上确认。

## 已知限制

- 没有实现 `WebChromeClient.onShowFileChooser`，聊天里的「选择文件」暂不可用。
- 没有多电脑管理：一个 App 只保存一个连接码。
- 明文 HTTP 只发往 `127.0.0.1`，外网方向全部走加密隧道。
