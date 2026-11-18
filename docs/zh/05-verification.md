# 05 · 验证记录

原则：**能测的都测，测不了的明说**。这份文档里的每一项都带「怎么测的」，
方便你复核；没测的单独列在最后，不混在结论里。

## 测试环境

| 项 | 值 |
| --- | --- |
| 构建机 | Windows 11，无 Android 设备（`adb devices` 为空），Hyper-V 未启用 → 无模拟器 |
| 引擎 | 同一份上游源码，两个目标：`windows/amd64`（用来跑真引擎做基准）与 `android/arm64`（进 APK） |
| 渲染测试 | Chrome headless + CDP，视口 412×915 @2x（与主流手机一致） |
| 上游替身 | 自建 mock（先 HttpListener，后因端口被 http.sys 占用改成 `TcpListener` 纯 socket 实现） |

为什么用 Windows 引擎做基准：网关、路由、协议翻译、插件系统都是**同一份 Go 代码**，
与目标平台无关；把它跑在 Windows 上，就能在「没有手机」的条件下验证除
「Android 运行时」之外的全部逻辑。Android 特有的部分（可执行性、对齐、进程模型）
用结构检查覆盖，真机行为标注为未验证。

## 一、接口层

引擎起来后逐个打。凭证用 `?k=<webKey>` 换来的 Cookie（与 WebView 里完全同一条链路）。

### 读接口（22 个，全部 200）

```
/api/state              /api/settings          /api/providers        /api/groups
/api/usage              /api/version           /api/sessions         /api/sessions/overview
/api/sessions/stats      /api/plugins           /api/plugins/builtin  /api/plugins/counts
/api/plugins/listings    /api/catalog/models    /api/agents/cli       /api/gateway
/api/gateway/keys        /api/lanes             /api/notices          /api/drift
/api/proxy               /api/cli
```

`/api/state` 返回 14,483 字节的真实状态（不是空壳）。

### 写接口（全套 CRUD，全部 200）

| 接口 | 覆盖的操作 |
| --- | --- |
| `/api/settings` | 读 + 写（主题、语言、镜像、网关模式） |
| `/api/provider/{name}` | `save` / `test` / `key` / `on` / `off` / `delete` / `models` |
| `/api/groups` | `save` / `delete` |
| `/api/plugins` | `add` / `options` / `off` |
| `/api/sessions/manage` | 会话管理动作 |

### 关键的一条：`provider/test`

用**假 key** 打真实的 DeepSeek：

```
POST /api/provider  {"action":"test", ...}  →  上游 401
```

401 是**期望结果**——它证明请求真的出网了，并且真的被发到了 `api.deepseek.com`。
如果返回 200 或超时，反而说明链路有问题。

## 二、端到端

### 网关 → 上游（mock）

```
POST /v1/chat/completions  {"model":"mock-model","messages":[{"role":"user","content":"ping"}]}
→ 200  {"content":"pong from mock", "usage":{"prompt_tokens":5,"completion_tokens":3}}
```

这条打通了 `请求 → 选 provider → 转发上游 → 解析 → 回填 usage` 全链路。

### `/v1/models`

正确列出渠道里的模型（`mock-2/mock-model`），与 dsh-go 点「获取模型」看到的一致。

### 协议翻译

| 入口 | 上游实际收到的 | 结论 |
| --- | --- | --- |
| `POST /v1/messages`（Anthropic） | `POST /v1/chat/completions`（OpenAI） | 翻译正确 |
| 流式（SSE） | 逐块转发，`usage` 在最后一块 | 正常 |

> Anthropic 那条最初返回 502，原因是 mock 只读了一次请求体就关连接，不是 magpie 的
> 问题。换成完整读请求体的 mock 后 200。

## 三、插件与登录

