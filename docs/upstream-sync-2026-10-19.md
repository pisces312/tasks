# 上游同步记录（2026-10-19）

> 本文是**执行记录**；可复用的合并约定见 [`upstream-merge-conventions.md`](./upstream-merge-conventions.md)。

将上游 `tasks/tasks` 的 15.13（992 个提交）合并进 pisces fork，并核验 fork 全部定制功能是否受影响。

## 1. 概要

| 项 | 值 |
|---|---|
| 分叉点 | `f0fbbc14`（2026-05-01），fork 在此之上有 16 个提交 |
| 上游目标 | `upstream/main` = `84ed8f5c20`（2026-10-18，v15.13） |
| 合并提交 | `34d26b372f` "Merge upstream/main (992 commits) into fork" |
| 同步分支 | `sync/upstream-20261019`（已 `--ff-only` 快进 `main`） |
| 回滚点 | `backup/pre-upstream-sync-20261019`（= 旧 `main` `4a2dbb626a`） |
| 变更规模 | 1624 文件，+330,710 / −45,525 |
| 冲突 | 15 项（14 文件 + `makers` 目录），全部手工解决 |
| 工具链变化 | Gradle 9.8.1、AGP 9.4.1、Kotlin 2.4、compileSdk 37 |
| 版本号机制 | 从 `libs.versions.toml` 迁到 `version.properties`（`15.13` / `151306`） |

## 2. 操作步骤

1. `git fetch upstream --tags` / `git fetch origin`
2. 建立回滚分支 `backup/pre-upstream-sync-20261019`，切出 `sync/upstream-20261019`
3. `git merge upstream/main --no-edit` → 15 项冲突
4. 逐项解决（见第 3 节），并修复上游升级引发的编译问题（见第 5 节）
5. 编译验证 debug / release 后提交 merge commit，`main` 快进到合并结果

## 3. 冲突清单与处理决定

| # | 文件 | 冲突类型 | 处理 |
|---|---|---|---|
| 1 | `.gitignore` | content | 保留 fork 的 `tasks-release.jks` / `*.log`，吸收上游 `xcuserdata/` |
| 2 | `gradle/wrapper/gradle-wrapper.properties` | content | 采用上游 9.8.1，保留腾讯镜像 URL |
| 3 | `gradle.properties` | content | 采用上游配置；删除失效的 `tasksStorePassword=tasks123` 明文凭据 |
| 4 | `gradle/libs.versions.toml` | content | 采用上游版本目录（`applicationId` 移入、AGP 9.4.1、compileSdk 37）；版本号改由 `version.properties` 提供 |
| 5 | `app/src/debug/AndroidManifest.xml` | content | 保留红图标 + `applicationIdSuffix`；采用上游 `allowBackup=false`；保留 dmfs 权限 `tools:node="remove"` |
| 6 | `app/src/debug/java/org/tasks/BuildSetup.kt` | content | 保留 `GitSyncLogCollector.timberTree`，叠加上游 kermit `minSeverity=Verbose` |
| 7 | `app/src/release/java/org/tasks/BuildSetup.kt` | content | 同上（`Severity.Debug`） |
| 8 | `app/src/debug/res/values/keys.xml` | 自动合并 | 保留 `app_package=org.tasks.debug`；`opentasks_authority` 见第 5.1（本次回归） |
| 9 | `app/src/main/java/org/tasks/backup/TasksJsonImporter.kt` | content ×4 | Microsoft→本地导入叠加到上游 `canonicalized` / `accountUuidMap` / `calendarUuidMap` / `perTaskDirty` 结构；详见 3.1 |
| 10 | `app/src/main/java/org/tasks/preferences/fragments/BackupsViewModel.kt` | content ×4 | 保留设置导出/导入；采用上游 `suspend formatLastBackup`；移除已删除的 `p_leakcanary` 忽略键 |
| 11 | `app/src/main/res/values/strings.xml` | content | 保留 git sync + 设置导入导出文案；跟随上游删除 blog 通知条目 |
| 12 | `app/src/main/res/values-zh-rCN/strings.xml` | content | 保留设置导入导出中文；`export_toast` 采用上游措辞 |
| 13–15 | `app/src/androidTest/.../{Freeze,SuspendFreeze,TestUtilities}.kt`、`makers/` | modify/delete | 接受上游删除；测试源已迁至 `app/src/sharedTest/java`（上游注释明确"不可移植到 Windows"，替代 fork 的占位方案） |
| 16 | `app/google-services.json` | 非冲突但缺失 | 上游把文件从 `app/src/debug/` 移到 `app/`；补回 `org.tasks` client，使 release/debug 两个变体都能匹配 |

