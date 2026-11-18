# 03 · Android 移植：四个坑

这四个坑的共同点：**构建全绿，装上去才炸**。而且症状都指向错误的方向——
「装不上」看着像签名问题、「加渠道超时」看着像网络问题、「登录没反应」看着像
前端 bug。本文把每个坑的错误原文、真实原因、判定方法和解法都写清楚。

按踩到的顺序排列，也是排查时应该检查的顺序。

---

## 坑 1 · 引擎不能用纯 Go 编译

### 症状

APK 装得上，引擎也起来了（状态灯变绿、端口在监听），但一加渠道就超时：

```
provider test failed: dial tcp: lookup api.deepseek.com: no such host
```

或者更隐蔽：`/v1/models` 有内容（来自缓存），但真发请求就挂。

### 原因

Go 有两个 DNS 解析器：

| 解析器 | 触发条件 | 读什么 |
| --- | --- | --- |
| `netgo`（纯 Go） | `CGO_ENABLED=0`，或 `-tags netgo` | `/etc/resolv.conf`、`/etc/hosts` |
| `cgo`（libc） | `CGO_ENABLED=1` 且默认 | 系统的 `getaddrinfo` |

Android 上没有 `/etc/resolv.conf`——DNS 走 `netd`，只能通过 bionic 的
`getaddrinfo` 问。而 `GOOS=android` 时 Go 的默认选择本来就是 cgo 解析器，
**但前提是这次构建真的带了 cgo**：`CGO_ENABLED=0` 会静默 fallback 到 netgo，
不报任何错，编出来的二进制也完全合法。

于是你得到一个「能启动、能监听、解析不了任何域名」的引擎。

### 判定

```bash
# 看二进制里有没有 cgo 解析器
strings libmagpie.so | grep -c 'netgo'        # 0 或很小 = 没带 netgo（好）
nm -D libmagpie.so 2>/dev/null | grep -c getaddrinfo   # >0 = 用了 libc 的解析器（好）
```

更直接的办法：在手机上跑 `magpie serve`，看日志第一行有没有
`lookup ... no such host`。

### 解法

```powershell
$env:CGO_ENABLED = '1'
$env:CC  = "$Ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
$env:CXX = "$Ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd"
go build -tags nogui ...
```

`-tags nogui` 也必须带上：Wails 的 GUI 会把 WebKit 链接进来，交叉编译直接失败。

### 顺带一个坑中坑

`GOOS=android` 时 Go 读系统 CA 证书走 `/system/etc/security/cacerts`
（`crypto/x509/root_linux.go` 有专门的 android 分支）。所以**不要**为了「减小体积」
去 `-tags no_cgo` 或用 `GODEBUG=x509usefallbackroots=1`，那会让所有 HTTPS 变成
`x509: certificate signed by unknown authority`。

---

## 坑 2 · Bun 不能放在应用私有目录

### 症状

插件安装失败，错误原文：

```
bun add @magpie-community/opencode-workbuddy-auth@latest: fork/exec
/data/user/0/ai.usemagpie.android/files/home/.cache/magpie/bun/1.4.2/magpie-bun:
permission denied
```

注意是 `permission denied`，不是 `not found`——文件在，权限位也对，就是不让执行。

### 原因

Android 10（API 29）起，SELinux 策略禁止 App 从 `/data/data/<pkg>/` 下
`execve` 任何文件（W^X 策略）。可用位置只剩两个：

| 位置 | 可执行 | 说明 |
| --- | --- | --- |
| `/data/data/<pkg>/lib/`（`nativeLibraryDir`） | ✅ | 安装时由 PackageManager 释放，标了 `exec` 上下文 |
| `/data/data/<pkg>/files/`、`cache/` | ❌ | 就是这里被拒 |
| `/data/local/tmp/` | ⚠️ | 需要 `run-as` 或 root，普通 App 写不进去 |

magpie 默认把 Bun 下到 `$HOME/.cache/magpie/bun/<版本>/`——正是被禁的那一类。

### 解法

把 Bun 的 `bun-linux-aarch64-android` 构建打进 APK：

