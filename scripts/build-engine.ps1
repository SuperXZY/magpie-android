# Cross-compile the magpie engine for Android arm64.
#
#   .\scripts\build-engine.ps1 -Magpie D:\src\magpie
#   .\scripts\build-engine.ps1 -Magpie D:\src\magpie -Ndk D:\android-ndk-r28c -Version 0.1.4386
#
# Output: app\jniLibs\arm64-v8a\libmagpie.so   (about 92 MB, unstripped-of-symbols but -s -w)
#
# Why CGO: with GOOS=android the Go net package defaults to the cgo resolver.
# A pure-Go build starts and listens, then fails every hostname lookup, which
# looks like "the gateway is up but adding a provider times out".
param(
    [Parameter(Mandatory = $true)][string]$Magpie,
    [string]$Out     = (Join-Path $PSScriptRoot '..\app\jniLibs\arm64-v8a'),
    [string]$Ndk     = $(if ($env:ANDROID_NDK_ROOT) { $env:ANDROID_NDK_ROOT } else { $env:ANDROID_NDK_HOME }),
    [string]$Go      = 'go',
    [int]$Api        = 26,
    [string]$Version = '0.1.4386',
    [string]$GoModCache = $env:GOMODCACHE,
    [string]$GoCache     = $env:GOCACHE
)
$ErrorActionPreference = 'Stop'

function Die($m) { Write-Host "!!  $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path (Join-Path $Magpie 'go.mod'))) { Die "no go.mod in $Magpie - point -Magpie at a yetone/magpie checkout" }
if (-not $Ndk -or -not (Test-Path $Ndk)) { Die 'Android NDK not found. Pass -Ndk or set ANDROID_NDK_ROOT.' }

# host tag of the NDK prebuilt toolchain
$hostTag = if ($env:OS -eq 'Windows_NT') { 'windows-x86_64' }
           elseif ($IsMacOS) { 'darwin-x86_64' }
           else { 'linux-x86_64' }
$binDir = Join-Path $Ndk "toolchains\llvm\prebuilt\$hostTag\bin"
if (-not (Test-Path $binDir)) {
    $binDir = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin"
}
if (-not (Test-Path $binDir)) { Die "NDK toolchain not found: $binDir" }

$ext  = if ($env:OS -eq 'Windows_NT') { '.cmd' } else { '' }
$clang   = Join-Path $binDir "aarch64-linux-android$Api-clang$ext"
$clangxx = Join-Path $binDir "aarch64-linux-android$Api-clang++$ext"
if (-not (Test-Path $clang)) { Die "clang wrapper not found: $clang" }

New-Item -ItemType Directory -Force $Out | Out-Null
$target = Join-Path (Resolve-Path $Out) 'libmagpie.so'

Write-Host "magpie : $Magpie"
Write-Host "ndk    : $Ndk ($hostTag, api $Api)"
Write-Host "out    : $target"

$env:GOOS        = 'android'
$env:GOARCH      = 'arm64'
$env:CGO_ENABLED = '1'
$env:CC          = $clang
$env:CXX         = $clangxx
if ($GoModCache) { $env:GOMODCACHE = $GoModCache }
if ($GoCache)    { $env:GOCACHE    = $GoCache }

Push-Location $Magpie
try {
    & $Go build -tags nogui -trimpath `
        -ldflags "-s -w -X main.version=$Version" `
        -o $target .
    if ($LASTEXITCODE -ne 0) { Die 'go build failed' }
}
finally {
    Pop-Location
}

$size = [math]::Round((Get-Item $target).Length / 1MB, 1)
Write-Host "`nOK  $target  ($size MB)" -ForegroundColor Green
Write-Host @"

Sanity check with the NDK's own llvm-readelf (works on Windows too):

  & "$binDir\llvm-readelf.exe" -h "$target" | Select-String 'Type:|Machine:'
  & "$binDir\llvm-readelf.exe" -d "$target" | Select-String 'NEEDED'

Expected: DYN / AArch64, and NEEDED liblog.so, libdl.so, libc.so only.
A static-pie or a PT_INTERP pointing at /lib/ld-linux-aarch64.so.1 means the
wrong target triple got picked up and the binary will not exec on Android.
"@