> 注：表中编号以"处理项"计；git 报告的原始冲突项为 15 条。

### 3.1 Microsoft → 本地任务导入（语义冲突，行为有变更）

fork 原实现分两处：导入 `caldavAccounts` 时跳过 Microsoft 账号并记 UUID；导入 `caldavCalendars` 时把 Microsoft 日历改挂本地账号；导入任务时用**已被改写的日历**去比对 Microsoft UUID。

问题：任务阶段读到的 `caldavTask.calendar` 已被重定向到本地账号，`microsoftAccountUuids.contains(...)` 永远不成立 → 清除 `remoteId` / `remoteParent` 的分支实际是死代码。

合并后改为在**日历阶段**记录来源日历 UUID 集合 `microsoftCalendarUuids`，任务阶段用 `caldavTask.calendar`（原始值）比对：

```kotlin
val originalCalendarUuid = caldavTask.calendar
val isFromMicrosoft = originalCalendarUuid != null &&
    microsoftCalendarUuids.contains(originalCalendarUuid)
caldavDao.insert(caldavTask.copy(
    task = taskId,
    calendar = remappedCalendar,
    remoteId = if (isFromMicrosoft) null else caldavTask.remoteId,
    remoteParent = if (isFromMicrosoft) null else caldavTask.remoteParent,
))
```

这是**行为变更**：该功能现在真正生效（此前 Microsoft 任务仍带 `remoteId`）。

## 4. fork 定制功能影响核查

核查方法：`fork 改动文件清单` × `合并后文件存在性` × `源码内容比对` × `debug/release APK 逆向校验`。