```
app/jniLibs/arm64-v8a/libbun.so     ← 就是 bun 二进制，只是改了名字
```

运行时 `MAGPIE_BUN=<nativeLibraryDir>/libbun.so`，magpie 就直接用它，不再下载。
`lib/` 在安装时被释放，具有可执行上下文。

### 为什么改名叫 `.so` 还能执行

`execve` 只看 ELF 头和 `x` 位，不看扩展名。但反过来不成立：**不能**用
`System.loadLibrary("bun")` 去 `dlopen` 它——`dlopen` 要求它是合法的共享库
（有 `.dynsym`、无 `PT_INTERP` 之类），而 Bun 是可执行文件。两条路径互不通用。

### 判定

```bash
adb shell run-as ai.usemagpie.android ls -l lib/
# -rwxr-xr-x ... libbun.so      ← 有 x 位，且在 lib/ 下
adb shell run-as ai.usemagpie.android ./lib/libbun.so --version
# 1.4.2                          ← 能直接跑，说明可执行
```

---

## 坑 3 · `resources.arsc` 必须 STORED

### 症状

点安装，系统弹「**与系统不兼容**」，装不上。没有更多信息，`adb install` 也只给
`INSTALL_PARSE_FAILED_*` 或干脆 `INSTALL_FAILED_INVALID_APK`。

### 原因

Android 11（API 30）起，`resources.arsc` 必须**以 STORED（不压缩）方式**存放在
ZIP 里。压缩过的资源表在安装器解析阶段就被拒绝——因为系统要 `mmap` 它，
不能先解压到内存。

### 为什么这个坑特别难找

因为 **.NET 的 `CompressionLevel.NoCompression` 不是 STORED**。它写的是一个
DEFLATE 流，只是 level 0——数据没变小，但压缩方法字段仍然是 8（DEFLATED）。
实测：

```
resources.arsc  DEFLATE  raw=1904  zip=1909    ← 反而大了 5 字节
resources.arsc  STORED   raw=1904  zip=1904
```

所以「我明明设了不压缩」这个直觉是错的，而且用 `CompressedLength == Length`
去判断也不可靠（两者可能凑巧相等）。**只能读 ZIP 本地头的 method 字段**。

第二层坑：`zipalign` 在某些实现下会**重写**条目，把 STORED 又压回去。所以顺序必须是
「先用 Python 打 STORED → 再 zipalign → 最后 apksigner」，并且签完还要再验一次。

### 判定

```python
import zipfile
z = zipfile.ZipFile('magpie.apk')
for i in z.infolist():
    if i.filename in ('resources.arsc', 'AndroidManifest.xml'):
        print(i.filename, i.compress_type, i.compress_size, i.file_size)
# resources.arsc 0 1904 1904    ← 0 = ZIP_STORED，且两个尺寸相等
# AndroidManifest.xml 8 ...     ← manifest 压缩是允许的
```

### 解法

**打包这一步不要用 `System.IO.Compression`**，用 Python：

```python
zi = zipfile.ZipInfo(name, date_time=info.date_time)
zi.compress_type = zipfile.ZIP_STORED      # 真的 0，不是 "level 0"
dst.writestr(zi, data)
```

`app/pack.py` 就是干这个的，并且在把 `.so` 加进 APK 时同样保持 STORED
（`.so` 压缩与否取决于 `extractNativeLibs`，见下）。

### 附：什么时候 `.so` 该压、什么时候不该

| `android:extractNativeLibs` | `.so` 存放 | 结果 |
| --- | --- | --- |
| `true`（本项目的选择） | 可压缩（DEFLATE） | 安装时解压到 `lib/`，可执行；APK 69.9 MB |
| `false` | 必须 STORED + 页对齐 | 直接从 APK mmap，不可 `execve`；体积 175 MB |

本项目需要 `execve` 引擎，所以必须 `true` + 解压落盘；换成 `false` 会得到一个
能 `dlopen` 但不能执行的包——正好踩在坑 2 上。

---

## 坑 4 · WebView 会吞掉 `window.open`

### 症状

