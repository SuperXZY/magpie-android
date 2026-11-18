# 04 · 接入 dsh-go

这一篇讲怎么让 dsh-go 用上 magpie 的网关，以及为什么这样接能通。

## dsh-go 是什么（决定了能不能接）

`dsh-go` 不是普通的 Android 应用：它是一个 **PRoot 沙箱里跑的完整 Ubuntu**，
App 本身（Flutter + Kotlin）只是个壳。

| 项 | 值 |
| --- | --- |
| 包名 | `io.github.ccch1mneyyy.dsh_app` |
| rootfs | `assets/runtime/rootfs-arm64-v8a.tar.xz`，152 MB 压缩 / **798 MB 解压** / 26,774 个文件 |
| 发行版 | Ubuntu 24.04.5 |
| 运行时 | Node 24.22.1 + `dsh` 0.2.2-rc.1 |
| 沙箱 | PRoot 5.1.107，`--link2symlink` |
| 启动命令 | `libproot.so --rootfs=… /bin/sh -c "dsh web --host 127.0.0.1 --port {PORT}"` |

关键结论：**PRoot 不做网络命名空间隔离**。它只是拦截文件系统与 `ptrace` 系统调用，
网络栈还是宿主的。所以：

```
dsh-go 里的 Node 进程  →  connect 127.0.0.1:3425  →  手机上的 magpie
        ↑ 同一个 loopback，没有 NAT、没有端口映射、不需要 adb reverse
```

这也是整套方案能成立的前提。如果哪天 dsh-go 换成了带 netns 的容器方案，
这条路就断了。

## 接法（推荐）：在界面里加渠道

dsh-go 内置的模型指南（`dsh-go-guide/zh-02-models.md`）写得很明确：
**添加渠道 = 选 OpenAI/Anthropic/Responses + Base URL + API Key + 获取模型**。
magpie 的网关恰好就是这四种协议。

1. 先在 magpie 里配好至少一个真实渠道，并确认控制台能看到模型
2. 打开 magpie 主界面 → **接入 dsh-go** → 复制两个值
3. 打开 dsh-go → 设置 → 模型 → **添加渠道**
4. 按下面填：

| 字段 | 值 |
| --- | --- |
| 类型 | **OpenAI 兼容**（也可以用 Anthropic / Responses，见下） |
| Base URL | `http://127.0.0.1:3425/v1` |
| API Key | `magpie` |
| 模型 | 点「获取模型」，勾选需要的，保存 |

5. 回到对话，顶部模型胶囊里选刚加进来的模型

### 三种协议怎么选

magpie 的网关同时提供四套接口（互转），所以 dsh-go 侧选哪种都能通：

| dsh-go 里选 | 实际打的接口 | 适合 |
| --- | --- | --- |
| OpenAI 兼容 | `POST /v1/chat/completions` | 默认选这个，兼容性最好 |
| Responses | `POST /v1/responses` | 需要 OpenAI 新版语义时（reasoning 汇总、内置工具） |
| Anthropic | `POST /v1/messages` | 想在 dsh-go 里用 Claude 风格的 thinking 字段 |

选了 Anthropic 也不用改 magpie 侧——网关会把 `/v1/messages` 翻译成上游要的形状。
实测：dsh-go 发 `/v1/messages`，上游收到的是 `POST /v1/chat/completions`，协议翻译正确。

### 为什么 Key 是 `magpie`

这是 magpie 桌面版给 agent 用的**默认本地口令**，不是秘密。真正拦住外部访问的是
「只监听 127.0.0.1」——除了同一台手机上的进程，没人能连上。想让 dsh-go 用别的
口令，改 `Magpie.java` 里的 `TOKEN` 并同步改 magpie 的网关配置（不推荐，没有意义）。

## 接法（备选）：直接改 dsh 的配置

不想点界面，或者想批量部署时，可以直接写配置文件。位置在 rootfs 里：