| # | fork 功能（提交） | 涉及文件 | 合并后状态 | 证据 |
|---|---|---|---|---|
| 1 | Git 同步核心（`3ea8a1f628`） | `GitSyncManager` / `GitJsonExporter` / `SshKeyManager` / `TasksSshSessionFactory` / `GitTransportCallback` / `GitSyncStep` / `GitSync` fragment | ✅ 完整保留 | 10 个文件全部存在；`assemble*` 通过 |
| 2 | Git 同步日志面板（`0a43d153db`） | `GitSyncScreen.kt`、`GitSyncLogCollector.kt`、`BuildSetup.kt` | ✅ 保留（图标库已适配） | 面板/自动滚动/清空逻辑在；`Timber.plant(GitSyncLogCollector.timberTree)` debug+release 均在 |
| 3 | Git 同步时间显示 | `GitSyncViewModel.kt` | ✅ 已适配 | `getFullDateTime` 上游删除 → 改用 `DateFormatter.create(...).fullDateTime()`（suspend），摘要改为协程内构建 |
| 4 | 设置导出/导入（`f23adeff6d`） | `BackupsViewModel.kt`、`BackupsScreen.kt`、`Backups.kt`、strings | ✅ 完整保留 | 7 个字符串 en/zh 各 1；`onExportSettings` / `onImportSettings` 回调接线完整；忽略键表已剔除 `p_leakcanary` |
| 5 | debug 独立包名（`5171589cdf`） | `app/build.gradle.kts`（`applicationIdSuffix=".debug"`）、`keys.xml` | ✅ 保留 | APK badging：`package: name='org.tasks.debug'` |
| 6 | debug 红图标（`5171589cdf`） | `app/src/debug/AndroidManifest.xml` | ✅ 保留 | 合并清单 `android:icon=@mipmap/ic_launcher_red`；资源随上游迁至 `kmp/src/androidMain/res` |
| 7 | debug OpenTasks authority（`55a0fbe0da`） | `app/build.gradle.kts`、`keys.xml` | ⚠️ **合并时回归，已修复** | 见 5.1；现 debug APK 内 `opentasks_authority=org.tasks.debug.opentasks` |
| 8 | debug 权限改写（`55a0fbe0da`） | `app/src/debug/AndroidManifest.xml` | ✅ 保留 | 合并清单无 `org.tasks.permission.*`，自定义权限为 `org.tasks.debug.permission.*` |
| 9 | ABI splits（`a36a14f4a7`） | `app/build.gradle.kts` | ✅ 保留 | `splits.abi { ... -PbuildAbi }`；产出 `app-generic-arm64-v8a-*.apk` |
| 10 | 环境变量签名（`a36a14f4a7` / `93362eb416`） | `app/build.gradle.kts` | ✅ 保留 | release APK `apksigner verify`：`CN=pisces312` |
| 11 | 禁用 oss-licenses 插件 | `app/build.gradle.kts` | ✅ 保留 | 插件行仍注释 |
| 12 | JGit + mwiede jsch（Git 同步依赖） | `app/build.gradle.kts`、`app/proguard.pro` | ✅ 保留且经 R8 验证 | 依赖 3 条在；`mapping.txt` 中 31,655 条 `org.eclipse.jgit.*` 未裁剪 |
| 13 | Microsoft → 本地导入（`8ddde1750e` / `bd189c39dd`） | `TasksJsonImporter.kt` | ✅ 保留，行为修正 | 见 3.1 |
| 14 | 设置按钮状态栏遮挡修复（`e492b1e383`） | `HomeScreen.kt` | ✅ 保留 | `LaunchedEffect(currentWindowInsets) { windowInsets.value = currentWindowInsets }` |
| 15 | 任务列表内边距立即生效 | `TaskListFragment.kt` | ✅ 保留（自动合并） | `updatePadding(top=...)` 后仍有 `requestLayout()` |
| 16 | Windows 符号链接兼容（`57f6130d77`） | `app/src/androidTest/**` 占位文件 | ➖ 已被上游方案取代 | 上游改用 `app/src/sharedTest/java`；仓库内符号链接数 = 0 |
| 17 | 构建脚本（`ffcd514358`） | `build.sh`、`build.ps1` | ✅ 保留（路径已修） | `build.sh` 的 JBR 探测已补 `/d/dev/AndroidStudio/jbr`（原 `/d/nili/...` 已不存在） |
| 18 | 腾讯 Gradle 镜像 | `gradle-wrapper.properties` | ✅ 保留 | `distributionUrl=https://mirrors.cloud.tencent.com/gradle/gradle-9.8.1-all.zip` |
| 19 | 文档沉淀 | `BUILD_LOG.md`、`AGENTS.md`（原 `CLAUDE.md` 已并入）、`docs/*.md` | ✅ 全部存在 | — |
| 20 | fork 版本号（`4a2dbb626a`，`15.3-fork-1`） | `version.properties` | ⚠️ 被上游机制替换 | 当前 `15.13` / `151306`；见第 6 节 |

## 5. 本次发现的问题与修复

### 5.1 【回归，已修复】debug 变体的 OpenTasks authority 指向 release 包

- 现象：合并后 `app/build/generated/res/resValues/generic/debug/values/gradleResValues.xml` 中
  `opentasks_authority = "org.tasks.opentasks"`（release 的 authority）。