打开控制台 → 供应商页 → 点「登录」→ **什么也没发生**。没有报错、没有新窗口、
没有跳转。于是用户（包括我自己）以为「这里只能填 API Key」。

### 原因

三段代码叠在一起造成的沉默失败：

1. magpie 的网页用 `window.open(url, "_blank")` 打开 OAuth 授权页。
2. Android WebView **默认丢弃** `window.open`——它不弹窗、不报错、返回 `null`。
   要接住它必须显式 `setSupportMultipleWindows(true)` + 实现 `onCreateWindow`。
3. magpie 的 `openSignInPage` 拿到 `null` 后判定为「弹窗被拦」，显示提示——
   但那个提示很轻，在手机上容易被忽略，看起来就是「点了没反应」。

### 解法（两层，缺一不可）

```java
// 第一层：接住新窗口，把 URL 交给系统浏览器
webView.getSettings().setSupportMultipleWindows(true);
webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
webView.setWebChromeClient(new WebChromeClient() {
    @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message resultMsg) {
        // 从 resultMsg 里取出 WebView.WebViewTransport，拿到目标 URL 再交给系统
        ...
        return true;
    }
});
```

```javascript
// 第二层：onPageFinished 注入 shim，改调宿主
// 注意必须返回一个「占位 window 对象」，返回 null 会触发 magpie 的误判
window.open = function (url) {
  MagpieHost.open(url);                    // ← @JavascriptInterface
  return { closed: false, focus: function(){}, close: function(){} };
};
```

第二层看着多余，其实必要：`onCreateWindow` 在某些 ROM 的 WebView 实现里
不触发（尤其是 `target="_blank"` + JS 触发的组合），注入 shim 是兜底。

### 判定

```javascript
// 在控制台的 devtools 里
typeof window.open                      // function
window.open('https://example.com')      // 应该真的打开系统浏览器
```

或者看 App 日志里有没有 `open: https://...`。

### 附：登录回跳

授权完成后，OAuth 回调指向 `http://127.0.0.1:<随机端口>/...`。浏览器和应用在
同一台手机上，理论上是通的——但这是**唯一没有真机验证过的链路**
（见 [05-verification.md](05-verification.md)）。如果回跳失败，备用路径有两条：
插件详情里的「桌面登录文件导入」，或直接用 API Key。

---

## 附 · 两个「不致命但会让你多花两小时」的坑

### A. 16 KB 页对齐

Android 15+ 在部分设备上要求 `.so` 的 `LOAD` 段按 16 KB 对齐（而不是 4 KB）。
不对齐的包在这些设备上装不上或启动即崩。

```powershell
zipalign -P 16 -f 4 in.apk out.apk     # -P 16 是页对齐，4 是普通对齐
zipalign -c -P 16 -v 4 out.apk         # 验证
```

同时 NDK 必须 ≥ r26（产物的 LOAD 段对齐是 `0x4000`）。实测：

```
libmagpie.so  LOAD aligns: 0x4000
libbun.so     LOAD aligns: 0x10000, 0x4000
```

### B. `apksigner` 在 `minSdk 26` 下不写 v1

这是规范行为（v2/v3 已覆盖 7.0+），不是失败。但如果你看到 `v1: false` 就以为
「签名不完整」而去折腾 `--v1-signing-enabled true`，会浪费很久——它还是不会写，
因为 `apksigner` 认为没必要。判断签名是否有效应该看 `apksigner verify` 的退出码
和 `--print-certs` 的输出，而不是 v1 那个布尔值。

---

## 小结：检查顺序

装不上 → 先看 `resources.arsc` 的 method（坑 3），再看体积与 ABI，最后才怀疑签名。
跑起来但请求失败 → 先看引擎是不是 CGO 编译的（坑 1）。
插件装不上 → 先看 Bun 在哪（坑 2）。
登录按钮没反应 → 先看 `window.open`（坑 4）。

这四条覆盖了本项目 90% 的「玄学问题」。剩下的 10% 在
[06-troubleshooting.md](06-troubleshooting.md) 里做成了决策树。
