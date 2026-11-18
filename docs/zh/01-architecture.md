# 01 · 架构

这个 App 没有「自己实现一套网关」——它只做三件事：**把引擎进程拉起来、让它活着、
把它的界面显示出来**。所以架构的重点不在代码分层，而在进程与 Android 生命周期的交界处。

## 1. 进程模型

整个 App 只有一个进程 `ai.usemagpie.android`。`Magpie.java` 里的状态全是
`static`，Service 与 Activity 共享同一份——不跨进程，不 AIDL，不做绑定。

```
MainActivity ──┐
               ├── 同一个进程、同一份静态状态 ── Magpie（引擎进程管理器）
MagpieService ─┘                                      │
                                        ProcessBuilder │ fork+exec
                                                       ▼
                                        lib/arm64-v8a/libmagpie.so
                                        （独立进程，Go runtime）
```

引擎是**子进程**，不是 library。它有自己的 pid、自己的 Go runtime、自己的
goroutine 调度；宿主通过 `stdout/stderr` 管道读它的日志，通过 `Process.waitFor()`
感知它挂了，通过 TCP connect 判断它「起来了」。

## 2. 状态机

```
        start()                probe(3430) 成功
STOPPED ────────▶ STARTING ──────────────────▶ RUNNING
   ▲                  │                          │
   │                  │ 进程退出                  │ 进程退出（非主动停止）
   │                  ▼                          ▼
   └──── stop() ─── STOPPED                    ERROR
```

- `STARTING → RUNNING` 的判定是**真的能连上 3430 端口**，不是「进程还活着」。
  引擎从 exec 到监听端口要 1–3 秒，`watch()` 每 400 ms 探一次，最多 48 秒。
- `ERROR` 与 `STOPPED` 的区别在 `stopWanted` 标志：用户点停止导致的退出是
  `STOPPED`，意外退出是 `ERROR` 并记下退出码。
- 重复 `start()` 是幂等的（`state == RUNNING || STARTING` 直接返回）。

## 3. 端口

| 端口 | 用途 | 谁监听 | 鉴权 |
| --- | --- | --- | --- |
| `127.0.0.1:3425` | 网关（`/v1/*`，OpenAI / Anthropic / Gemini / Responses） | 引擎 | `Authorization: Bearer magpie` |
| `127.0.0.1:3430` | 控制台（magpie 自己的网页 + `/api/*`） | 引擎 | `?k=<webKey>` → Cookie |

两个端口都只绑回环，不对外。网关的口令是**字面量 `magpie`**——这是 magpie 桌面版
给 agent 用的默认值，不是秘密；真正拦住外部访问的是「只监听 127.0.0.1」。
控制台的 `webKey` 是首次启动时 `SecureRandom` 生成的 32 位 hex，存在
SharedPreferences 里，重启不变。

控制台鉴权链路：

```
WebView.loadUrl("http://127.0.0.1:3430/?k=<webKey>")
        │
        ▼  引擎校验 k，匹配则
   303 redirect + Set-Cookie: magpie_web=<...>
        │
        ▼  WebView 自动带上该 Cookie
   GET /api/state → 200（14 KB 真实状态）
```

这条链路是 App 能在 WebView 里「直接操作任何功能」的前提，也是为什么
`?k=` 必须由宿主拼好再 load，而不是让用户在浏览器里手输。

## 4. 引擎进程的拉起方式

```java
cmd = [ nativeLibraryDir/libmagpie.so, "serve", "--web=127.0.0.1:3430" ]
cwd = filesDir/home
```

为什么是 `libmagpie.so` 而不是 `magpie`：Android 只允许执行 APK 释放到
`nativeLibraryDir` 的文件（详见 [03-android-porting.md](03-android-porting.md)）。
文件名带 `.so` 后缀不影响 `execve`——内核不看扩展名，只看 ELF 头和可执行位。
但要注意 `dlopen` 的路径规则完全不同，所以**不能**把这个文件当共享库加载。

## 5. 环境变量

宿主只覆盖 magpie 需要的，**保留系统环境**——Android 的 DNS 解析器和
`__system_property_get` 依赖 `ANDROID_*` / `PATH` 等变量，清空它们会让引擎
起得来但不能上网。