- 原因：上游把该字符串改为 `defaultConfig` 的 `resValue("string", "opentasks_authority", "$applicationId.opentasks")`。
  `$applicationId` 在**配置期**求值，取的是基础包名 `org.tasks`，**不包含** `applicationIdSuffix=".debug"`。
  合并时按"上游已按 applicationId 生成、无需覆盖"的判断删掉了 fork 的 debug 覆盖 → 覆盖丢失。
- 影响：安装 release 版后再跑 debug 版，dmfs provider 会解析到 release 应用的 provider，触发
  `SecurityException`（正是 `55a0fbe0da` 当年修的问题）。
- 复现验证：源码集 `src/debug/res/values/keys.xml` 里的同名 `<string>` **无法覆盖** `resValue`（生成的
  resValues 优先级更高，`aapt2 dump resources` 仍为 `org.tasks.opentasks`）。
- 修复：在 `buildTypes.debug` 内按变体覆盖（`app/build.gradle.kts`）：

  ```kotlin
  debug {
      applicationIdSuffix = ".debug"
      ...
      resValue(
          "string",
          "opentasks_authority",
          "${libs.versions.applicationId.get()}.debug.opentasks",
      )
  }
  ```

- 验证：debug APK 内 `string/opentasks_authority = "org.tasks.debug.opentasks"`，release 仍为 `org.tasks.opentasks`。

### 5.2 【上游升级导致，已修复】Compose 编译指标在 Windows 上导致全量编译失败

- Kotlin 2.4 的 module 名为 `Tasks:app_genericDebug`，Compose 插件将其拼入指标文件名，`:` 在 Windows 非法 →
  `IrGenerationExtensionException: Invalid file path`。
- 修复：根 `build.gradle.kts` 改为仅当显式传 `-PcomposeMetrics=<dir>` 时开启指标（与上游 CI 用法一致）。

### 5.3 【上游 API 移除，已适配】

| 移除项 | 处理 |
|---|---|
| `androidx.compose.material.icons`（material-icons 依赖） | `GitSyncScreen` 改用项目自有 `TasksIcons` + `SymbolIcon` |
| `getFullDateTime()` | 改用 `DateFormatter.create(is24Hour).fullDateTime()`（suspend） |
| `p_leakcanary` | 从设置导出忽略键表移除 |

### 5.4 【环境】构建脚本 JBR 路径

`build.sh` 探测 `/d/nili/dev/AndroidStudio/jbr`（已不存在）；已改为优先 `/d/dev/AndroidStudio/jbr`，保留旧路径与 Adoptium 回退。`local.properties` 的 `sdk.dir` 同步修正为 `D:\dev\android_sdk`（该文件不入库）。

## 6. 遗留事项与风险

1. **版本号规则变更（需你决策）**
   - 版本来源于 `version.properties`：`VERSION_NAME=15.13`、`VERSION_CODE=151306`。
   - `settings.gradle.kts` 断言 `VERSION_CODE % 2 == 0`（wear 模块使用 `+1`）。
   - fork 原有的 `MAJOR.MINOR.PATCH.FORK_INC` + `YYMMDDNN` 编号需在"偶数"约束下取号，例如
     `VERSION_NAME=15.13.0-fork-1`、`VERSION_CODE=2026101900`（当天偶数序号）。
2. **Git 同步 JSON 与备份导入格式不兼容（合并前即如此，非回归）**
   - `GitJsonExporter` 输出 `{version, tasks, tags, ...}`（无 `data` 包裹），而 `TasksJsonImporter` 只解析
     `version` / `timestamp` / `data`，其余顶层键一律 `Skipping`。
   - 结论：git 仓库里的 `tasks.json` 只适合做版本化/人类可读快照，**不能**通过"导入备份"还原。
     若要支持还原，需给 `GitJsonExporter` 增加 `"data":{...}` 包裹。
