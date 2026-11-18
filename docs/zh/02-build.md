# 02 · 构建

从上游源码到一把装得上的 APK，全流程。本文的每条命令都在 Windows 11 + 下列版本上
实际跑过，输出原样贴出。

## 工具链

| 组件 | 实测版本 | 说明 |
| --- | --- | --- |
| JDK | 17（`javac 17`） | 只用来编 Java 11 字节码、跑 `keytool` / `apksigner` |
| Android SDK | build-tools **36.0.0**，platform **android-36** | 只用 `aapt2` `d8` `zipalign` `apksigner` `keytool`，不装 Gradle |
| Go | **1.27.4** | 交叉编译引擎 |
| Android NDK | **r28c**（`28.2.13676358`） | 提供 `aarch64-linux-android26-clang` |
| Python | 3.13 | 打包（`app/pack.py`），必须是 3.x |

`build.ps1` 会自动探测 build-tools 与 platform 的最新版本，所以版本号不必完全一致，
但 **build-tools ≥ 34**（`zipalign -P 16` 才有 16 KB 页对齐）与 **NDK ≥ r26**
（16 KB 页对齐的产物）是硬要求。

## 0. 准备目录

```powershell
git clone --depth 1 https://github.com/yetone/magpie D:\src\magpie
git clone https://github.com/SuperXZY/magpie-android D:\src\magpie-android
```

引擎必须来自上游。本仓库**不含** magpie 源码——这是有意的：上游以天为单位发版，
把 160 MB 源码 vendor 进来只会让同步变成负担。

## 1. 编引擎

```powershell
cd D:\src\magpie-android
.\scripts\build-engine.ps1 -Magpie D:\src\magpie -Ndk D:\android-ndk-r28c
```

脚本做的事，逐条对应：

```powershell
$env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'
$env:CC  = "$Ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
$env:CXX = "$Ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd"
go build -tags nogui -trimpath -ldflags "-s -w -X main.version=0.1.4386" `
  -o app\jniLibs\arm64-v8a\libmagpie.so .
```

四个参数都不是随便加的：

| 参数 | 原因 |
| --- | --- |
| `CGO_ENABLED=1` + `CC` | **必须**。`GOOS=android` 时 Go 的 `net` 默认用 cgo 解析器；纯 Go 构建能启动、能监听，但每个域名解析都失败，表现为「网关在跑，加渠道超时」。 |
| `-tags nogui` | 去掉 Wails 桌面 GUI。手机上没有窗口系统，带上它只会把 WebKit 链接进来，编译失败或体积暴涨。 |
| `-trimpath` | 去掉构建机绝对路径，避免二进制里泄露 `D:\...`（也便于复现构建）。 |
| `-ldflags "-s -w"` | 去符号表与 DWARF，96 MB 里省掉约 30 MB。**不要**去掉——手机装不装得下很现实。 |

`-X main.version=` 注入的版本号只影响 `magpie --version` 和 App 界面上的显示，
与协议无关。App 里 `Magpie.version()` 是硬编码的另一份（见
[08-roadmap.md](08-roadmap.md) 的已知问题）。

### 校验产物

```powershell
$re = "$Ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
& $re -h app\jniLibs\arm64-v8a\libmagpie.so | Select-String 'Type:|Machine:'
& $re -d app\jniLibs\arm64-v8a\libmagpie.so | Select-String 'NEEDED'
```

实测输出：

```
  Type:    DYN (Shared object file)
  Machine: AArch64

 (NEEDED)  Shared library: [liblog.so]
 (NEEDED)  Shared library: [libdl.so]
 (NEEDED)  Shared library: [libc.so]
