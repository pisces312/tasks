# Tasks.org Android 构建日志

## 项目信息

| 项目 | 详情 |
|------|------|
| 名称 | Tasks.org (org.tasks) |
| 仓库 | https://github.com/tasks/tasks |
| 版本 | v15.2.1 (最新源码) |
| 语言 | Kotlin Multiplatform (KMP) |
| UI 框架 | Jetpack Compose Multiplatform |
| 依赖注入 | Hilt (Dagger) |
| 数据库 | Room |
| 许可证 | GPL-3.0 |
| 源码路径 | `D:\nili\3rd_party_projects\tasks-org-source` |

## 构建环境

| 工具 | 版本/路径 |
|------|-----------|
| OS | Windows 10 (x64) |
| JDK | 21 (`D:\nili\dev\AndroidStudio\jbr`) |
| Android SDK | `D:\nili\dev\android_sdk` (platforms 34/35/36.1) |
| Gradle | 9.4.1 (`D:\nili\dev\gradle`) |
| AGP | 9.1.0 |
| Kotlin | 2.3.10 |
| Shell | PowerShell |

## 构建配置

| 配置项 | 值 |
|--------|-----|
| compileSdk | 36 |
| targetSdk | 35 |
| minSdk | 26 |
| JVM Target | 17 |
| Product Flavors | fdroid / googleplay |
| 构建类型 | fdroid debug |
| Compose BOM | 2026.03.00 |
| R8 fullMode | false |
| JVM 堆内存 | -Xmx8G |

## 构建过程

### 1. 获取源码

```powershell
# GitHub 直连失败（Connection reset），改用 ghp.ci 镜像
git clone https://ghp.ci/https://github.com/tasks/tasks.git D:\nili\3rd_party_projects\tasks-org-source
```

### 2. 首次构建失败 — 任务名错误

```powershell
# 错误：flavor 名是 fdroid 不是 foss
D:\nili\dev\gradle\bin\gradle.bat assembleFossDebug --no-daemon
# → Task 'assembleFossDebug' not found
```

### 3. 第二次构建失败 — Windows 符号链接问题

```powershell
D:\nili\dev\gradle\bin\gradle.bat assembleFdroidDebug --no-daemon
# → 错误：values-in is not directory! Raw files should be placed in 'composeResources/files' directory
```

**原因：** Windows 上 Git 默认 `core.symlinks=false`，符号链接被创建为纯文本文件而非目录。项目中有 8 个 Git 符号链接：

| 符号链接路径 | 指向 |
|-------------|------|
| `app/src/main/res/values-in` | `values-id` |
| `app/src/main/res/values-iw` | `values-he` |
| `kmp/src/commonMain/composeResources/values-in` | `values-id` |
| `kmp/src/commonMain/composeResources/values-iw` | `values-he` |
| 4 个测试文件符号链接 | Freeze.kt, SuspendFreeze.kt, TestUtilities.kt, makers |

**修复方法：** 用 Python 将目标目录内容复制到符号链接路径：

```python
import shutil
import os

# kmp 模块 - 需要复制（原目录是文本文件而非目录）
src_base = "D:/nili/3rd_party_projects/tasks-org-source/kmp/src/commonMain/composeResources"
shutil.copytree(os.path.join(src_base, "values-id"), os.path.join(src_base, "values-in"))
shutil.copytree(os.path.join(src_base, "values-he"), os.path.join(src_base, "values-iw"))

# app 模块 - 已有正确目录，无需处理
```

### 4. 清理缓存

```powershell
D:\nili\dev\gradle\bin\gradle.bat clean --no-daemon
# BUILD SUCCESSFUL in 14s
```

### 5. 构建成功 ✅

```powershell
D:\nili\dev\gradle\bin\gradle.bat assembleFdroidDebug --no-daemon
# BUILD SUCCESSFUL in 3m 11s
# 101 actionable tasks: 82 executed, 19 from cache
```

## 构建产物

| 属性 | 值 |
|------|-----|
| APK 路径 | `D:\nili\3rd_party_projects\tasks-org-source\composeApp\build\outputs\apk\fdroid\debug\composeApp-fdroid-debug.apk` |
| 文件大小 | 43.64 MB |
| 构建变体 | fdroid debug |
| 对比预编译版 | F-Droid 预编译版 24 MB（release 版不含 debug 信息，资源已压缩） |

## 编译警告摘要

