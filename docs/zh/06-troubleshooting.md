# 06 · 排错

先分诊，再深入。80% 的问题落在下面这张表的第一列。

| 现象 | 最可能的原因 | 跳到 |
| --- | --- | --- |
| 点安装 → 「与系统不兼容」，装不上 | `resources.arsc` 被压缩 / 空间不足 / ABI 不符 | [A](#a-装不上) |
| 装上了，打开就说「找不到引擎」 | 装的是 `-NoEngine` 的包，或 `lib/` 里没有 `libmagpie.so` | [B](#b-装上了但引擎起不来) |
| 状态灯绿了，但加渠道/对话超时 | 引擎不是 CGO 编译（DNS 挂了），或上游渠道本身不通 | [C](#c-起来了但请求失败) |
| 插件装不上，`permission denied` | Bun 在不可执行的目录 | [D](#d-插件失败) |
| 点「登录」没反应 | WebView 吞了 `window.open` | [E](#e-点登录没反应) |
| 息屏一会儿就失联 | 前台服务被系统/ROM 杀掉 | [F](#f-息屏后失联) |

先说一句最有用的话：**`adb install` 的错误码比界面上那句「与系统不兼容」有用一百倍。**

```bash
adb install -r magpie.apk
# Failure [INSTALL_PARSE_FAILED_...] / [INSTALL_FAILED_...]  ← 直接告诉你原因
```

---

## A. 装不上

### A1. 先做二分：装 `magpie-mini.apk`

Release 里附了一个 50 KB 的 `magpie-mini.apk`——**只有界面、没有引擎**。
它存在的唯一目的就是区分「APK 结构问题」和「体积/原生库问题」：

| mini 的结果 | 说明 | 下一步 |
| --- | --- | --- |
| 能装 | 结构没问题，之前是 `resources.arsc` 压缩或体积 | 装主包；还不行看 A3/A4 |
| 也装不上 | 与体积、引擎、Bun 全都无关 | 看 A2（结构）或 A5（设备侧） |

### A2. 检查 `resources.arsc` 是否 STORED

这是本项目的头号坑，症状就是那句「与系统不兼容」。

```python
import zipfile
z = zipfile.ZipFile('magpie.apk')
print([(i.filename, i.compress_type) for i in z.infolist()
       if i.filename in ('resources.arsc',)])
# [('resources.arsc', 0)]   ← 0 才对；8 = DEFLATED，装不上
```

如果是 8：这个包是用 `System.IO.Compression` 打的，重新用 `app/pack.py` 打一遍。
详见 [03-android-porting.md](03-android-porting.md) 坑 3。

### A3. 空间够不够

| 阶段 | 需要 |
| --- | --- |
| 下载 APK | 70 MB |
| 安装解压后 | 175 MB |
| 安装过程峰值 | ~350 MB |

剩余空间 < 400 MB 时，安装器报的**同样**是「与系统不兼容」。这是最容易被
误判成「架构不对」的一条。

```bash
adb shell df /data        # 看 Avail
```

### A4. 架构对不对

```bash
adb shell getprop ro.product.cpu.abi        # arm64-v8a / armeabi-v7a
```

本项目只出 `arm64-v8a`。`armeabi-v7a` 的设备会得到
`INSTALL_FAILED_NO_MATCHING_ABIS`——但很多国产 ROM 的安装器会把它也说成「不兼容」。
2017 年后的机型基本都是 arm64。

### A5. 设备侧的限制

前四条都过了还装不上，那问题在安装器：

- 部分 ROM 禁止安装**非应用商店来源**的包 → 设置里打开「未知来源」
- 企业/教育设备有 MDM 策略 → `adb shell dpm list`
- 某些 ROM 对「体积大的包」有额外限制 → 用 `adb install` 看真实错误码
- 曾有包因为签名证书 DN 里有空字段（`L=, S=`）生成畸形证书而装不上 →
  重新生成密钥（`build.ps1` 现在的 DN 是干净的）

### 附：`adb install` 错误码对照

| 错误码 | 含义 | 处理 |
| --- | --- | --- |
| `INSTALL_PARSE_FAILED_NOT_APK` | 文件不是 APK | 下载不完整，重下 |
| `INSTALL_PARSE_FAILED_BAD_MANIFEST` | manifest 二进制 XML 坏了 | 重新构建 |
| `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION` | 资源表解析失败 | 检查 `resources.arsc`（A2） |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | 没签名 | `apksigner sign` |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | ABI 不符 | A4 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | A3 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名与已装版本不同 | 先卸载旧版 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | versionCode 比已装的小 | 提升 `versionCode` |
| `INSTALL_FAILED_TEST_ONLY` | 包被标了 testOnly | 用 `adb install -t` 或去掉标记 |

---

## B. 装上了但引擎起不来

界面显示「找不到引擎」或状态灯一直是红的。

### B1. 确认装的是完整包

```bash
adb shell pm path ai.usemagpie.android
adb shell run-as ai.usemagpie.android ls -l lib/
# 应该有 libmagpie.so（~92 MB）和 libbun.so（~83 MB）
```

只有几十 KB 的 `libmagpie.so`？那是 `-NoEngine` 的包，重装完整版。

### B2. `extractNativeLibs` 必须是 true

```bash
adb shell dumpsys package ai.usemagpie.android | grep -i extractNative
```

若为 `false`，`.so` 留在 APK 里没被释放到 `lib/`，引擎根本不存在可执行的落点。
本项目的 manifest 固定为 `true`，改它就会踩这个坑。

### B3. 看日志

```bash
adb logcat -s magpie:* AndroidRuntime:E
adb shell run-as ai.usemagpie.android cat files/magpie.log | tail -50
```

常见内容：

| 日志 | 含义 |
| --- | --- |
| `权限不够` / `Permission denied` | `lib/` 没有 x 位（极少见；重装一次） |
| `exec format error` | 二进制不是 Android 的，用错了 triple |
| `no such file or directory`（但文件明明在） | 动态链接器找不到，通常是 `PT_INTERP` 指向了 glibc 的 `ld-linux` |
| `address already in use` | 3425/3430 被占，换端口 |

---

## C. 起来了但请求失败

状态灯绿了、`/api/*` 能打开，但加渠道超时或对话报错。**先分清是谁的问题。**

### C1. 判断故障在哪一跳

```bash
# 1) 网关本身活着吗
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3425/v1/models -H 'Authorization: Bearer magpie'
# 200 → 网关正常，跳到 C2；000 → 引擎没跑，回 B

# 2) 上游通吗（在控制台里点「测试」，或用真实 key 打）
# 401 → 请求出去了，key 不对
# timeout → 引擎的 DNS 或出网有问题 → C3
```

### C2. 上游返回错误

| 状态 | 含义 |
| --- | --- |
| 401 | 渠道的 key 错了/过期 |
| 403 | 账号被风控，或地区限制 |
| 429 | 额度耗尽 → 路由组会自动换下一个渠道，没得换就报错 |
| 5xx | 上游自己挂了，magpie 会重试/切换 |

### C3. `lookup ... no such host` → 引擎不是 CGO 编译的

这是坑 1。纯 Go 构建的二进制在 Android 上解析不了域名：

```bash
adb shell run-as ai.usemagpie.android "strings lib/libmagpie.so | grep -c netgo"
```

有输出说明可能是 netgo 解析器；重新按
[02-build.md](02-build.md) 用 NDK clang + `CGO_ENABLED=1` 编一次。

### C4. TLS 超时（国内网络）

```
Get "https://raw.githubusercontent.com/...": net/http: TLS handshake timeout
```

插件源和 GitHub 直连在国内经常不通。App 默认已经打开了镜像回退
（`MAGPIE_MIRRORS=on` + `chinaMirror: true` + npmmirror）。如果被人为关掉了：

```bash
adb shell run-as ai.usemagpie.android cat files/home/.config/magpie/settings.json
# 确认 "chinaMirror": true
```

---

## D. 插件失败

### D1. `fork/exec .../magpie-bun: permission denied`

Bun 在应用私有目录里，W^X 不让执行。这是坑 2。

```bash
adb shell run-as ai.usemagpie.android ls -l lib/libbun.so
# 没有这一行 → 装的是不带 Bun 的精简版
adb shell run-as ai.usemagpie.android ls -l files/home/.cache/magpie/bun/*/magpie-bun
# 有这一行 → 说明 MAGPIE_BUN 没生效，引擎在走下载路径
```

### D2. 插件装了但「登录方式只有 API Key」

那不是插件的问题，是壳的问题 → [E](#e-点登录没反应)。

### D3. `bun add` 卡住 / 超时

npm 源不通。检查 `settings.json` 里的 `npmRegistry`，以及
`MAGPIE_NPM_REGISTRY` 环境变量（两者都应该是 `https://registry.npmmirror.com`）。

---

## D2. 供应商登录报「has no build for android arm64」

有些「订阅」供应商不是纯协议实现，而是**包装一个厂商的桌面 CLI**：点登录时
magpie 先跑厂商的安装脚本把 CLI 拉下来，找到可执行文件之后才开始 OAuth。
Android arm64 上这些厂商没有构建，于是第一步就失败——界面停在「登录未完成」，
看起来像点了没反应。

两种典型原文：

```
installing Antigravity CLI failed
  (Antigravity CLI has no build for android arm64)

No official AIStudio2API CLI archive for android/arm64.
```

| 供应商 | 依赖的 CLI | 手机上有解吗 |
| --- | --- | --- |
| Antigravity（agy-cli） | `agy`（Google，只有 macOS/Linux/Windows） | 无，也没提供远程模式 |
| Google AI Studio | AIStudio2API（有 Windows x64） | **有**，见下 |
| Grok → Grok Build / Devin / Cursor CLI | 各自厂商的 CLI | 无 |

### Google AI Studio 的例外

它的供应商设置里有两个不用本地 CLI 的模式（报错文案自己也会提示）：

- **连接已有 AIStudio2API（地址 + API key）**：在电脑上跑 AIStudio2API（官方有
  Windows x64 包），手机里填电脑的局域网地址和 key。两个设备在同一 Wi-Fi 即可。
- **指定 AIStudio2API 程序路径（高级）**：只在能运行该程序的平台上才有意义，
  Android 上填什么路径都不会跑起来。

### 想在这些供应商上用手机，只有两条路

1. **电脑跑 magpie 桌面版**（agy / AIStudio 都能正常装），手机的 dsh-go 直接把
   Base URL 指向电脑：`http://<电脑局域网 IP>:3425/v1`，Key 仍是 `magpie`。
   手机端不用装任何 CLI。
2. **换成不依赖本地二进制的供应商**：API Key（DeepSeek / Kimi / GLM / OpenRouter…）
   或订阅登录（Claude / ChatGPT / Copilot / WorkBuddy）——这些是 magpie 自己用协议
   实现的，不需要任何外部程序。

> 顺带一句：`@magpie-community/opencode-antigravity-auth` 这个老插件已被上游下架
> （Google 会封用它调 Code Assist 的账号），即使能装上也不建议使用。

## E. 点「登录」没反应

这是坑 4。三层检查：

1. **页面有没有订阅登录区块**——供应商页往下滑，应该能看到
   「订阅 · 登录即可，无需密钥」和 Claude / ChatGPT / Copilot 等图标。
   如果连这个区块都没有，是页面没加载完或版本不对。
2. **点了之后系统浏览器有没有起**——没有起，就是 `window.open` 被吞了。
3. **浏览器起了但没回到 App**——是回跳问题，见 E2。

### E1. `window.open` 被吞

需要在 `MainActivity` 里：
`setSupportMultipleWindows(true)` + `onCreateWindow` 接住，再注入 `window.open` shim。
本项目已实现，见 [03-android-porting.md](03-android-porting.md) 坑 4。
如果你在改代码，注意 **shim 必须返回一个占位对象而不是 `null`**。

### E2. 回跳失败

授权完成后浏览器要跳到 `http://127.0.0.1:<随机端口>/...`，由引擎里的临时
HTTP 服务接住。这一段**没有真机验证过**。失败时的两条替代路径：

1. 插件详情 → 「桌面登录文件导入」：在电脑上登录，把凭据文件导进来
2. 直接用 API Key（这条路一定可用）

---

## F. 息屏后失联

### F1. 前台服务还在吗

```bash
adb shell dumpsys activity services ai.usemagpie.android | head -20
```

通知栏里应该常驻一条「magpie 网关运行中」。通知消失了就说明服务被杀了。

### F2. 电池白名单

App 主界面有「电池优化白名单」开关。国产 ROM 还需要额外几步（因厂商而异）：

| 厂商 | 位置 |
| --- | --- |
| 小米 / Redmi | 设置 → 应用设置 → magpie → 省电策略 → 无限制；自启动 → 打开 |
| 华为 / 荣耀 | 应用启动管理 → magpie → 手动管理，三个开关全开 |
| OPPO / 一加 | 电池 → 应用耗电管理 → 允许后台运行 |
| vivo | 后台高耗电 → 允许 |
| 三星 | 电池 → 后台使用限制 → 从「休眠应用」里移除 |

### F3. WakeLock 有没有拿到

```bash
adb shell dumpsys power | grep -i magpie
```

没有 `magpie:gateway` 说明 Service 没走到 `acquireWake()`——通常是前台服务
没起来（F1）。

---

## 还解决不了

提 issue 时请带上这四样，能省掉一半来回：

```bash
adb shell getprop ro.product.model      # 机型
adb shell getprop ro.build.version.release  # Android 版本
adb shell run-as ai.usemagpie.android cat files/magpie.log | tail -100
adb install -r magpie.apk 2>&1          # 完整的错误输出
```