```

三个判断点：

1. `DYN` + `AArch64` —— 位置无关的可执行文件，Android 只认这个。
2. `NEEDED` 只有 `liblog/libdl/libc` —— 全是系统库，不需要额外分发。
3. **没有** `PT_INTERP = /lib/ld-linux-aarch64.so.1`。如果出现，说明用错了 triple
   （`aarch64-linux-gnu` 而不是 `aarch64-linux-android`），装到手机上会
   `exec format error` 或 `no such file or directory`。

## 2. 取 Bun（只有需要插件时才要）

插件系统跑在 Bun 上。magpie 自己会下载，但在 Android 上**下载下来的文件不可执行**
（W^X，见 [03-android-porting.md](03-android-porting.md)），所以必须随包内置。

```powershell
# magpie pin 的版本是 1.4.2，pin 的是 zip 的 SHA-256
$url = 'https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64-android.zip'
Invoke-WebRequest $url -OutFile bun.zip
Get-FileHash bun.zip -Algorithm SHA256
# 必须等于 a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274
# —— 这个常量来自上游 internal/plugin/bun.go 的 bunSums

Expand-Archive bun.zip -DestinationPath bun-x
Copy-Item bun-x\bun-linux-aarch64-android\bun app\jniLibs\arm64-v8a\libbun.so
```

两个容易搞错的点：

- pin 的是 **zip** 的哈希，不是解压出来的二进制的哈希。实测二进制的 SHA-256 是
  `f2970336bfccf36e4e8a408b25c627122f210e941672f184198c31f4dbd377a0`，
  与 `a1c7e298…` 不同，这是正常的，别以为下错了。
- 用 `MAGPIE_BUN` 指过去之后，magpie **不会**再校验哈希（校验只发生在下载路径）。
  所以这一步的可信度完全来自你自己验的 zip 哈希。

不需要插件（只用 API Key / 订阅登录）可以跳过这一步，并把 `libbun.so` 从
`jniLibs` 里删掉，APK 会从 69.9 MB 降到约 36 MB。

## 3. 打 APK

```powershell
$env:ANDROID_SDK_ROOT = 'C:\Android\sdk'
$env:JAVA_HOME        = 'C:\Program Files\Java\jdk-17'
.\build.ps1
```

六个阶段，每一步都值得知道在干什么：

| 阶段 | 命令 | 关键点 |
| --- | --- | --- |
| 资源编译 | `aapt2 compile --dir app\res -o res.zip` | 产出扁平化的资源包 |
| 资源链接 | `aapt2 link ... --min-sdk-version 26 --target-sdk-version 34` | 生成 `base.apk` + `R.java`；`resources.arsc` 在这里是 **STORED** |
| Java | `javac -source 11 -target 11 ...` | 只依赖 `android.jar`，没有别的 jar |
| dex | `d8 --lib android.jar --min-api 26 ...` | 单 dex，方法数远低于 64 K |
| 打包 | `python app\pack.py ...` | **不能用 .NET**：`NoCompression` 仍写 DEFLATE，`resources.arsc` 被压缩后 Android 11+ 直接拒装 |
| 对齐 | `zipalign -P 16 -f 4` | `-P 16` 是 16 KB 页对齐，Android 15+ 的硬要求 |
| 签名 | `apksigner sign --v1 --v2 --v3` | 三个都要开 |

只验证源码能编过（不需要引擎，产物约 50 KB）：

```powershell
.\build.ps1 -NoEngine
```

输出：

```
OK  D:\src\magpie-android\build\magpie.apk  (0 MB)
```

这个「0 MB」是因为四舍五入——实际 50,162 字节。它是个**只有界面、没有引擎**的包，
装上去能开、能看界面，但引擎启不来（会提示 `找不到引擎`）。用途只有一个：
判断「装不上」是 APK 结构问题还是体积/原生库问题（见
[06-troubleshooting.md](06-troubleshooting.md)）。

## 4. 签名

`build.ps1` 在 `app\keystore.jks` 不存在时会自动生成一把**开发用**密钥
（口令 `magpieandroid`）。发版前请自己换：

```powershell
keytool -genkeypair -keystore app\keystore.jks -storepass <你的口令> -keypass <你的口令> `
  -alias magpie -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=..., O=..., C=CN"

$env:MAGPIE_KEYSTORE_PASS = '<你的口令>'
.\build.ps1
```

