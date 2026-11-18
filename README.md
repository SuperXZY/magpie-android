# magpie for Android

An Android host for [yetone/magpie](https://github.com/yetone/magpie) — the local
model gateway that puts Claude Code, Codex, Gemini CLI, OpenCode, DSH and 100+
other agents behind one loopback port.

The upstream Go engine is cross-compiled to `android/arm64` **unmodified**, launched
by a foreground service, managed by a small native Java UI, with magpie's own web
console embedded in a WebView. The point is to let **dsh-go** (a PRoot-hosted
Ubuntu running the dsh CLI) get model channels on a phone.

```
phone
├── magpie (this repo)    127.0.0.1:3425  ← gateway (OpenAI / Anthropic / Gemini / Responses)
│                         127.0.0.1:3430  ← console (the desktop web UI, untouched)
└── dsh-go                add an OpenAI-compatible channel → base URL 127.0.0.1:3425/v1
```

| | |
| --- | --- |
| Engine | upstream `magpie` @ `50c5a52` (`VERSION` = v0.1.4386), **source unmodified** |
| Host | 4 Java files, ~1.2 k LOC, zero dependencies, no Gradle |
| Output | `magpie.apk`, 69.9 MB, `minSdk 26` / `targetSdk 34`, `arm64-v8a` only |
| Package | `ai.usemagpie.android`, `versionCode 1` |

> Chinese documentation is the primary one and much more detailed:
> **[README.zh-CN.md](README.zh-CN.md)** · [`docs/zh/`](docs/zh/)

## Why

magpie's job is simple: unify every agent's model egress behind one local port and
route it to DeepSeek / Kimi / GLM / Claude / GPT / local models, failing over
between accounts. On a phone that is exactly what dsh-go needs — its "add a
channel" dialog takes an OpenAI-compatible base URL and key.

Two obstacles: there is no magpie build for Android, and Android's rules for
processes, files and executables are nothing like a desktop's. So this repo is
not a rewrite — it is a shell that lets the real engine live on Android.

## What this is / is not

**Is** — an Android host (service, UI, WebView console), a record of the four
Android-specific traps that break a "looks correct" APK
([`docs/zh/03-android-porting.md`](docs/zh/03-android-porting.md)), and a
reproducible build from upstream source to a signed APK.

**Is not** — a magpie fork (no magpie source is vendored), an official release
(upstream has no Android support), or a reimplementation of a model client.

## Install

1. Grab `magpie.apk` from [Releases](../../releases). 64-bit Android 8.0+, and
   **≥ 400 MB free** (70 MB APK, 175 MB installed, ~350 MB peak during install).
   With too little space the installer also says "not compatible", which is easy
   to misread — see [`docs/zh/06-troubleshooting.md`](docs/zh/06-troubleshooting.md).
2. Open it, wait for the status light to turn green, allow battery-optimisation
   exemption and boot autostart.
3. Tap **Open console** → Providers → add a channel. Either a subscription login
   (Claude / ChatGPT / Copilot / WorkBuddy / Gemini CLI — opens the system browser)
   or a plain API key.

## Wire up dsh-go

dsh-go runs in PRoot, which does **not** create a network namespace — its
`127.0.0.1` is the phone's `127.0.0.1`. That is why the two apps can talk.

In dsh-go: **Settings → Models → Add channel → OpenAI-compatible**

| Field | Value |
| --- | --- |
| Base URL | `http://127.0.0.1:3425/v1` |
| API Key | `magpie` |
| Models | tap "fetch models", select, save |

Details and the exact `cordis.patch.yml` shape:
[`docs/zh/04-dsh-go-integration.md`](docs/zh/04-dsh-go-integration.md).

## The four Android traps

Every one of them compiles fine and fails only on device or at install time.

1. **Pure-Go builds cannot resolve DNS.** With `GOOS=android` the `net` package
   wants the cgo resolver; a pure-Go binary starts and listens, then every hostname
   lookup fails. You need NDK clang and `CGO_ENABLED=1`.
2. **Bun cannot live in the app's private directory.** Android 10+ W^X refuses
   `execve` there: `fork/exec .../magpie-bun: permission denied`. Ship
   `bun-linux-aarch64-android` as `lib/arm64-v8a/libbun.so` and point `MAGPIE_BUN`
   at it.
3. **`resources.arsc` must be STORED.** Android 11+ rejects compressed resource
   tables, and **.NET's `CompressionLevel.NoCompression` still writes DEFLATE**
   (level 0). Hence `app/pack.py` (Python `zipfile`) instead of
   `System.IO.Compression`.
4. **WebView swallows `window.open`.** Subscription login returns `null` from
   `openSignInPage`, which magpie reads as "popup blocked" — the UI then looks like
   "API key only". Fixed with `onCreateWindow` plus an injected `window.open` shim
   that returns a placeholder window object.

## Build

Requires **JDK 17**, **Android SDK** (build-tools + one platform), **Go 1.27+**,
**Android NDK r28+**, **Python 3**.

```powershell
# 1) engine, from upstream source (clone yetone/magpie first)
.\scripts\build-engine.ps1 -Magpie D:\src\magpie -Out app\jniLibs\arm64-v8a

# 2) APK
$env:ANDROID_SDK_ROOT = 'C:\Android\sdk'; $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\build.ps1

# UI-only build (no engine, ~50 KB) — good for checking sources compile
.\build.ps1 -NoEngine
```

No Gradle: `aapt2 compile → aapt2 link → javac → d8 → pack.py → zipalign -P 16 →
apksigner (v1+v2+v3)`. Toolchain versions are auto-detected, every path is a
parameter, and a dev key is generated if none exists. Linux/macOS equivalents:
[`docs/zh/02-build.md`](docs/zh/02-build.md).

## Verification

Measured, not assumed — 22 read endpoints and the full write surface return 200;
`provider/test` reaches a real upstream (401 with a fake key); plugin installation
succeeds (0.2.36); subscription logins return real authorization URLs (447 / 454 /
546 chars); end-to-end `/v1/chat/completions` returns `pong from mock` with usage
in=5 out=3; Anthropic `/v1/messages` is correctly translated to OpenAI upstream;
APK passes `apksigner verify` (v1+v2+v3), `zipalign -c -P 16`, and 16 KB page
alignment. Full matrix: [`docs/zh/05-verification.md`](docs/zh/05-verification.md).

**Not verified:** running on a real device — the build machine has no Android
device (`adb devices` empty, Hyper-V disabled). UI layout, OAuth callback and
foreground-service survival on vendor ROMs still need a phone.

## Limitations

- `arm64-v8a` only; 32-bit devices get `INSTALL_FAILED_NO_MATCHING_ABIS`.
- 69.9 MB (engine 92.1 MB + Bun 82.8 MB, compressed). Dropping Bun gets ~36 MB.
- No self-update (disabled — nothing to install on Android).
- The local PII model (1.6 GB of weights) is not bundled.
- Engine version string is hardcoded (`0.1.4384`) and lags upstream `v0.1.4386`.
- Single instance only — the config dir is fixed, two instances fight over it.

## Docs

| Document | Contents |
| --- | --- |
| [`docs/zh/01-architecture.md`](docs/zh/01-architecture.md) | Process model, ports, env vars, layout, console auth |
| [`docs/zh/02-build.md`](docs/zh/02-build.md) | Full toolchain walkthrough |
| [`docs/zh/03-android-porting.md`](docs/zh/03-android-porting.md) | The four traps, in depth |
| [`docs/zh/04-dsh-go-integration.md`](docs/zh/04-dsh-go-integration.md) | dsh-go side of the wiring |
| [`docs/zh/05-verification.md`](docs/zh/05-verification.md) | Verification matrix and repro commands |
| [`docs/zh/06-troubleshooting.md`](docs/zh/06-troubleshooting.md) | Install / start / plugin / login decision trees |
| [`docs/zh/07-reverse-engineering.md`](docs/zh/07-reverse-engineering.md) | dsh-go package layout (interoperability notes) |
| [`docs/zh/08-roadmap.md`](docs/zh/08-roadmap.md) | 32-bit, slimming, device testing, CI |

## Credits & disclaimer

The engine, gateway, console and protocol translation are all
[yetone/magpie](https://github.com/yetone/magpie) (MIT, © 2026 yetone). This
repository contributes only the Android host. Bun © Oven (MIT). `dsh-go` is a
third-party app that is **neither redistributed nor modified** here.

**This is not an official project.** Upstream does not accept external PRs and
does not support Android — please file issues **here**, not upstream. See
[`NOTICE`](NOTICE) for third-party terms. You are responsible for your accounts,
quota and compliance when wiring subscription logins.