| 项 | 结果 |
| --- | --- |
| 插件安装 `@magpie-community/opencode-workbuddy-auth` | 成功，版本 0.2.36 |
| `/api/plugins` 里的 `bun` / `mirror` | `true` / `true`，`bunVersion: 1.4.2` |
| 插件提供的登录方式 | 3 种：浏览器 OAuth ×2 + API Key ×1 |
| OAuth 返回 | 154 字符授权 URL |
| 订阅登录 `/api/signin` | Claude 447 / Codex 454 / Gemini 546 字符授权 URL |

`window.open` shim 单独验证过：在 Node 的 `vm` 里加载 magpie 真实的
`openSignInPage` 函数，替换 `window.open` 为 shim，确认 URL 被正确交给宿主、
且返回的占位对象不会触发「弹窗被拦」分支。

## 四、渲染（412×915 @2x）

用 Chrome headless + CDP 打开 `http://127.0.0.1:3430/?view=<页面>`，截图核对：

| 页面 | 结果 |
| --- | --- |
| Agent（首页） | 中文、暗色主题、底部 4 个导航、快速上手卡片，全部正常 |
| 供应商 | **「订阅 · 登录即可，无需密钥」区域确实存在**：Claude / ChatGPT / Copilot / WorkBuddy / Gemini CLI |
| 设置 | 8 个标签、外观、语言、网关模式，全部正常 |
| 用量 | 正常 |

> 这几张截图是「用户说只能填 API Key」那次争议的关键证据：页面本来就有订阅登录，
> 是 `window.open` 被吞导致点了没反应——问题在壳，不在页面。

## 五、APK 结构

```
aapt2 dump badging
  package: name='ai.usemagpie.android' versionCode='1' versionName='1.0.0'
  targetSdkVersion: 34
  native-code: 'arm64-v8a'
  launchable-activity: ai.usemagpie.android.MainActivity

apksigner verify --print-certs
  Signer #1 certificate SHA-256: 7ec5f3b3…（维护者密钥）

zipalign -c -P 16 -v 4
  Verification succesful

resources.arsc      STORED   1904 → 1904
libmagpie.so        LOAD aligns 0x4000
libbun.so           LOAD aligns 0x10000, 0x4000
```

## 六、没验证的（重要）

| 项 | 为什么没验 | 怎么补 |
| --- | --- | --- |
| **真机运行** | 构建机无 Android 设备，`adb devices` 为空，Hyper-V 未启用装不了模拟器 | 装上去，看状态灯是否变绿、日志有没有起来 |
| **界面实际观感** | 只有 headless Chrome 的移动端渲染，不是真实 WebView | 真机上看一屏，注意状态栏遮挡与字体缩放 |
| **订阅登录回跳** | 回调是 `127.0.0.1:<随机端口>`，只有真机能验「浏览器 → 应用」这一段 | 点一次登录，看是否回到应用并出现「已登录」 |
| **厂商 ROM 保活** | 需要真机息屏挂几小时 | 加电池白名单后，息屏 30 分钟再让 dsh-go 发一次请求 |

Android 侧的「引擎能执行」这一点，目前只有结构证据（ELF `DYN`/`AArch64`、
`NEEDED` 全是系统库、16 KB 对齐、位于 `nativeLibraryDir`），**没有运行时证据**。
这是本次交付最大的诚实缺口。

## 复现

```powershell
# 1) 起一个干净的引擎实例（Windows，用于基准测试）
$env:HOME='D:\tmp\magpie-home'; $env:MAGPIE_ADDR='127.0.0.1:3425'; $env:MAGPIE_WEB_KEY='testkey1234567890'
.\magpie.exe serve --web=127.0.0.1:3430

# 2) 换 Cookie
curl -i "http://127.0.0.1:3430/?k=testkey1234567890"        # 303 + Set-Cookie
curl -H "Cookie: magpie_web=..." http://127.0.0.1:3430/api/state | head -c 200

# 3) 打网关
curl -H 'Authorization: Bearer magpie' http://127.0.0.1:3425/v1/models
```

完整列表见 `work/apitest*.ps1` 的原始脚本（未随仓库发布，思路就是上面的 curl）。
