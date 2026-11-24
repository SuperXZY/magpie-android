# 10 · 手机上的 Gemini 订阅

结论先放这儿：**「Gemini CLI 订阅」在手机上走不通，但「Gemini Web」能，而且不需要电脑。**
两者的区别是「用哪个 Google 额度」，不是「能不能装」。

## 三条 Gemini 路，手机上分别怎样

| 入口 | 本质 | 手机可行性 |
| --- | --- | --- |
| **Gemini CLI**（内置"订阅登录"） | magpie 自己实现 OAuth（用 Gemini CLI 的 client id），纯 HTTP，**不需要本地二进制** | 能走完授权，但 **Google 已不对个人账号提供**，只有 Code Assist Standard/Enterprise + 自有 GCP 项目才拿得到额度 → 个人号授权成功也没用 |
| **Gemini Web**（`@dorokuma/magpie-gemini-web`） | 用网页版（gemini.google.com）的会话额度，后端是 `gemini-web2api-go` | **能**，后端要跑在能执行 linux/arm64 程序的地方 —— dsh-go 的 Ubuntu 或电脑 |
| **Antigravity**（agy-cli） | 包装 Google 的桌面 CLI | 不能：Google 没有 android 构建，插件也只认本地可执行文件；且 Google 会封这类账号 |

上游源码里那句话很直白（`internal/provider/google.go`）：

> Google no longer serves Gemini CLI's sign-in to individuals: only a Code Assist
> Standard or Enterprise account, with a Google Cloud project of its own, gets an answer.

所以别再在「Gemini CLI」上花时间——它连的是企业版 Code Assist，不是你的 Google AI Pro。

## 推荐的手机方案：Gemini Web + dsh-go 里的后端

为什么不是「把后端打进 APK」：`gemini-web2api-go` 官方的 linux/arm64 产物是
**ET_EXEC（非 PIE）**，而 Android 只允许 PIE 可执行文件，且只允许从 APK 的 `lib/`
执行（安装后只读）。要内置就得从源码用 NDK 重编成 PIE（我验证过可以编出来，
19.5 MB），代价是每个用户多下 20 MB。

dsh-go 的 Ubuntu 是普通 arm64 Linux，没有这两条限制；PRoot 又与宿主共享
`127.0.0.1`，所以手机的 magpie 能直接连到它。**零体积成本。**

### 一、在 dsh-go 里装后端

把仓库里的 `scripts/gemini-web-backend.sh` 拷进 dsh-go 的终端（或直接在
dsh-go 里 `curl` 下来），然后：

```bash
bash gemini-web-backend.sh          # 下载 17 MB、写配置、后台启动
# → 会打印：
#   地址    http://127.0.0.1:8083/v1
#   API Key <32 位>
#   admin   http://127.0.0.1:8083/admin  + admin token
```

脚本做了四件事：从 gh-proxy / ghfast 镜像下载 `gemini-web2api-go`（linux/arm64），
`chmod +x`，写一份只监听 `127.0.0.1` 的 `config.json`，用 `setsid nohup` 起在后台。
其它子命令：`status` / `stop` / `log` / `start`。

> 依赖：dsh-go 的 Ubuntu 里有 `curl` 和 `python3`（都有）。后端默认端口 8083，
> 可用 `GEMINI_WEB_PORT` 改。

### 二、在 magpie 里接上

控制台 → 供应商 → **Gemini Web** → 登录方式选 **「连接已有后端」**：

```
地址     http://127.0.0.1:8083/v1
API Key  ← 脚本打印的那串
```

保存后 `/v1/models` 就有 `gemini-3.6-flash` 等模型了。

### 三、登录 Google（拿额度）

浏览器打开 `http://127.0.0.1:8083/admin`，用脚本打印的 admin token 登录，
按面板里的引导走 **无痕窗口** 登录 Google —— 千万别用日常窗口，共用会话会被
Google 判定异常并踢掉所有 Google 登录。

登录完把 cookie 交给后端（面板里有入口）。之后 magpie 每次请求都会用这个会话，
用量卡片还能显示网页版的 5 小时 / 每周剩余额度。

> Gemini 在境内直连不通，后端和 magpie 都需要能出去的网络。后端的代理可以在
> `config.json` 的 `proxy` 或面板里配，也可以用 `--proxy` 参数。

## 备选：后端跑在电脑上

同一套流程，把二进制换成 Windows/macOS 版本即可（官方有 x64/arm64 包），
然后在供应商里填 `http://<电脑局域网 IP>:8083/v1`。好处是电脑常开时手机换网络也不断；
坏处是要两台设备都在线。

## 排错

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 脚本下载失败 | GitHub release 在境内不稳 | 脚本已按 gh-proxy → ghfast → 官方 依次重试；都不行就挂代理 |
| `Connection refused` | 后端没跑（dsh-go 被切后台后 PRoot 可能被挂起） | 回到 dsh-go 执行 `bash gemini-web-backend.sh status`，必要时 `start` |
| 供应商保存时报 401 | API Key 与后端不一致 | `status` 重新打印一次，粘贴最新的 |
| 登录后仍 401/无模型 | cookie 失效或会话被踢 | 重新用无痕窗口登录一次 |
| 请求超时 | Google 不可达 | 给后端配代理，确认能打开 gemini.google.com |

## 一句话总结

**Gemini CLI 订阅在手机上是死的（Google 政策），Gemini Web 是活的（只要给它一个能跑后端的地方）。**
手机上最省事的做法就是 dsh-go 里跑那个 17 MB 的后端，magpie 用「连接已有后端」接过去。
