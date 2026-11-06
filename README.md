# DSH Remote (Android)

用手机原生 App 打开电脑上的 DeepSeek Harness。App 内置一个本地 HTTP 代理，把 WebView 的每个
TCP 连接通过 **AES-128-GCM 加密隧道**送到中转服务器，再由桌面端的 `dsh-remote-plugin` 转给本机 DSH：

```
WebView ──http://127.0.0.1:8321──▶ LocalProxy ──Tunnel(AES-GCM)──▶ Relay ──▶ dsh-remote-plugin ──▶ 本机 DSH
```

界面参照 [`april-jk/dsh-mobile`](https://github.com/april-jk/dsh-mobile) 的浅色设计语言
（DMSans / Montserrat / FragmentMono 的系统字体映射，10dp 圆角，海军蓝主按钮）。

## 要求

- Android **8.0（API 26）**及以上。
- 系统 WebView **Chrome 119 或更新**。DSH 的前端用到 `Promise.withResolvers`
  （Chromium 119 引入），WebView 太旧时页面会白屏 —— App 会检测并给出提示，而不是留一张白屏。
- 电脑端装好 [`dsh-remote-plugin`](https://github.com/umeabc/dsh-remote-plugin) 0.2.x。

## 构建

需要 JDK 17+、Android SDK（compileSdk 35）、Gradle 8.14。

```bash
# 工具链可以装在仓库外，脚本会自动接线（幂等，约 800MB）
bash tools/bootstrap-toolchain.sh

bash tools/build-apk.sh                        # debug:  app/build/outputs/apk/debug/app-debug.apk
bash tools/build-apk.sh :app:assembleRelease   # release: app/build/outputs/apk/release/app-release.apk
```

`build-apk.sh` 会自己写 `local.properties`（用正斜杠 —— Java properties 会把反斜杠当转义，
`D:\harness` 会悄悄变成 `D:harness`，症状是 `IOException: 文件名、目录名或卷标语法不正确`）。

Release 构建需要 `signing/keystore.properties` 与对应的 keystore（两者都在 `.gitignore` 里）。
没有它只会产出未签名的包，装不上。

### 签名

```
signing/dsh-remote-release.jks   RSA 4096 / 10000 天 / alias dsh-remote
signing/keystore.properties      storePassword / keyPassword
```

证书 SHA-256（校验下载包是否为同一签名者）：

```
9F:99:FC:3D:47:7B:85:D7:05:01:5A:DE:37:80:CB:B5:A8:A4:E4:05:7A:0D:C8:FD:BF:9B:28:56:0E:C0:B4:89
```

**这把 keystore 丢了就再也签不出能覆盖安装的更新包**，请连同口令一起备份。

```bash
apksigner verify --print-certs dsh-remote-0.7.7.apk
```

### 内置壳资源

`app/src/main/assets/dsh-web/` 里是 DSH 前端（约 5 MB），WebView 直接从 APK 读，第一次打开不用
把整包 JS/CSS 拖过隧道。文件是内容哈希命名的，桌面端升级后哈希变化会自动回落到隧道。

刷新：

```bash
node tools/sync-web-assets.mjs [app.asar 路径]   # 默认 D:/dsh/resources/app.asar
```

## 安装与配对

1. 电脑：DSH → **设置 → 手机访问 → 中转服务器**，填好地址，确认「已连接」。
2. 手机：打开 App →「扫码添加电脑」扫二维码，或「手动输入」粘贴连接码。
3. 回到「我的电脑」，点「打开」。

连接码格式：

```
dshm://relay?url=relay.example.com%3A9443&room=<32位hex>&key=<base64url 16字节>
```

## 界面

| 屏幕 | 内容 |
|---|---|
| **我的电脑** | 已配对电脑卡片（名称、中转地址、在线状态点、上次连接时间）+「打开」；空状态引导扫码；「扫码添加电脑」「手动输入连接码」；右上角设置。 |
| **添加电脑** | 分段控件「扫码绑定 / 手动输入」；扫码页带取景框；手动页支持粘贴 `dshm://` 连接码、行内错误提示；海军蓝「绑定电脑」。 |
| **会话** | 48dp 白色顶栏（返回、设备名称、状态胶囊、刷新、断开、设置）+ 2dp 进度条 + WebView；连接中显示「正在连接电脑…」，电脑未响应/连接断开/页面错误有整屏诊断页和「重试」。 |
| **设置** | 底部弹层：中转地址、脱敏连接码、加密标识、日志、版本、断开连接。 |

会话页的「断开」在刷新按钮右边，走确认框（断开后远端的会话会保留，可随时再点「打开」继续）。

## 后台保活

`ConnectionService` 是 `Tunnel` + `LocalProxy` 的唯一持有者，前台服务 + 常驻通知：

- 心跳：每 20 秒 PING，桌面回显；45 秒没有任何帧就判定连接已死并重连。
- 网络变化：`registerDefaultNetworkCallback` 在默认网络切换时立刻重拨；回到前台若已断开会自动恢复。
- 通知：最低优先级、静音、可划掉，只用来满足系统对前台服务的要求；自带「断开」动作。
- Android 14+ 使用 `specialUse` 前台服务类型，避免 `dataSync` 的时长预算把长连接掐断。

厂商系统仍可能清理后台：把 DSH Remote 设为「不限制」可进一步降低被杀概率（系统策略，App 不申请
忽略电池优化，也不常驻唤醒锁）。

## 传输

- 转发 `Accept-Encoding`（桌面端 gzip 后约为原始体积的 1/2）；`/plugins/*`、`/assets/*` 的响应补
  `Cache-Control: immutable`（URL 带内容哈希），第二次启动直接命中 WebView 缓存。
- 内置壳资源从 APK 读，不走网络。
- 页面里的 `/plugins/events`（客户端 HMR 的 SSE）会长期占一条连接 —— 桌面插件侧已不再限制并发，
  每次开关页面上游都会立即释放。

## 文件

- 输入框左侧「添加文件或调用指令」走 `onShowFileChooser`，支持多选；文件经隧道上传到桌面端。
- WebView 里的下载走 `setDownloadListener` → 隧道 → `cache/downloads/`，再用 `FileProvider`
  交给系统应用打开。

## 代码结构

```
app/src/main/java/dev/dsh/mobile/
  MainActivity.kt      界面、WebView、屏幕切换、诊断
  ConnectionService.kt 前台服务、连接相位、直连切换
  Tunnel.kt            AES-GCM 帧协议
  LocalProxy.kt        回环 HTTP 代理（静态资源从 APK 回放）
  WebAssets.kt         内置壳资源读取
  Pairing.kt           dshm:// 连接码解析
  Instances.kt         已配对电脑的持久化
tools/
  bootstrap-toolchain.sh / build-apk.sh / sync-web-assets.mjs / make-icon.py
```

## 日志

`adb logcat -s DshProxy DshApp DshTunnel DshDirect`，或 App 内 **设置 → 日志**。
`DshProxy` 记录每个请求的方法/路径与隧道状态，`DshApp` 记录连接相位（`snapshot …`）与页面加载。

## 许可

见 `LICENSE`。变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。