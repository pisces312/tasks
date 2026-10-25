# 架构

多模块 Android 项目，并含 Kotlin Multiplatform 能力（`composeApp` 支持 desktop / iOS）。

## 模块

| 模块 | 用途 |
|---|---|
| `app/` | 主 Android 应用（UI、注入、备份、Git 同步等 fork 定制集中在这里） |
| `data/` | 数据层（Room DB、DAO、实体） |
| `composeApp/` | Kotlin Multiplatform Compose UI（desktop / iOS） |
| `kmp/` | KMP 共享代码（CalDAV、ical、API provider、过滤器、时间格式化等） |
| `icons/` | 应用图标资源 |
| `wear/` | Android Wear 应用 |
| `wear-datalayer/` | Wear 数据层（gRPC） |

`kmp` 的目标：`androidTarget`、`jvm`、`iosArm64`、`iosSimulatorArm64`。

## Flavor 与交付

- `generic` —— **交付默认**，无 Google 服务。
- `googleplay` —— Play Services、billing、地图等。

正式版只出 `generic` + `arm64-v8a`，命令与产物路径见
[`build-and-environment.md`](build-and-environment.md#交付形态固定)。

`generic` 不含 `play-services-oss-licenses`（该依赖是 `googleplayImplementation`），因此
"第三方许可证"入口按 Activity 可解析性隐藏（`HelpAndFeedback.canOpenThirdPartyLicenses()`）；
上游只用 `isGooglePlay` 判断，没有这一步，合上游时要确认它没被覆盖掉。

按 flavor 划分的 source set：

- `main/` —— 共享代码
- `generic/` —— 仅 generic flavor
- `googleplay/` —— 仅 googleplay flavor（Play Services、billing、地图）

## 关键技术

- DI：Hilt / Dagger
- 数据库：Room 3（`androidx.room3`）+ KSP
- UI：Compose 与 ViewBinding/DataBinding 混合
- 网络：Ktor + OkHttp
- 同步：JGit（Git 同步，fork 定制）、Google Tasks API、CalDAV、Etebase

## debug 变体的包名契约（fork 定制，容易踩）

debug 包名是 `org.tasks.debug`（`applicationIdSuffix = ".debug"`），可与正式版共存。以下三处
**必须同时成立**，否则 debug 包会去访问正式版的 provider / Content Provider API 会静默失效：

1. `app/build.gradle.kts` 的 `buildTypes.debug` 保留
   `resValue("string", "opentasks_authority", "<applicationId>.debug.opentasks")`。
   上游 `defaultConfig` 的 `resValue` 在配置期求值，只等于基础包名；且
   **`src/debug/res/values/*.xml` 覆盖不了 `resValue`**。
2. `kmp/build.gradle.kts` 的 `android.buildTypes.debug` 保留
   `buildConfigField("String", "APPLICATION_ID", "\"<base>.debug\"")`。
   library 模块不吃 app 的 `applicationIdSuffix`，少了它 `TasksContract.AUTHORITY` /
   `PERMISSION_*` 在 debug 包里仍指向 `org.tasks.api`，与合并清单注册的
   `org.tasks.debug.api` 不一致，`TasksApiProvider` 的 `UriMatcher` 匹配不上。
3. `app/src/debug/AndroidManifest.xml` 保留红图标 `@mipmap/ic_launcher_red`（含
   `activity-alias` 的 `tools:replace`）与 dmfs 硬编码权限的 `tools:node="remove"`。

合并上游之后的必查项见 [`upstream-merge-conventions.md`](upstream-merge-conventions.md) 5.1 / 5.2。

## fork 定制一览

| 定制 | 位置 |
|---|---|
| Git 同步（JGit + mwiede jsch，含日志收集） | `app/`；设计见 [`git-sync-design.md`](git-sync-design.md) |
| 设置导出 / 导入 | `app/`；设计见 [`settings-export-import-design.md`](settings-export-import-design.md) |
| debug 红图标 + `.debug` 包名 | 见上一节 |
| ABI splits、环境变量签名 | `app/build.gradle.kts`；见 [`build-and-environment.md`](build-and-environment.md) |
| 中文支持（桌面端等） | 见 [`i18n.md`](i18n.md) |

`TasksJsonImporter.kt` 与上游逐字一致：曾经叠加的"导入时跳过 Microsoft 账户"已于
2026-10-24 revert（理由见 `docs/upstream-sync-2026-10-19.md` 9.3）。
