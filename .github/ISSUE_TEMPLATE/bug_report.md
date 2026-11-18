---
name: Bug report
about: 安装失败、引擎起不来、功能异常
title: '[bug] '
labels: bug
---

## 先说一句

**「与系统不兼容，装不上」先看 [docs/zh/06-troubleshooting.md](../../docs/zh/06-troubleshooting.md) 的 A 节**，
九成是 `resources.arsc` 被压缩、空间不足或 ABI 不符。按那里的决策树走一遍，
大多数情况不用提 issue。

## 环境

| 项 | 值 |
| --- | --- |
| 机型 | |
| Android 版本 | |
| ABI | （`adb shell getprop ro.product.cpu.abi`） |
| magpie 版本 | （界面上显示的那个） |
| 装的是 | 完整包 / `magpie-mini.apk` / 自己构建的 |

## 现象

<!-- 什么时候开始的、点哪里、期望什么、实际什么 -->

## 错误信息

```
# 优先贴 adb 的原始输出，比界面上的提示有用得多
adb install -r magpie.apk
```

```
# 引擎日志
adb shell run-as ai.usemagpie.android cat files/magpie.log | tail -100
```

## 已经试过

- [ ] 装 `magpie-mini.apk` 验证是不是结构问题
- [ ] 检查剩余空间 ≥ 400 MB
- [ ] 加电池优化白名单
- [ ] 用 `adb install` 拿到真实错误码
