# Changelog

格式：日期 / 版本 / 变更。`versionCode` 在括号里。

## 1.0.0 — 2026-11-18 (versionCode 1)

首次发布。含完整引擎、原生宿主，以及让它在真机上「装得上、跑得起来、登录得了」
的四项关键修复。

### 引擎与宿主

- 引擎：上游 `magpie` @ `50c5a52`（`VERSION` v0.1.4386）交叉编译为 `android/arm64`，
  NDK r28c、`CGO_ENABLED=1`、`-tags nogui`、`-trimpath`、`-s -w`。**源码未改**。
- 宿主：`Magpie`（进程/环境/日志/状态机）、`MagpieService`（前台服务 + WakeLock）、
  `MainActivity`（原生界面 + WebView 控制台）、`BootReceiver`（开机自启）。
  四个文件约 1.2 k 行，无 Gradle、无第三方依赖。
- 网关 `127.0.0.1:3425`，控制台 `127.0.0.1:3430`（magpie 自己的网页，未裁剪）。
- dsh-go 接入：PRoot 不做网络命名空间隔离，两个 App 共享回环，加一个
  OpenAI 兼容渠道即可（Base URL `http://127.0.0.1:3425/v1`，Key `magpie`）。

### 安装与运行的关键修复（本版已包含）

- **`resources.arsc` 必须是 STORED**。原先用 .NET 的
  `CompressionLevel.NoCompression` 打包，它实际仍写 DEFLATE（level 0），
  Android 11+ 在解析阶段直接拒装，报「与系统不兼容」。改为 Python `zipfile`
  真 STORED（`app/pack.py`）。
- **Bun 不能放在应用私有目录**。Android 10+ 的 W^X 禁止 execve 那里的文件
  （`fork/exec .../magpie-bun: permission denied`）。改为内置
  `lib/arm64-v8a/libbun.so` + `MAGPIE_BUN`。
- **WebView 会吞掉 `window.open`**。订阅登录因此「点了没反应」，看起来像只能填
  API Key。改为 `onCreateWindow` 交给系统浏览器 + 注入 shim（返回占位 window
  对象，返回 `null` 会触发 magpie 的误判）。
- **国内网络 TLS 超时**。默认打开 `chinaMirror` + npmmirror + `MAGPIE_MIRRORS=on`。

### 其它

- 图标为 mdpi–xxxhdpi 五套 PNG（源自上游 magpie 的 app 图标，MIT），
  以 base64 存放在 `scripts/gen-icons.py`，构建时自动生成。
- 补 `uses-feature` 声明（`touchscreen` / `wifi` / `screen.portrait`，全部 `required=false`）。
- `.so` 压缩存放（`extractNativeLibs=true`，安装时解压到 `lib/`），
  体积 92.2 MB → 69.9 MB。

### 产物

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `magpie.apk` | 73,258,111 | `7290ca1ffda41631e6a6e71ced8b8ed0618709f3ceceb6f9e92831fd8a7ada7e` |
| `magpie-mini.apk` | 50,162 | `912d0ee228aa4146ab464edafe1942aab382b375787768a00e737fc44745742a` |

签名证书 SHA-256：`7ec5f3b3c68605f70001a11b98aba8e75137b60ccf688efce70b4b38c7e7076f`。