| 变量 | 值 | 作用 |
| --- | --- | --- |
| `HOME` | `files/home` | magpie 的配置根目录 |
| `XDG_CONFIG_HOME` | `files/home/.config` | `settings.json` / `providers.json` / `usage.jsonl` |
| `XDG_CACHE_HOME` | `files/home/.cache` | models.dev 缓存、模型列表 |
| `XDG_DATA_HOME` | `files/home/.local/share` | 数据目录 |
| `TMPDIR` | `cache/tmp` | Go 的临时文件 |
| `MAGPIE_ADDR` | `127.0.0.1:3425` | 网关监听地址 |
| `MAGPIE_WEB_KEY` | 32 位 hex | 控制台口令 |
| `MAGPIE_BUN` | `lib/arm64-v8a/libbun.so` | 插件运行时（跳过下载与 W^X 检查） |
| `MAGPIE_MIRRORS` | `on` | 打开国内镜像回退 |
| `MAGPIE_NPM_REGISTRY` | `https://registry.npmmirror.com` | 插件 npm 源 |
| `MAGPIE_GITHUB_MIRROR` | `https://gh-proxy.com` | GitHub raw/release 镜像 |
| `DO_NOT_TRACK` / `DISABLE_AUTOUPDATER` | `1` | 关遥测、关自更新 |
| `TZ` | 系统时区 | 日志与用量统计的时间戳 |

首次启动还会写一份 `settings.json`：

```json
{"noAutoUpdate":true,"noAutoDownload":true,"lang":"zh",
 "chinaMirror":true,"npmRegistry":"https://registry.npmmirror.com"}
```

只在文件不存在时写，之后**不覆盖**——用户改了就是改了。

## 6. 前台服务

| 项 | 值 |
| --- | --- |
| 类型 | `foregroundServiceType="specialUse"`（Android 14+ 用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 启动） |
| 声明 | `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 写明「跑用户的本地模型网关」 |
| 通知 | 渠道 `magpie-gateway`，`IMPORTANCE_LOW`，常驻、不显示时间戳 |
| WakeLock | `PARTIAL_WAKE_LOCK`，非引用计数，Service 销毁时释放 |
| 返回值 | `START_STICKY`（被系统杀掉后尝试重建） |

为什么必须前台服务：Android 8+ 对后台进程的处理是「随时可能被冻结或杀掉」。
网关一旦被冻结，dsh-go 的请求就会超时——而这类超时在 dsh-go 侧表现为
「模型无响应」，极难归因。前台服务 + WakeLock 是唯一能保证「息屏还活着」的组合。

即便如此，国产 ROM 的省电策略仍可能越过这套机制，所以界面上保留了
「电池优化白名单」开关（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。

## 7. 开机自启

`BootReceiver` 接两个广播：

- `BOOT_COMPLETED`：开机后若 SharedPreferences 里 `autostart == true`，拉起服务
- `MY_PACKAGE_REPLACED`：App 被覆盖安装后重新拉起（避免升级后服务消失）

不监听 `USER_PRESENT` / `SCREEN_ON` 之类的高频广播——那会被系统判为滥用。

## 8. 日志

三层，各有用处：

1. **内存环形缓冲**：最近 800 行，界面日志区实时显示（`ArrayDeque`，每次重绘读快照）。
2. **磁盘追加**：`files/magpie.log`，用于事后排查（无轮转，需要时手动清）。
3. **logcat**：Java 侧不主动打点，避免和引擎日志混在一起。

`pump()` 线程把引擎的合并流（`redirectErrorStream(true)`）逐行读出来，
所以 Go 的 panic traceback 和普通日志顺序一致，不会错乱。

## 9. 代码地图

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `Magpie.java` | 422 | 路径初始化、`settings.json` 播种、webKey、进程启停、状态机、日志、版本、设备信息 |
| `MainActivity.java` | 568 | 原生界面（状态/控制/参数/开关/日志）、WebView 控制台、dsh-go 接入弹窗、权限请求 |
| `MagpieService.java` | 117 | 前台服务、通知、WakeLock、启停入口 |
| `BootReceiver.java` | 24 | 开机 / 覆盖安装后自启 |

四个文件加起来约 1.2 k 行，没有第三方依赖——这是刻意的：**依赖越少，越不会因为
Android 版本升级而碎**。代价是界面全手写（没有 RecyclerView、没有 Material 组件），
但界面本来就只有一屏。

## 10. 为什么不用 Gradle

不是偏好问题，是环境问题：构建机上没有可用的 Gradle 分发（网络受限），
而 `aapt2 + javac + d8 + apksigner` 这条链路是 Android 构建的**全部实质步骤**，
Gradle 只是把它们编排起来。手写脚本带来两个好处：

- 每一步都能单独调试（`failing step` 立刻可见，不用读 Gradle 的千行堆栈）；
- 打包这一步能完全控制 ZIP 条目的压缩方式——这正是
  [`resources.arsc` 那个坑](03-android-porting.md)的解法所在。

代价是要自己管 SDK 路径与版本探测，`build.ps1` 里的前 40 行就是在做这件事。
