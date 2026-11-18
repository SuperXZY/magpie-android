# magpie-android build script
#   aapt2 compile -> aapt2 link -> javac -> d8 -> pack.py -> zipalign -> apksigner
#
# Usage (from anywhere):
#   pwsh -File build.ps1                       # build with the engine in jniLibs/
#   pwsh -File build.ps1 -NoEngine             # UI-only APK (no engine .so)
#   pwsh -File build.ps1 -Sdk D:\sdk -Jdk D:\jdk-17
#
# Environment fallbacks:
#   ANDROID_SDK_ROOT / ANDROID_HOME    Android SDK (needs build-tools + one platform)
#   JAVA_HOME                          JDK 17
#   MAGPIE_KEYSTORE_PASS               keystore password (default: magpieandroid)
param(
    [string]$Sdk      = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }),
    [string]$Jdk      = $env:JAVA_HOME,
    [string]$LibDir   = (Join-Path $PSScriptRoot 'app\jniLibs\arm64-v8a'),
    [string]$Out      = (Join-Path $PSScriptRoot 'build\magpie.apk'),
    [string]$Keystore = (Join-Path $PSScriptRoot 'app\keystore.jks'),
    [string]$StorePass = $(if ($env:MAGPIE_KEYSTORE_PASS) { $env:MAGPIE_KEYSTORE_PASS } else { 'magpieandroid' }),
    [switch]$NoEngine
)
$ErrorActionPreference = 'Stop'

$app = Join-Path $PSScriptRoot 'app'
$build = Join-Path $PSScriptRoot 'build'

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Die($msg)  { Write-Host "!!  $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- toolchain
if (-not $Sdk -or -not (Test-Path $Sdk)) { Die 'Android SDK not found. Pass -Sdk or set ANDROID_SDK_ROOT.' }
if (-not $Jdk -or -not (Test-Path $Jdk)) { Die 'JDK not found. Pass -Jdk or set JAVA_HOME.' }

# newest build-tools, newest platform
$btRoot = Join-Path $Sdk 'build-tools'
if (-not (Test-Path $btRoot)) { Die "no build-tools under $Sdk" }
$btVer = Get-ChildItem $btRoot -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$','') } -ErrorAction SilentlyContinue |
    Select-Object -Last 1
if (-not $btVer) { Die "no build-tools under $btRoot" }
$bt = $btVer.FullName

$platRoot = Join-Path $Sdk 'platforms'
if (-not (Test-Path $platRoot)) { Die "no platforms under $Sdk" }
$plat = Get-ChildItem $platRoot -Directory |
    Sort-Object { [int]($_.Name -replace '[^0-9].*$','') } |
    Select-Object -Last 1
$jar = Join-Path $plat.FullName 'android.jar'
if (-not (Test-Path $jar)) { Die "android.jar not found in $($plat.FullName)" }

$java = Join-Path $Jdk 'bin'
Write-Host "SDK   : $Sdk"
Write-Host "tools : build-tools $($btVer.Name) / $($plat.Name)"
Write-Host "JDK   : $Jdk"

# ------------------------------------------------------------------ clean
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
foreach ($d in 'gen','classes','dex','res') { New-Item -ItemType Directory -Force (Join-Path $build $d) | Out-Null }

# ---------------------------------------------------------------- resources
# The launcher/notification PNGs are kept as base64 inside scripts\gen-icons.py
# so the repository stays text-only. Regenerate them on first build.
if (-not (Test-Path (Join-Path $app 'res\mipmap-mdpi\ic_launcher.png'))) {
    Step 'icons (base64 -> png)'
    & python (Join-Path $PSScriptRoot 'scripts\gen-icons.py')
    if ($LASTEXITCODE -ne 0) { Die 'icon generation failed' }
}

Step 'aapt2 compile'
& "$bt\aapt2.exe" compile --dir (Join-Path $app 'res') -o (Join-Path $build 'res\res.zip')
if ($LASTEXITCODE -ne 0) { Die 'aapt2 compile failed' }

Step 'aapt2 link'
& "$bt\aapt2.exe" link -o (Join-Path $build 'base.apk') `
    -I $jar `
    --manifest (Join-Path $app 'AndroidManifest.xml') `
    (Join-Path $build 'res\res.zip') `
    --java (Join-Path $build 'gen') `
    --min-sdk-version 26 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { Die 'aapt2 link failed' }

# ------------------------------------------------------------------- java
Step 'javac'
$srcs = @(Get-ChildItem (Join-Path $app 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += Join-Path $build 'gen\ai\usemagpie\android\R.java'
& "$java\javac.exe" -source 11 -target 11 -encoding UTF-8 -nowarn -classpath $jar -d (Join-Path $build 'classes') $srcs
if ($LASTEXITCODE -ne 0) { Die 'javac failed' }

Step 'd8'
$classes = @(Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --lib $jar --min-api 26 --output (Join-Path $build 'dex') $classes
if ($LASTEXITCODE -ne 0) { Die 'd8 failed' }

# ------------------------------------------------------------------- pack
# pack.py pins resources.arsc to ZIP_STORED; .NET's CompressionLevel.NoCompression
# still emits DEFLATE(level 0), which makes Android 11+ refuse the installation.
$libs = if ($NoEngine) { $null } else { $LibDir }
if ($libs -and -not (Test-Path $libs)) {
    Die "engine dir not found: $libs`n   run scripts\build-engine.ps1 first, or pass -NoEngine for a UI-only APK"
}
$engineCount = if ($libs) { @(Get-ChildItem $libs -Filter *.so).Count } else { 0 }
if (-not $NoEngine -and $engineCount -eq 0) { Die "no .so in $libs - use -NoEngine for a UI-only APK" }
Step ("pack (engine libs: {0})" -f $(if ($NoEngine) { 'none' } else { $engineCount }))
$packArgs = @((Join-Path $build 'base.apk'), (Join-Path $build 'dex\classes.dex'), (Join-Path $build 'unaligned.apk'))
if ($libs) { $packArgs = @((Join-Path $build 'base.apk'), (Join-Path $build 'dex\classes.dex'), $libs, (Join-Path $build 'unaligned.apk')) }
& python (Join-Path $app 'pack.py') @packArgs
if ($LASTEXITCODE -ne 0) { Die 'pack failed' }

# -------------------------------------------------------------- align/sign
Step 'zipalign -P 16'
& "$bt\zipalign.exe" -P 16 -f 4 (Join-Path $build 'unaligned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { Die 'zipalign failed' }

Step 'apksigner'
if (-not (Test-Path $Keystore)) {
    Write-Host "    no keystore at $Keystore - generating a dev key (do NOT use for a store release)" -ForegroundColor Yellow
    & "$java\keytool.exe" -genkeypair -keystore $Keystore -storepass $StorePass -keypass $StorePass `
        -alias magpie -keyalg RSA -keysize 2048 -validity 10950 `
        -dname 'CN=magpie-android, OU=dev, O=magpie-android, C=CN' | Out-Null
}
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
& "$bt\apksigner.bat" sign --ks $Keystore --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $Out (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { Die 'apksigner failed' }

Step 'verify'
& "$bt\apksigner.bat" verify --print-certs $Out
& "$bt\aapt2.exe" dump badging $Out | Select-String -Pattern '^package|^sdkVersion|^targetSdkVersion|^native-code|^launchable-activity'

$size = [math]::Round((Get-Item $Out).Length / 1MB, 1)
Write-Host "`nOK  $Out  ($size MB)" -ForegroundColor Green