- **AGP 9.x 废弃警告：** `android.enableR8.fullMode=false`、`android.builtInKotlin=false`、`android.newDsl=false` 等选项已废弃
- **KMP 兼容性警告：** `org.jetbrains.kotlin.multiplatform` 插件与 AGP 9.0.0+ 不兼容，建议迁移到新项目结构
- **日志 API 废弃：** 大量 `fun d/w/e/v/i(withTag: String, ...)` 调用需改为 throwable-first 重载
- **kotlinx.datetime 废弃：** `Instant` 类建议改用 `kotlin.time.Instant`
- **ClickableText 废弃：** 建议改用 `LinkAnnotation`

## 关键经验

1. **Windows 符号链接是常见坑：** KMP 项目常用符号链接做语言别名（如 `values-in` → `values-id`，ISO 639-2 的 "in" 已改为 "id"），Windows 上必须手动复制
2. **Product Flavor 命名：** Tasks.org 用的是 `fdroid` 而非 `foss`，需先 `gradle tasks` 确认
3. **KMP + Compose 的模块结构：** Android 应用入口在 `composeApp` 模块而非 `app` 模块
4. **Debug APK 体积大：** 包含 debug 符号和未压缩资源，约为 release 版的 1.8 倍

## Release 构建

Release 构建需要本地 keystore 和更大内存。

### 1. 创建 keystore

```powershell
keytool -genkeypair -v -keystore tasks-release.jks `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -alias tasks -storepass tasks123 -keypass tasks123 `
  -dname "CN=Tasks, OU=Dev, O=pisces312, L=Shanghai, ST=Shanghai, C=CN"
```

### 2. gradle.properties 配置

```properties
org.gradle.jvmargs=-Xmx8G -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
tasksStoreFile=tasks-release.jks
tasksKeyAlias=tasks
tasksStorePassword=tasks123
tasksKeyPassword=tasks123
```

注意：properties 中每行末尾不要加反斜杠续行符，PowerShell 会错误解析。

### 3. 构建

```powershell
.\build.ps1 release  # 构建 release 版
.\build.ps1 debug    # 构建 debug 版
```

## 构建产物

| 构建类型 | 路径 | 大小 |
|---------|------|------|
| fdroid debug | `composeApp/build/outputs/apk/fdroid/debug/composeApp-fdroid-debug.apk` | 43.64 MB |
| fdroid release | `composeApp/build/outputs/apk/fdroid/release/composeApp-fdroid-release.apk` | **35.25 MB** |

## 构建日期

2026-04-20
2026-04-20（release）

## 安全审计（2026-04-20）

### 审计范围
- AndroidManifest 权限声明
- Analytics / 遥测数据发送
- 网络请求 / 服务器地址
- 敏感信息泄露
- 依赖库安全性

### 审计结论：✅ 安全通过

| 检查项 | 结果 |
|--------|------|
| 隐私权限（位置/通讯录/通话等） | ✅ 未声明任何隐私权限 |
| Analytics / 遥测 | ✅ fdroid 版为空实现，不发送任何数据 |
| 第三方追踪 SDK | ✅ 无（PostHog 仅桌面版启用，Android 版不初始化） |
| 后台偷偷传输 | ✅ 无，所有同步均为用户主动配置 |
| 硬编码密钥 / API Key | ✅ 无 |
| 网络安全 | ✅ 使用 HTTPS |
| allowBackup | ⚠️ ndroid:allowBackup="true"（可通过 db backup 备份数据，风险低） |

### 详细分析

**Analytics 实现（composeApp/src/androidMain/kotlin/org/tasks/di/AndroidModule.kt）：**

`kotlin
factory<Reporting> {
    object : Reporting {
        override fun logEvent(event: String, vararg params: Pair<String, Any>) {}
        override fun addTask(source: String) {}
        override fun completeTask(source: String) {}
        override fun identify(distinctId: String) {}
        override fun reportException(t: Throwable, fatal: Boolean) {}
    }
}
`

Android fdroid 版本的 Reporting 接口是**空实现**——所有方法体都是空的，不记录、不发送任何数据。

**PostHog 仅在以下条件同时满足时启用：**
1. 配置了 	asks_posthog_key（fdroid release 为空字符串）
2. 运行在 desktop 平台

**数据同步说明：**
- CalDAV / Google Tasks / WebDAV / Taskwarrior 同步均为用户主动配置的服务
- 不存在未经用户同意的后台上传行为

### 审计工具
- 人工代码审查
- grep / Select-String 关键词扫描
- AndroidManifest.xml 权限分析
- DI 模块注入链追踪