- `keystore.jks` 已在 `.gitignore` 里，**永远不要提交**。
- 同一把密钥签出来的包才能互相覆盖升级；换了密钥必须先卸载旧版。
- `minSdk 26` 意味着 Android 7.0+ 用 v2/v3 就够，v1 事实上用不到，但留着不影响。
  注意 `apksigner` 在 `min-sdk 26` 下会**跳过** v1，这是规范行为，不是失败。

## 5. 验收

```powershell
$bt = "$env:ANDROID_SDK_ROOT\build-tools\36.0.0"
& "$bt\apksigner.bat" verify --print-certs build\magpie.apk
& "$bt\aapt2.exe" dump badging build\magpie.apk | Select-String 'package|sdkVersion|native-code'
& "$bt\zipalign.exe" -c -P 16 -v 4 build\magpie.apk | Select-Object -Last 3

# resources.arsc 必须是 STORED（method 0）
python -c "import zipfile;z=zipfile.ZipFile(r'build\magpie.apk');print([ (i.filename,i.compress_type) for i in z.infolist() if i.filename in ('resources.arsc','AndroidManifest.xml')])"
```

期望：

```
package: name='ai.usemagpie.android' versionCode='1' versionName='1.0.0'
targetSdkVersion:'34'
native-code: 'arm64-v8a'
[('AndroidManifest.xml', 8), ('resources.arsc', 0)]     ← 0 = ZIP_STORED
```

## Linux / macOS

工具链完全一样，只是路径与编译器名不同：

```bash
# 引擎（NDK 的 host tag 换成 linux-x86_64 / darwin-x86_64）
export GOOS=android GOARCH=arm64 CGO_ENABLED=1
export CC=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
export CXX=${CC}++
go build -tags nogui -trimpath -ldflags "-s -w -X main.version=0.1.4386" \
  -o app/jniLibs/arm64-v8a/libmagpie.so .

# APK：build.ps1 是 PowerShell；Linux 上需要 pwsh，或者把六个阶段照着抄成脚本
```

> `build.ps1` 在 Linux/macOS 上需要 PowerShell 7（`pwsh`）。纯 bash 版本目前
> **没有**提供——写了但没在 Linux 上验过的东西不如不写。如果你有 Linux 构建机，
> 照着上表把六条命令串起来就是 `build.sh`，欢迎提 PR。

## 体积账

| 组成 | 未压缩 | 进 APK 后 |
| --- | --- | --- |
| `libmagpie.so`（引擎） | 92.1 MB | ~34 MB |
| `libbun.so`（插件运行时） | 82.8 MB | ~33 MB |
| `classes.dex` + 资源 + manifest | < 200 KB | < 100 KB |
| **合计** | **175 MB** | **69.9 MB** |

安装时解压回 175 MB，峰值约 350 MB。这就是为什么 README 里反复强调「留够 400 MB」——
空间不足时安装器报的是「与系统不兼容」，而不是「空间不足」。

## 常见失败

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `go: no Go files` / `cannot find main module` | `-Magpie` 指错了目录 | 该目录下必须有 `go.mod` 与 `main.go` |
| `clang: not found` | `-Ndk` 指到了 NDK 根目录以外 | 应是含 `toolchains/llvm/prebuilt/...` 的那一层 |
| 链接期报 `undefined reference to __android_log_write` | 没走 NDK 的 clang 包装器 | `CC` 必须是 `aarch64-linux-android26-clang`，不是裸 `clang` |
| `aapt2 link` 报 `resource mipmap/ic_launcher not found` | `res/` 缺密度目录 | 五个 `mipmap-*` 都要在 |
| `d8` 报 `Type ... not found` | 忘了 `--lib android.jar` | 见 `build.ps1` |
| 打出来的包 40 KB，装机白屏 | 用了 `-NoEngine` | 那是故意没有引擎的包 |
| 装机报「与系统不兼容」 | 九成是 `resources.arsc` 被压缩 | 必须用 `app/pack.py`，见 [03-android-porting.md](03-android-porting.md) |
