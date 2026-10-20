# DSH Remote (Android)

> English summary: a native Android client that opens your desktop **DeepSeek Harness** over an
> end-to-end encrypted relay. It keeps the desktop's web UI, adds a phone layout, caches the shell
> in the APK, and keeps the tunnel alive in a foreground service.

用手机原生 App 打开电脑上的 DeepSeek Harness。连接通过一条 **AES-128-GCM 加密隧道**经过中转服务器，
电脑不需要公网 IP、不需要端口映射、不需要 HTTPS 证书。

```
WebView ──http://127.0.0.1:8321──▶ LocalProxy ──Tunnel(AES-GCM)──▶ Relay ──▶ dsh-remote-relay
                                                                              │
                                                                      127.0.0.1:8319
                                                                      DeepSeek Harness
```

## 功能

- **多台电脑**：每台电脑保存为一条实例，各自有名称、连接码和连接设置；首页点「打开」即可切换。
- **配对简单**：扫电脑上的二维码，或粘贴 `dshm://relay?url=…&room=…&key=…` 连接码。
- **全局设置**：运行日志（App 内查看、复制、清空）与关于（版本、作者、项目主页）。
- **手机布局**：对话全宽、侧栏变覆盖式抽屉、隐藏桌面挂件；DSH 自己的设置页改成顶部标签页 + 满宽内容。
- **首屏加速**：DSH 的 JS/CSS 从 APK 内置资源读取（可选，约 5 MB），插件 bundle 走 gzip + 不可变缓存。
- **后台保活**：`ConnectionService` 前台服务持有隧道与本地代理，20 秒心跳、网络切换自动重拨、回前台自动恢复。
- **文件与图片**：相册/文件选择器上传给模型；远程文件下载后用系统应用打开。
- **诊断**：`adb logcat -s DshProxy DshTunnel` 能看到请求、隧道状态和解密失败。

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）、Gradle 8.14+。

```bash
# 可选：把 DSH 桌面前端打进 APK（不执行也能跑，只是首屏要走隧道）
node tools/sync-web-assets.mjs "/path/to/DeepSeek Harness/resources/app.asar"

gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库里不包含 DSH 的前端产物（属于上游项目），`tools/sync-web-assets.mjs` 只负责从你本机的
安装目录里复制，不会上传。

## 配对

1. 电脑安装 [dsh-remote-plugin](https://github.com/umeabc/dsh-remote-plugin)，打开 **设置 → 手机访问**，确认中转「已连接」。
2. 手机打开 App →「扫码添加电脑」，或「手动输入」粘贴连接码。
3. 回到首页点「打开」。

连接码格式：

```
dshm://relay?url=relay.example.com%3A9443&room=<32位hex>&key=<base64url 16字节>
```

连接码等同于钥匙，不要公开发布。

## 结构

| 文件 | 作用 |
|---|---|
| `Tunnel.kt` | 握手、帧编解码、HKDF-SHA256、AES-128-GCM、多路流、心跳与重连 |
| `LocalProxy.kt` | `127.0.0.1:8321` 本地代理：内置静态资源本地返回，其余原样走隧道 |
| `ConnectionService.kt` | 前台服务：持有隧道/代理由、通知、探针、网络回调 |
| `MainActivity.kt` | 首页 / 配对 / 会话 / 设置四个界面 |
| `HttpHead.kt` | HTTP 请求头解析（可单测） |
| `WebAssets.kt` | APK 内置前端资源 |
| `Instances.kt` | 多实例存储：名称、连接码、当前连接 |
| `AppLog.kt` | 内存日志环（设置页展示、可复制） |

## 安全

- 隧道载荷端到端加密，中转只按 `channel/stream` 转发，看不到内容。
- 手机只持有 App 自己的配对信息；桌面端的 DSH 会话 Cookie 由电脑侧注入，不下发到手机。
- APK 内的明文 HTTP 只允许发往 `127.0.0.1`；配对信息不参与云备份。
- 连接码/房间密钥泄露等同于访问权泄露，可在电脑端「更换房间」作废。

## 已知限制

- 没有实现所有 WebView 文件类型：可下载文件交给系统应用，其他预览依赖 DSH 页面自身。
- 一个 App 只保存一个连接码（单电脑）。
- 国产 ROM 的省电策略仍可能回收前台服务，需要把 App 设为「不限制」。

## 协议与配套

中转协议、部署方式见 [dsh-remote-relay](https://github.com/umeabc/dsh-remote-relay)。
电脑侧插件 [dsh-remote-plugin](https://github.com/umeabc/dsh-remote-plugin) 负责把隧道流转发到本机 DSH，并用配对码/二维码把控访问。

## License

MIT
