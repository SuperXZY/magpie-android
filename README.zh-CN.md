# magpie for Android

把 [yetone/magpie](https://github.com/yetone/magpie) 原封不动地搬到手机上：
上游 Go 引擎交叉编译成 `android/arm64`，由前台服务拉起，原生 Java 界面管理，
自带的 Web 控制台嵌进 WebView。目标是让手机上的 **dsh-go** 能像桌面上的
Claude Code / Codex 那样，从一个本地网关拿到任意的模型渠道。

```
手机
├── magpie（本项目）      127.0.0.1:3425  ← 网关（OpenAI / Anthropic / Gemini / Responses）
│                         127.0.0.1:3430  ← 控制台（桌面版同一个网页）
└── dsh-go                添加一个 OpenAI 兼容渠道 → Base URL 填 127.0.0.1:3425/v1
```

| | |
| --- | --- |
| 引擎 | 上游 `magpie` **v0.1.4722**（`3ad5454`），源码未改 |
| 宿主 | 7 个 Java 文件，约 2.5 k 行，零第三方依赖，不用 Gradle |
| 产物 | `magpie.apk`，76.6 MB，`minSdk 26` / `targetSdk 34` / 仅 `arm64-v8a` |
| 包名 | `ai.usemagpie.android`，`versionCode 5`（1.1.0） |

## 目录

- [为什么需要它](#为什么需要它)
- [它是什么，不是什么](#它是什么不是什么)
- [架构](#架构)
- [快速开始](#快速开始)
- [接入 dsh-go](#接入-dsh-go)
- [界面](#界面)
- [四个 Android 专属的坑](#四个-android-专属的坑)
- [自己构建](#自己构建)
- [验证记录](#验证记录)
- [已知限制](#已知限制)
- [文档索引](#文档索引)
- [致谢与免责](#致谢与免责)

## 为什么需要它

magpie 桌面版做的事很朴素：**把一堆 agent 的模型出口统一到一个本地端口**，
再由它路由到 DeepSeek / Kimi / GLM / Claude / GPT / 本地模型，失败自动换账号。

在手机上，dsh-go 是一个 PRoot 沙箱里的 Ubuntu（798 MB rootfs、Node 24、dsh CLI），
它只能通过「添加渠道」拿到模型——而这正好是 magpie 网关的接口形状。
麻烦只有两点：

1. 手机上没有 magpie，官方只发桌面版（macOS / Windows / Linux）；
2. Android 的进程、文件、网络规则和桌面完全不同，直接丢一个二进制进去跑不起来。

于是有了这个仓库：**不重写 magpie，只给它造一个能活在 Android 上的壳**。

## 它是什么，不是什么

**是**

- 一个 Android 宿主：拉起 magpie 引擎、保住它的前台服务、把它的控制台塞进 WebView。
- 一份踩坑记录：CGO、W^X、`resources.arsc`、`window.open`——这四个坑每一个都会
  让「看起来很对」的 APK 装不上或跑不起来，`docs/zh/03-android-porting.md` 全写了。
- 一份可复现的构建流程：从上游源码到签名 APK，命令逐条可抄。

**不是**

- 不是 magpie 的 fork。本仓库不含任何 magpie 源码，引擎在构建时从上游取。
- 不是官方发布。magpie 官方不支持 Android，遇到问题请提到**这个**仓库。
- 不是一个「重新实现的模型客户端」。没有 magpie 的引擎，这个壳没有意义。

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ app 进程（单进程，Service 与 Activity 共享静态状态）            │
│                                                              │
│  MainActivity            原生界面：状态灯 / 启停 / 复制参数    │
│    └─ WebView            → http://127.0.0.1:3430/?k=<webKey>  │
│  MagpieService           前台服务 + PARTIAL_WAKE_LOCK         │
│  BootReceiver            BOOT_COMPLETED / MY_PACKAGE_REPLACED │
│  Magpie                  进程/路径/环境/日志（静态工具类）      │
└───────────────┬──────────────────────────────────────────────┘
                │ ProcessBuilder → nativeLibraryDir/libmagpie.so
                ▼
┌──────────────────────────────────────────────────────────────┐
│ libmagpie.so（Go，CGO，aarch64-linux-android26）              │
│   serve --web=127.0.0.1:3430                                  │
│   MAGPIE_ADDR=127.0.0.1:3425                                  │
│   HOME=<filesDir>/home   MAGPIE_BUN=<lib/libbun.so>           │
└───────────────┬──────────────────────────────────────────────┘
                │ 出站 HTTPS
                ▼
        DeepSeek / Kimi / GLM / Claude / GPT / Ollama / …
```

关键决定：**引擎必须从 `nativeLibraryDir` 执行**。Android 10+ 的 W^X 禁止
`execve` 应用私有目录里的文件，`/data/data/<pkg>/lib/` 是唯一可执行的落点，
所以引擎以 `lib/arm64-v8a/libmagpie.so` 的形式随 APK 分发。

运行时布局：

```
/data/data/ai.usemagpie.android/
├── files/
│   ├── home/                         HOME
│   │   ├── .config/magpie/           settings.json / providers.json / usage.jsonl
│   │   └── .cache/magpie/            models.dev 缓存、模型列表
│   └── magpie.log                    引擎 stdout/stderr（追加）
├── cache/tmp/                        TMPDIR
└── lib/arm64-v8a/                    ← 可执行，引擎与 Bun 都在这里
```

## 快速开始

### 1. 装

从 [Releases](../../releases) 下载 `magpie.apk`，装到 **arm64 的 Android 8.0+** 手机。

> 装之前确认两点：手机是 64 位（2017 年后的机型基本都是），剩余空间 **≥ 400 MB**
> （APK 70 MB，装完占 175 MB，安装峰值约 350 MB）。空间不足时安装器报的也是
> 「与系统不兼容」，很容易误判——详见 `docs/zh/06-troubleshooting.md`。

### 2. 起

打开 magpie → 状态灯变绿即网关就绪。第一次启动会在后台：
写 `settings.json`（关自更新、开国内镜像）、生成控制台密钥、拉起引擎。
给「电池优化白名单」和「开机自启」两个开关放行，否则某些 ROM 会在息屏后杀掉它。

### 3. 配渠道

点「打开控制台」→ **Providers → 加一个渠道**。两条路：

- **订阅登录**：Claude / ChatGPT / Copilot / WorkBuddy / Gemini CLI，点登录会在
  系统浏览器打开授权页（回调走本机回环端口）。
- **API Key**：DeepSeek / Kimi / GLM / MiniMax / Qwen / OpenRouter / Ollama…

配完在控制台的 Agent 页能看到模型列表，`/v1/models` 就有了内容。

### 4. 接 dsh-go

主界面「接入 dsh-go」会给出该填的两个值，然后见下一节。

## 接入 dsh-go

dsh-go 的引擎跑在同一台手机的 PRoot 里，而 PRoot **不做网络命名空间隔离**，
所以它里面的 `127.0.0.1` 就是手机的 `127.0.0.1`——两个 App 天然共享回环。
这也是整套方案成立的前提。

在 dsh-go 里：

1. 设置 → 模型 → 添加渠道
2. 类型选 **OpenAI 兼容**
3. Base URL：`http://127.0.0.1:3425/v1`
4. API Key：`magpie`
5. 点「获取模型」→ 勾选 → 保存

之后 dsh-go 的对话就能选到 magpie 背后的任何模型；额度耗尽或上游报错时，
magpie 会按路由组策略自动换下一个账号/渠道，dsh-go 侧无需感知。

细节（含 `cordis.patch.yml` 长什么样、为什么要这么填）：`docs/zh/04-dsh-go-integration.md`。

## 界面

原生壳只有一屏，信息密度按「一眼看出它在不在跑」设计：

| 区域 | 内容 |
| --- | --- |
| 状态 | 状态灯 + 运行时长 + 引擎版本 + 当前机型/系统/ABI |
| 控制 | 启动 / 停止 / 重启 |
| 参数 | 网关地址、API Key、控制台链接，各带一个复制按钮 |
| 接入 | 「接入 dsh-go」弹窗，给出 Base URL / Key，可跳 `dshgo://open/models` |
| 开关 | 开机自启、电池优化白名单 |
| 日志 | 引擎实时输出（最近 800 行），落盘在 `files/magpie.log` |

控制台是 magpie 自己的网页（`app.js` 1.3 MB，自带 `max-width: 600px` 断点），
不重写、不裁剪，所以桌面版有什么功能，手机上就有什么功能。

## 四个 Android 专属的坑

这四个坑的共同点是：**构建全部通过，装机/运行才炸**。

### 1. 引擎不能用纯 Go 编译

`GOOS=android` 时 Go 的 `net` 包默认走 cgo 解析器，纯 Go 构建出来的二进制
能启动、能监听端口，但**任何域名解析都失败**——表现是「网关起来了，加渠道就超时」。
必须用 NDK 的 `aarch64-linux-android26-clang` + `CGO_ENABLED=1`。

### 2. Bun 不能放在应用私有目录

插件系统靠 Bun。magpie 默认把 Bun 下到 `~/.cache/magpie/bun/<ver>/`，
Android 10+ 会直接拒绝执行：

```
fork/exec .../files/home/.cache/magpie/bun/1.4.2/magpie-bun: permission denied
```

解法是把 `bun-linux-aarch64-android` 打进 `lib/arm64-v8a/libbun.so`，
用 `MAGPIE_BUN` 指过去。校验和与 magpie 源码里 pin 的完全一致。

### 3. `resources.arsc` 必须 STORED

Android 11+ 拒绝安装资源表被压缩的 APK，报错是那句含糊的
「与系统不兼容」。而 **.NET 的 `CompressionLevel.NoCompression` 仍然写 DEFLATE
（level 0）**，条目甚至更大：

```
resources.arsc  DEFLATE  raw=1904  zip=1909   ← 装不上
resources.arsc  STORED   raw=1904  zip=1904   ← 正确
```

所以打包这一步不能用 `System.IO.Compression`，得用 Python 的 `zipfile`
（`app/pack.py` 就是干这个的，别删）。

### 4. WebView 会吞掉 `window.open`

订阅登录走 `window.open(url, "_blank")`。WebView 默认丢弃新窗口，
而 magpie 的 `openSignInPage` 把返回 `null` 判定为「弹窗被拦」，
于是点了没反应——用户看到的是「这里只能填 API Key」。

解法两层：`onCreateWindow` 把 URL 交给系统浏览器；`onPageFinished` 注入 shim
把 `window.open` 改调宿主并返回一个占位 window 对象（返回 `null` 会触发误判）。

> 完整原理、错误原文、以及每个坑的验证方法：`docs/zh/03-android-porting.md`。

## 自己构建

前置：**JDK 17**、**Android SDK**（build-tools + 任一 platform）、**Go 1.27+**、
**Android NDK r28+**、**Python 3**。

```powershell
# 1) 编引擎（从上游源码，需要先 clone yetone/magpie）
.\scripts\build-engine.ps1 -Magpie D:\src\magpie -Out app\jniLibs\arm64-v8a

# 2) 打 APK
$env:ANDROID_SDK_ROOT = 'C:\Android\sdk'; $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\build.ps1

# 只想验证界面/资源/JAVA 能编过（不需要引擎，产物约 50 KB）
.\build.ps1 -NoEngine
```

`build.ps1` 不依赖 Gradle：`aapt2 compile → aapt2 link → javac → d8 → pack.py
→ zipalign -P 16 → apksigner(v1+v2+v3)`。工具链版本自动探测，路径全部可传参，
密钥缺失时自动生成一把开发用的（**不要用它发版**）。

Linux / macOS 的等价命令、`libbun.so` 的下载与校验、CGO 环境变量逐条说明：
`docs/zh/02-build.md`。

## 验证记录

能实测的都实测了；不能实测的明确标注为「未验证」。

| 项 | 方法 | 结果 |
| --- | --- | --- |
| 22 个读接口（`/api/state` `/providers` `/groups` `/usage` `/plugins` `/sessions`…） | 起引擎逐个打 | 全 200 |
| 写接口（settings / provider save·test·key·on·off·delete / groups / plugins） | 同上 | 全 200 |
| `provider/test` 打真实上游 | 假 key | 上游 401 —— 证明请求真的发出去了 |
| 插件安装 `@magpie-community/opencode-workbuddy-auth` | 真装 | 成功，0.2.36 |
| 插件三种登录方式 | 调接口 | OAuth×2 + API×1，返回 154 字符授权 URL |
| 订阅登录授权 URL | 调 `/api/signin` | Claude 447 / Codex 454 / Gemini 546 字符 |
| **端到端** `/v1/chat/completions` | 网关 → mock 上游 | 200，`pong from mock`，usage in=5 out=3 |
| `/v1/models` | — | 正确列出渠道模型 |
| Anthropic `/v1/messages` → OpenAI 上游 | 抓上游请求 | 收到 `POST /v1/chat/completions`，协议翻译正确 |
| `window.open` shim | Node vm 里跑真实的 `openSignInPage` | URL 正确交给宿主 |
| 控制台移动端渲染 | Chrome headless 412×915@2x | Agent / 供应商 / 设置页均正常，中文+暗色+底部导航齐全 |
| APK 结构 | `aapt2 dump badging`、`apksigner verify`、`zipalign -c -P 16` | minSdk 26 / target 34 / arm64-v8a；v1+v2+v3 通过；16 KB 页对齐通过 |
| **真机运行** | — | **未验证**（构建机无 Android 设备，`adb devices` 为空，Hyper-V 未启用） |

未验证的就三项，都是只有真机能验的：界面实际观感、订阅登录回调、厂商 ROM 下的保活。
完整方法与原始输出：`docs/zh/05-verification.md`。

## 已知限制

- **仅 arm64-v8a**。32 位设备装不上（会报 `INSTALL_FAILED_NO_MATCHING_ABIS`）。
  加 32 位要再编一份 `armeabi-v7a` 引擎，体积翻倍，暂不做。
- **体积 69.9 MB**，其中引擎 92.1 MB、Bun 82.8 MB（压缩后共 69.9 MB）。
  不用插件可以去掉 Bun，能降到 ~36 MB，需要自己改 `pack.py`。
- **没有真机验证过保活**。国产 ROM 建议手动加白名单，否则息屏后可能被清。
- **不支持自更新**。magpie 的更新器在 Android 上没有可替换的安装包，已关闭。
- **隐私掩码的本地模型未内置**（1.6 GB 权重），需要自己在控制台里配。
- **引擎版本号硬编码**在 `Magpie.java` 的 `version()`（`0.1.4384`），
  上游源码已到 `v0.1.4386`，两边不同步，只影响界面显示。
- **不做多用户/多实例**。magpie 的配置目录是固定的，两个实例会抢同一份 `settings.json`。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/zh/01-architecture.md`](docs/zh/01-architecture.md) | 进程模型、端口、环境变量、目录布局、控制台鉴权链路 |
| [`docs/zh/02-build.md`](docs/zh/02-build.md) | 从上游源码到签名 APK 的完整流程与工具链要求 |
| [`docs/zh/03-android-porting.md`](docs/zh/03-android-porting.md) | 四个坑的完整原理、错误原文与验证方法 |
| [`docs/zh/04-dsh-go-integration.md`](docs/zh/04-dsh-go-integration.md) | dsh-go 侧配置、`cordis.patch.yml` 结构、排错 |
| [`docs/zh/05-verification.md`](docs/zh/05-verification.md) | 验证矩阵、复现命令、未验证项清单 |
| [`docs/zh/06-troubleshooting.md`](docs/zh/06-troubleshooting.md) | 装不上 / 起不来 / 插件失败 / 登录无反应的排查树 |
| [`docs/zh/07-reverse-engineering.md`](docs/zh/07-reverse-engineering.md) | dsh-go 包结构、PRoot 运行时、dsh 配置位置（互操作性分析） |
| [`docs/zh/08-roadmap.md`](docs/zh/08-roadmap.md) | 待办：32 位、瘦身、真机验证、CI |
| [`docs/zh/09-in-app-update.md`](docs/zh/09-in-app-update.md) | 应用内更新：为什么整包、校验与安装链路 |

## 致谢与免责

- 引擎、网关、控制台、协议翻译全部来自 [yetone/magpie](https://github.com/yetone/magpie)
  （MIT，© 2026 yetone）。这个仓库贡献的只有一个 Android 壳。
- Bun 来自 [Oven](https://bun.sh)（MIT）。`dsh-go` 是第三方应用，本项目
  **不分发、不修改**它，只做互操作性说明。
- 本项目**非官方**。magpie 上游明确不接受外部 PR，所以**请不要把这里的问题
  提到 upstream**——upstream 没有 Android 支持，也没有义务支持。
- 协议与第三方声明见 [`NOTICE`](NOTICE)。使用本项目意味着你自行承担
  账号、额度与合规责任：接入订阅账号时请遵守对应厂商的服务条款。
