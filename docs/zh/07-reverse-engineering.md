# 07 · dsh-go 互操作性分析笔记

写这一篇的目的很窄：**为了让两个跑在同一台手机上的 App 能互相通信，我需要知道
dsh-go 长什么样、它把配置放在哪、它怎么访问网络。** 除此之外没有别的动机。

## 边界

留下记录的是**结论性事实**（包结构、进程模型、配置路径、端口），不是实现细节：

- 没有分发 dsh-go 的任何文件（APK、rootfs、反编译产物都在本机临时目录，未进仓库）。
- 没有修改 dsh-go 的任何文件。
- 没有绕过它的鉴权、授权或付费逻辑。
- 本文不包含可直接复制的私有实现代码。

如果你是这个 App 的作者并且认为某段描述越界，开个 issue，我删。

## 方法

```bash
unzip -l dsh-go-1.1.0-arm64.apk | head -50          # 看结构
unzip -p ... assets/flutter_assets/... > x          # 取需要的文本资源
aapt2 dump badging dsh-go-1.1.0-arm64.apk           # manifest 摘要
aapt2 dump xmltree ... --file AndroidManifest.xml   # 组件、权限、deep link
tar -tJf assets/runtime/rootfs-arm64-v8a.tar.xz | wc -l   # rootfs 清单（不看内容）
jadx -d out dsh-go-1.1.0-arm64.apk                  # 只看与启动/配置相关的类
```

## 它是什么

**不是普通 Android 应用，是一个自带 Linux 发行版的沙箱运行时。**

| 项 | 值 |
| --- | --- |
| 包名 | `io.github.ccch1mneyyy.dsh_app` |
| UI | Flutter（`libflutter.so` + `flutter_assets`）+ 原生 Kotlin（`DshService` 等） |
| rootfs | `assets/runtime/rootfs-arm64-v8a.tar.xz`：152 MB 压缩 / **798 MB 解压** / 26,774 个文件 |
| 发行版 | Ubuntu 24.04.5 |
| 运行时 | Node 24.22.1 |
| CLI | `dsh` 0.2.2-rc.1 |
| 沙箱 | `libproot.so`，PRoot 5.1.107，`--link2symlink` |
| 启动 | `libproot.so --rootfs=<rootfs> /bin/sh -c "dsh web --host 127.0.0.1 --port {PORT}"` |

## 关键事实一：PRoot 不隔离网络

这是整件事成立的技术前提。

PRoot 是一个用户态 `ptrace` 沙箱：它拦截文件系统相关的系统调用，把路径重写进
rootfs，让一个没有 root 权限的 App 跑起一个「看起来像 root」的发行版。它
**不创建网络命名空间**，也不做端口映射。

所以：

```
dsh-go 沙箱里的进程  connect("127.0.0.1", 3425)
        ↓ 同一个 loopback，内核里就是宿主机的回环
magpie 的网关（另一个 App，另一个 UID）
```

不需要 `adb reverse`、不需要端口转发、不需要额外权限。两个 App 只要都在跑，
回环就是共享的。

反过来也要记住：**这条路的可靠性依赖于 dsh-go 继续用 PRoot**。如果将来它换成
真正的容器（netns 隔离），这条路就断了，得改成 Unix socket 或者 `adb reverse`。

## 关键事实二：dsh 的模型渠道是「标准协议 + Base URL」

dsh-go 内置了一份中文指南 `dsh-go-guide/zh-02-models.md`，里面写得很清楚：

> 添加渠道 = 选 OpenAI / Anthropic / Responses + 填 Base URL + API Key + 点获取模型

这与 magpie 网关暴露的四套接口完全对得上。也就是说，**不需要为 dsh-go 写任何
适配层**——它本来就是个标准协议的客户端。

## 关键事实三：配置文件在哪

dsh 的配置在 rootfs 内部：

```
{ROOTFS}/root/.dsh/profiles/<profile>/cordis.patch.yml      # dsh 0.1.5+
{ROOTFS}/root/.dsh/config.yaml                              # 更老的版本
```

- 文件是 **patch 语义**：只写要改的字段，其余继承默认。
- 模型相关字段在 `llm-pi-ai.providers.<name>` 下，结构包含
  `api` / `baseURL` / `apiKeyEnv` / `models[]` / `streamIdleTimeoutMs`。
- 默认模型在 `agent-default-model`（`provider` / `model` / `reasoningEffort`）。
- 密钥可以放 `~/.dsh/.env`（环境变量）或 `~/.dsh/.credentials.yaml`（key store）。

magpie 桌面版就是按这个形状写配置的（`internal/agent/dsh.go`，123 KB）。
但在 Android 上，这个目录属于**另一个 App 的私有空间**，magpie 既没有权限也不该
去改——所以本项目选择让用户在 dsh-go 界面里手动加一次渠道。一次配置，长期有效。

## 关键事实四：deep link

dsh-go 注册了 `dshgo://` scheme。本项目只用到其中一个：

```
dshgo://open/models      → 直接跳到「模型」页
```

magpie 的「接入 dsh-go」弹窗里那个按钮就是发这个 intent。作用是省掉用户
在 dsh-go 里点四层菜单。**没有**用它传数据，也没有用它触发任何自动化操作。

## 我确认过但没有采纳的东西

| 发现 | 为什么不用 |
| --- | --- |
| rootfs 里的 Node 可以被替换 | 不改别人的运行时 |
| `dsh web` 的端口是启动参数，可被外部指定 | 改它需要改 dsh-go 的启动逻辑，越界 |
| dsh 支持 `llm-pi-ai` 自定义 provider，可直接写 magpie 地址 | 需要改 rootfs 内文件（另一个 App 的私有目录），且 patch 语义容易写错 |
| Flutter 层有 RPC 通道 | 与「让两个 App 通过标准协议通信」的目标无关，也没有稳定性保证 |

## 对上游的建议（如果作者看到）

1. `127.0.0.1` 共享是一把双刃剑：方便，但也意味着同机的任何 App 都能访问沙箱里
   暴露的端口。如果 `dsh web` 的 token 强度不够，建议默认只绑 unix socket。
2. `cordis.patch.yml` 的 patch 语义对用户不友好——写错缩进不报错、只是静默不生效。
   界面上「添加渠道」的引导做得很好，可以再补一条「当前生效配置预览」。

---

**再次说明**：本文只描述互操作性所需的事实。dsh-go 是第三方应用，本项目不分发、
不修改、不为其行为负责。如果你要基于这些笔记做二次开发，请先确认你所在地的法律
与该应用的许可条款。