```
{ROOTFS}/root/.dsh/profiles/<profile>/cordis.patch.yml     # dsh 0.1.5+
{ROOTFS}/root/.dsh/config.yaml                             # 更老的版本
```

`{ROOTFS}` 在 App 私有目录下，需要 root 或 `run-as` 才能碰。结构（字段名来自
magpie 的 `internal/agent/dsh.go`，**下面是示意，不是逐字模板**）：

```yaml
llm-pi-ai:
  providers:
    magpie:
      api: openai                    # openai | anthropic | responses
      baseURL: http://127.0.0.1:3425/v1
      apiKeyEnv: MAGPIE_GATEWAY_KEY   # 指向环境变量，避免明文
      models: [deepseek-chat, kimi-k2]
      streamIdleTimeoutMs: 120000
agent-default-model:
  provider: magpie
  model: deepseek-chat
```

同时要在 `~/.dsh/.env` 里写 `MAGPIE_GATEWAY_KEY=magpie`，dsh 的 key store
`~/.dsh/.credentials.yaml`（桌面版只读这个）也需要有对应条目。

> 手改 YAML 的风险：dsh 的配置是 **patch 语义**，写错一个缩进不会报错，只会静默
> 不生效。除非你要做自动化，否则用界面加渠道。

## 验收

### 在 magpie 侧

控制台 → Agent 页能看到模型列表；或者直接打网关：

```bash
curl -s http://127.0.0.1:3425/v1/models -H 'Authorization: Bearer magpie' | head -c 400
```

### 在 dsh-go 侧

dsh-go 里有终端的话，最硬的验证是**从 PRoot 内部**打网关：

```bash
# 在 dsh-go 的终端里
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3425/v1/models \
  -H 'Authorization: Bearer magpie'
# 期望 200；如果 000 说明连不上，先回去看 magpie 是否在跑
```

再发一次真实对话：

```bash
curl -s http://127.0.0.1:3425/v1/chat/completions \
  -H 'Authorization: Bearer magpie' -H 'Content-Type: application/json' \
  -d '{"model":"<你在 magpie 里配的模型>","messages":[{"role":"user","content":"ping"}]}' | head -c 300
```

## 排错

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| dsh-go 里「获取模型」转圈/失败 | magpie 没在跑，或引擎起来了但没配任何渠道 | 先看 magpie 状态灯；再在控制台确认 `/v1/models` 有内容 |
| 401 / 403 | Key 不是 `magpie`，或 Base URL 少了 `/v1` | Base URL 必须是 `http://127.0.0.1:3425/v1` |
| 能列模型，对话超时 | 上游渠道本身不通（假 key、余额、被墙） | 在控制台用「测试」按钮打一次，看上游返回什么 |
| 对话卡在「思考中」不动 | 上游是流式，但 magpie 被系统冻结了 | 给 magpie 加电池白名单，确认前台服务通知还在 |
| 换了渠道后 dsh-go 还是老模型 | dsh-go 缓存了模型列表 | 在 dsh-go 里重新「获取模型」，或重启 dsh-go |
| 端口冲突 | 3425 被别的 App 占了 | 改 `Magpie.java` 的 `GATEWAY_PORT`，同时改 dsh-go 的 Base URL |

## 设计上的取舍

**为什么不给 dsh-go 做一个专门的桥接 App/插件？**
不需要。dsh-go 的「添加渠道」本身就是 OpenAI 兼容接口的通用客户端，
magpie 提供的也正是这个接口。多一层桥接只会多一个会坏的东西。

**为什么不让 magpie 直接改 dsh-go 的配置文件？**
桌面版 magpie 会改写 Claude Code / Codex 等 agent 的配置（`internal/agent`），
但在 Android 上那些文件在 dsh-go 的 rootfs 里，属于另一个 App 的私有目录，
既没权限也不该越界。保持「两个 App 各自独立、只通过 loopback 通信」是更干净的边界。
