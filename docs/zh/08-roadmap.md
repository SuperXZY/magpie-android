# 08 · 路线图

按「不做会痛」排序，不是按「做起来爽」排序。

## P0 · 真机验证

这是当前最大的缺口：所有 Android 侧的结论都是结构性的（ELF 类型、对齐、路径），
没有一条是「在手机上跑过」。需要一台 arm64 真机，验四件事：

- [ ] 装得上（`adb install` 无错误）
- [ ] 状态灯变绿，`/v1/models` 有响应
- [ ] 订阅登录：点登录 → 系统浏览器 → 回跳 → 显示已登录
- [ ] 息屏 30 分钟后，dsh-go 仍能发请求成功

验收标准：把结果填进 [`05-verification.md`](05-verification.md) 的「没验证的」表格，
并删掉 README 里那句「未验证」。

## P1 · 让「装不上」不再玄学

- [ ] 在 Release 里同时提供 `-NoEngine`（50 KB）与完整包，并在 README 顶部写清
      「装不上先试 mini」——目前只写在 troubleshooting 里，位置太深。
- [ ] 加一个 `check-apk.py`：一条命令输出「这个 APK 会不会被安装器拒绝」的全部
      判据（arsc method、对齐、ABI、签名版本、解压体积）。目前这些检查散落在
      PowerShell 片段里。
- [ ] 如果确认 `resources.arsc` 是主因，考虑在 `build.ps1` 结束时**强制**校验，
      不通过直接 `exit 1`。

## P2 · 体积

69.9 MB 里 33 MB 是 Bun。三条路，按性价比排：

| 方案 | 体积 | 代价 |
| --- | --- | --- |
| 发布两个包（含/不含 Bun） | 36 MB / 69.9 MB | 需要维护两条构建流水线，Release 里放两个 APK |
| 首次用插件时下载 Bun 到 `lib/` | 36 MB | **做不到**：`lib/` 是只读的，安装后才可写的位置都不可执行 |
| 裁剪 Bun（去掉不需要的内置模块） | 未知 | 需要自己编 Bun，风险高，收益不明 |

结论：**先做「两个包」**，这是唯一确定可行的方案。

## P3 · 版本号同步

`Magpie.java` 里 `version()` 返回硬编码的 `"0.1.4384"`，而上游已经到 `0.1.4386`。
界面上的版本号因此不可信。

- [ ] 构建时把 `-X main.version=` 的值同步注入 Java（生成一个 `BuildInfo.java`，
      或写进 `res/values/strings.xml` 再读）。
- [ ] 顺手把 `versionCode` / `versionName` 与 APK 的 `versionName` 对齐
      （现在 manifest 是 1.0.0，引擎是 0.1.4384，两套编号容易混）。

## P4 · CI

没有 CI 的公开仓库，别人不敢用。但 Android 构建需要 175 MB 的原生库，全部搬上
CI 不现实。可行的最小方案：

- [ ] `workflow_dispatch` + `windows-latest`：只跑 `build.ps1 -NoEngine`，
      证明「源码能编过、资源能链接、签名流程完整」。产物 50 KB。
- [ ] 加一个 `check-apk.py` 的 job，对 Release 里的 APK 做结构校验。

不做：在 CI 里编引擎（需要 NDK + 160 MB 源码 + 20 分钟，性价比低）。

## P5 · 工程债

- [ ] `MainActivity.java` 568 行，界面/权限/WebView/弹窗混在一起，应该拆成
      `Ui`、`ConsoleWebView`、`DshDialog` 三个类。
- [ ] 日志文件无轮转，长期使用会一直涨。加一个「超过 5 MB 就截断」。
- [ ] 端口硬编码（3425/3430），冲突时只能改代码。做成设置项。
- [ ] `Magpie.java` 的 `chmod()` 实际上是死代码（走的是 `lib/` 路径，不需要），
      删掉或改成诊断用途。

## P6 · 体验

- [ ] 控制台首次打开时，如果没有任何渠道，直接跳到「添加供应商」页而不是首页。
- [ ] 通知里显示实时请求数/今日用量（`/api/usage` 已经有数据）。
- [ ] 深色模式跟随系统（现在控制台是 magpie 自己的 `theme:dark`）。
- [ ] 平板/横屏布局（现在是竖屏单列）。

## 不做

明确不做，避免反复讨论：

| 想法 | 为什么不做 |
| --- | --- |
| 重写一个 magpie 内核 | 上游 61 万行，没有意义；壳才是缺的那块 |
| 支持 iOS | 完全不同的运行时限制，另一个项目 |
| 把 magpie 改成 Android Service 常驻的多实例 | 配置目录固定，多实例会互相覆盖 |
| 内置代理/VPN 功能 | 越界，且与「本地网关」的定位冲突 |
| 改 dsh-go 的 rootfs 实现自动接线 | 另一个 App 的私有空间，不做 |
| 上 Google Play | 分发的 APK 内置了第三方二进制，且 targetSdk 34 需要长期维护；先用 GitHub Release |
