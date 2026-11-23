# Changelog

格式：日期 / 版本 / 变更。`versionCode` 在括号里。

## 1.1.0 — 2026-11-23 (versionCode 5)

引擎跟上电脑版，并加入应用内实时更新。

### 引擎升级 v0.1.4386 → v0.1.4722

上游六天推了 **326 个版本**，源码 diff 1304 个文件 / +10.5 万行。对本机有意义的变化：
配额耗尽的第三方模型会被网关自动暂停到配额重置；429 时先等一会再问下一个组员；
流式响应缺少内容/结束原因时按失败处理而不再静默当成功；GLM-5.4 支持图像输入、
GLM Coding Plan 从计划自己的目录取模型；`/v1/responses/compact` 可转发；
嵌入与重排模型进入网关与 GUI；插件可拿到调用方（`ctx.caller`）。

引擎产物 92.1 MB → **106.8 MB**，APK 69.9 MB → **76.6 MB**。
`?k=` 换 cookie 的登录链路不变（cookie 名带上了端口，有效期 400 天）。

### 应用内更新

「版本与更新」卡：显示本包版本、内置引擎版本、上游最新版本，一键检查 / 下载 / 安装。

- 为什么整包更新：引擎在 `lib/` 里，安装后只读，W^X 不允许别处执行 → 无法热替换
  （见 [09-in-app-update.md](docs/zh/09-in-app-update.md)）
- 下载后按 release 资产自带的 `digest` 校验 SHA-256，不一致直接删除、不安装
- 用 `PackageInstaller.MODE_FULL_INSTALL` 交给系统安装器：不需要 root、不需要
  FileProvider、不申请存储权限
- 新增权限 `REQUEST_INSTALL_PACKAGES`；首次安装未知应用需用户授权一次
- 每 6 小时静默自检，避免撞 GitHub API 的 60 次/小时限制

### 宿主（1.0.1 → 1.0.3 期间补齐，本版合并发布）

- 悬浮监控挂件（`FloatingMonitor`）：可见窗口让进程保持 visible 优先级，顺带显示
  实时状态；需要 `SYSTEM_ALERT_WINDOW`
- 保活广播（`KeepAliveReceiver`）+ `SCHEDULE_EXACT_ALARM`：服务被杀后能立刻顶回来
- 主界面的卡片分组、底部固定操作条、控制台全屏覆盖层

### 产物

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `magpie.apk` | 76,559,638 | `df7e30aea4c8abc1b9770cbde9a39a6d803cbbfc10061f90c6516555206e4afd` |

签名证书不变：`7ec5f3b3c68605f70001a11b98aba8e75137b60ccf688efce70b4b38c7e7076f`，
可从 1.0.x 覆盖升级。

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