3. **Git 同步界面无中文翻译**：`values-zh-rCN` 中 `git_sync*` 键数量为 0（合并前后一致），中文环境显示英文。
4. **真机验证尚未执行**：Git 同步往返（需 SSH key + 远端仓库）、设置导出/导入、Microsoft 旧备份导入
   建议在设备上各跑一遍；编译与打包只能证明接口/资源层正确。
5. **推送未执行**：本地 `main` 领先 `origin/main` 994 个提交，`git push` 需你显式确认。
6. **未跟踪文件**：`docs/agp-deprecation-migration-plan.md`、`.workbuddy/` 未纳入提交。

## 7. 验证记录

| 命令 | 结果 |
|---|---|
| `./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a` | BUILD SUCCESSFUL |
| `./gradlew :app:compileGenericReleaseKotlin -PbuildAbi=arm64-v8a` | BUILD SUCCESSFUL |
| `./gradlew :app:assembleGenericDebug -PbuildAbi=arm64-v8a` | BUILD SUCCESSFUL（3m19s） |
| `./gradlew :app:assembleGenericRelease -PbuildAbi=arm64-v8a -x uploadCrashlyticsMappingFileGenericRelease` | BUILD SUCCESSFUL（5m58s） |
| `aapt2 dump badging`（debug） | `org.tasks.debug` / `151306` / `15.13` |
| `aapt2 dump badging`（release） | `org.tasks` / `151306` / `15.13` |
| `aapt2 dump resources` | debug `org.tasks.debug.opentasks`；release `org.tasks.opentasks` |
| `apksigner verify --print-certs` | `CN=pisces312`（V2） |
| R8 `mapping.txt` | `org.eclipse.jgit.*` 31655 条保留 |
| 合并清单（debug） | `allowBackup=false`、红图标、无 `org.tasks.permission.*` |

## 8. 合并后补记（同日）

1. **OSS licenses 插件恢复启用**（`9c713c3dfa`）：Gradle 9.8.1 + AGP 9.4.1 下插件兼容，
   `googleplayRelease` 能正常生成 `raw/third_party_licenses` 与 `OssLicensesMenuActivity`。
2. **generic 的"第三方许可证"入口改为按可解析性隐藏**：
   `play-services-oss-licenses` 只有 `googleplayImplementation`，generic 包里没有该 Activity。
   现由 `HelpAndFeedback.canOpenThirdPartyLicenses()`（`PackageManager.queryIntentActivities`）
   决定是否渲染菜单项：generic 不显示、点击也不会 `ActivityNotFoundException`；
   googleplay 正常显示。上游只用 `isGooglePlay`（billing）判断，没有这层检查。
   - 数据流：`HelpAndFeedback` → `HelpAndFeedbackContent(showThirdPartyLicenses:)`
     → `HelpAndFeedbackScreen(showThirdPartyLicenses:)`（默认值回退到 `isGooglePlay`）。
3. **交付形态固定**：正式版只出 `generic` + `arm64-v8a`（无 Google 服务），已写入 `AGENTS.md`。
4. **本次构建验证**（`./build.sh` = `assembleGenericRelease -PbuildAbi=arm64-v8a`）：
   - `BUILD SUCCESSFUL in 3m 18s`
   - 产物 `app/build/outputs/apk/generic/release/app-generic-arm64-v8a-release.apk`，15,579,228 B
   - `aapt2 dump badging`：`org.tasks` / `151306` / `15.13` / `native-code: 'arm64-v8a'`
   - `apksigner verify --print-certs`：`CN=pisces312`，V2，SHA-256 `abadebd2fc9523628b5dacfa0fb40f652df0e161b71820c7a4b5a40653ee0b90`
   - 清单内 `oss.licenses` 相关条目数 **0**（符合 generic 预期）
   - 首轮构建曾因 `AAPT2 ... Daemon startup failed` 失败，`./gradlew --stop` 后重试即通过（环境偶发）
