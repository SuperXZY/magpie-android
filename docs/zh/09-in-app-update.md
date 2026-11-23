# 09 · 应用内更新

上游 magpie 一天发几十个版本（`v0.1.4386` → `v0.1.4722` 只隔了 6 天），所以这个 App
必须能跟上，否则用户装上去就停在出生那天的引擎上。这一篇讲清楚它为什么只能整包更新，
以及「检查 → 下载 → 校验 → 安装」这条链路。

## 为什么不能只换引擎

引擎是 `lib/arm64-v8a/libmagpie.so`，从 `nativeLibraryDir` 执行——Android 10 起的
W^X 策略只允许执行这一个目录里的文件，而它在安装后由系统持有、**只读**。

三条看似可行的路，全都不通：

| 想法 | 为什么不成立 |
| --- | --- |
| 把新引擎下到 `files/` 再执行 | W^X 直接 `permission denied`（就是 1.0.0 里 Bun 踩的那个坑） |
| 下到 `lib/` 覆盖旧的 | 目录只读，`/data/app/…/lib` 属于系统 |
| 用 `dlopen` 加载引擎当库 | 引擎是 Go 的可执行文件，不是共享库，没有 `.dynsym` 入口 |

所以「和电脑版同步」在 Android 上只有一个答案：**换整包**。这不是妥协——所有没有
root 的 Android 应用都只能这么干（Termux、F-Droid 客户端、各类自更新 App 都一样）。

好处是顺带解决了宿主自身的更新：界面、保活、悬浮窗的改动跟引擎一起送到。

## 链路

```
主界面「版本与更新」卡
├── 应用版本 1.1.0 · 5          ← PackageManager
├── 内置引擎 magpie 0.1.4722    ← Magpie.version()
├── 上游最新 magpie v0.1.4722   ← api.github.com/repos/yetone/magpie-releases/latest
└── [检查更新] [发布页]
        │
        │ GET api.github.com/repos/SuperXZY/magpie-android/releases/latest
        ▼
   比版本号 tag vs versionName（v1.1.0 > 1.0.0）
        │
        ▼ 有新版本
   下载 magpie.apk → cacheDir/update/
        │  进度条 400 ms 刷一次
        ▼
   SHA-256 对比（取自 release asset 的 digest 字段）
        │  不一致 → 删掉文件，绝不安装
        ▼
   PackageInstaller.Session（MODE_FULL_INSTALL）
        │  不需要 FileProvider、不写外部存储
        ▼
   系统安装器弹出 → 用户确认 → 进程被杀 → 系统拉起新版
```

## 三个设计取舍

**1. 只信任 GitHub 的 `digest`，不信任下载过程**
Release 资产列表里现在带 `digest: "sha256:…"`。下载完先算一遍再比对，不一致就删文件
并把两个前缀都显示出来。这一条比什么签名校验都实际——它挡住的是「半截文件」和
「镜像返回了别的包」，恰是弱网环境最常见的两种失败。

**2. 检查用两个仓库，升级只用本仓库**
上游 `yetone/magpie-releases` 只用来显示「电脑版到哪了」，升级目标是本仓库的 Release
（因为只有本仓库的包里带着对应的 Android 宿主）。这样用户能一眼看出自己落后多少，
又不会误装一个装不上的上游 CLI 二进制。

**3. 静默自检 6 小时一次**
`onResume` 时看 `lastUpdateCheck`，超过 6 小时才打 API。GitHub 未认证请求是
60 次/小时/IP，两次调用一次检查，正常使用不会撞到限制。

## 权限

唯一新增的是 `REQUEST_INSTALL_PACKAGES`。Android 8.0 起还需要用户手动允许一次
「安装未知应用」（`canRequestPackageInstalls()`），所以第一次点「下载并安装」会弹一个
说明框，按钮直接跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，授权回来再点一次即可。

不申请 `WRITE_EXTERNAL_STORAGE`——下载落在 `cacheDir`，`PackageInstaller` 读得到。

## 发布一侧的要求

因为 App 认的是**资产名**，新版本的 APK 必须以 `magpie.apk` 这个名字上传到 Release：

```
gh release create v1.1.0 \
  --title "magpie for Android 1.1.0" \
  --notes-file release-notes.md \
  magpie.apk          # ← 名字必须是这样
```

同名资产在不同 Release 里互不冲突；App 取的是「最新 Release 里名叫 magpie.apk 的东西」。

## 已知限制

- 下载 76 MB 在弱网下会很久。断点续传**没有**做——`PackageInstaller` 的 session 支持
  分块写入，但要自己维护偏移量，目前选择「失败就重来」，并在文案里给出明确的失败原因。
- 校验失败时只删文件、不自动重试，避免在被劫持的网络上反复下载。
- 从 1.0.x 升到 1.1.0 需要手动装一次（旧版没有更新器），之后就能在应用里升级。
