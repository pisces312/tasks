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
| 9 | `app/src/main/java/org/tasks/backup/TasksJsonImporter.kt` | content ×4 | Microsoft→本地导入叠加到上游 `canonicalized` / `accountUuidMap` / `calendarUuidMap` / `perTaskDirty` 结构；详见 3.1。**该定制已于 2026-10-24 revert，见 9.3** |
| 10 | `app/src/main/java/org/tasks/preferences/fragments/BackupsViewModel.kt` | content ×4 | 保留设置导出/导入；采用上游 `suspend formatLastBackup`；移除已删除的 `p_leakcanary` 忽略键 |
| 11 | `app/src/main/res/values/strings.xml` | content | 保留 git sync + 设置导入导出文案；跟随上游删除 blog 通知条目 |
| 12 | `app/src/main/res/values-zh-rCN/strings.xml` | content | 保留设置导入导出中文；`export_toast` 采用上游措辞 |
| 13–15 | `app/src/androidTest/.../{Freeze,SuspendFreeze,TestUtilities}.kt`、`makers/` | modify/delete | 接受上游删除；测试源已迁至 `app/src/sharedTest/java`（上游注释明确"不可移植到 Windows"，替代 fork 的占位方案） |
| 16 | `app/google-services.json` | 非冲突但缺失 | 上游把文件从 `app/src/debug/` 移到 `app/`；补回 `org.tasks` client，使 release/debug 两个变体都能匹配 |

> 注：表中编号以"处理项"计；git 报告的原始冲突项为 15 条。

### 3.1 Microsoft → 本地任务导入（历史记录：该定制已于 2026-10-24 revert）

> 以下内容记录 2026-10-19 合并当时对这条 fork 定制做的移植与行为修正，**现已不适用**：
> 整段定制已从 `TasksJsonImporter.kt` 移除，文件与上游一致。原因与取证见 9.3。

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
| 13 | Microsoft → 本地导入（`8ddde1750e` / `bd189c39dd`） | `TasksJsonImporter.kt` | ↩️ 合并时保留，2026-10-24 已 revert | 见 3.1 与 9.3 |
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
4. **真机验证尚未执行**：Git 同步往返（需 SSH key + 远端仓库）、设置导出/导入
   建议在设备上各跑一遍；编译与打包只能证明接口/资源层正确。
   （原列的"Microsoft 旧备份导入"随 9.3 的 revert 一并取消：导入行为已与上游一致。）
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

## 9. CI 红灯复查（2026-10-24）

合并后 `.github/workflows/check.yml`（"Assemble bundle"）从 `34d26b372f` 之后**每次 push 都红**：
`6d48e077`→`8f9a53c5` 连续 5 个 run 失败在同一批 job 上（最新一次
[run 39855270848](https://github.com/pisces312/tasks/actions/runs/39855270848)）。
逐条取证（`gh run view --log-failed` + 下载 `test-reports-*` artifact 读 HTML 报告）后，
确认是**四个互相独立**的原因，其中一个是假警报。上游 `tasks/tasks@478c8e9a` 同一 workflow 全绿，
可用作对照。

| # | job | 现象 | 定性 | 状态 |
|---|---|---|---|---|
| 1 | `iosTest` | `:kmp:linkDebugTestIosSimulatorArm64` Kotlin/Native 后端内部错误 | 上游已修的坑，fork 未合进回退 | 已修（待 CI 验证） |
| 2 | `jvmTest` | `RobolectricSmokeTest` 1/605 失败 | fork `.debug` 后缀 vs 上游测试硬编码 | 已修，本地两 flavor 全绿 |
| 3 | `instrumentedTest` | `TasksApiIntegrationTest` 7/7 失败 | fork `.debug` 后缀 → **debug 包 API provider 实际不可用** | 已修（kmp BuildConfig + 文档测试，待 CI connected 验证） |
| 3b | `instrumentedTest` | `TasksJsonImporterTest.deduplicateMicrosoftByUsername` | fork 导入特性 vs 上游测试期望 | 已修（revert fork 改动，见 9.3） |
| 4 | `instrumentedTest` | `TitleParserTest.testDays` 报告显示 failed | 假警报（`@Ignore`） | 无需处理 |

### 9.1 Kotlin 2.4.21 触发 K/N 编译器 bug

```
e: Compilation failed: Back-end: Please report this problem https://kotl.in/issue
Details: Internal error in file lowering: java.lang.IllegalStateException:
  IrConstructorCallImpl@... is not expected: CONSTRUCTOR_CALL 'public constructor <init>
  (vararg value: kotlin.String, orders: kotlin.Array<androidx.room3.Index.Order>,
   name: kotlin.String, unique: kotlin.Boolean) [primary] declared in androidx.room3.Index'
```

不是我们的代码问题：Kotlin 2.4.21 在把 `@Entity(indices = [Index(...)])` 的注解构造调用序列化进
klib 时后端崩。取证对照：上游 renovate 提交 `b9095f6656 "Update kotlin (#4757)"`（10-17，
`kotlin 2.4.10→2.4.21`、`ksp 2.3.11→2.3.12`）之后，上游 run `39009524931` 的 `iosTest` 在**同一任务、
同一行错误消息**上失败；作者当天用 `30b4e18463 "Downgrade Kotlin"` 退回
`kotlin = 2.4.10` / `ksp = 2.3.11`。fork 的合并点 `84ed8f5c20`（10-18）含升版、**不含**这条回退，
所以 HEAD 仍是 2.4.21。

修复：按上游把 `gradle/libs.versions.toml` 的 `kotlin`、`ksp` 降回 2.4.10 / 2.3.11。
本机是 Windows，iOS 目标不参与链接，**本地只能验证 Android/JVM 侧没被降级改坏**，
真正的通过与否由 CI 的 `iosTest` 判定。

> 状态（2026-10-24）：已按下表降级。对照证据 —— 上游在 Kotlin 2.4.10 下
> run `39339508387`（`5162b6f8f2`）的 `iosTest` 为 success，在 2.4.21 下
> run `39009524931` 的 `iosTest` 为 failure 且错误逐字相同。

| 项 | 改前 | 改后 |
|---|---|---|
| `kotlin` | 2.4.21 | 2.4.10 |
| KSP 插件 | 2.3.12 | 2.3.11 |

### 9.2 fork 的 `applicationIdSuffix = ".debug"` 撞上游新代码

`app/build.gradle.kts:89` 的 debug 后缀让 fork 的 debug 包名是 `org.tasks.debug`，
上游同位置的测试/常量都按"无后缀"写死。两处后果：

1. `RobolectricSmokeTest.androidResourcesAndManifestAreAvailable`（`app/src/test/.../RobolectricSmokeTest.kt:22`）
   断言 `assertEquals("org.tasks", context.packageName)`，实际
   `ComparisonFailure: expected:<org.tasks[]> but was:<org.tasks[.debug]>`。这是 jvmTest 唯一失败项。
   **已修复（2026-10-24）**：断言改为 `if (BuildConfig.DEBUG) "org.tasks.debug" else "org.tasks"`，
   既保留上游"合并后的 manifest 与 Android 资源可用"的原意，又把 fork 的 debug 后缀契约钉住。
   本地验证：`./gradlew --continue :app:testGenericDebugUnitTest :app:testGoogleplayDebugUnitTest`
   → BUILD SUCCESSFUL，generic 604 用例 / 0 失败 / 1 skipped，googleplay 605 / 0 / 1
   （与 CI 的用例数一致，CI 报的是 googleplay 605）。
2. **新 Content Provider API 在 debug 包里调不通**（比测试更实质）。上游 10.19 之前的
   `a15ecea680 "Add new content provider API"` 引入两套必须一致的 authority：
   - manifest：`android:authorities="${applicationId}.api"`（debug 解析成 `org.tasks.debug.api`）
   - 代码：`kmp/src/commonMain/kotlin/org/tasks/api/TasksContract.kt` 的
     `AUTHORITY = "${TasksBuildConfig.APPLICATION_ID}.api"`，而 android 侧
     `TasksBuildConfig.APPLICATION_ID` 取自 kmp library 的 `BuildConfig`
     （`kmp/build.gradle.kts` 的 `defaultConfig`，修复前恒为基础 id `org.tasks`；
     library 模块不吃 app 的 `applicationIdSuffix`）。
   `TasksApiProvider` 的 `UriMatcher` 也用它（`addURI(TasksContract.AUTHORITY, ...)`）。
   debug 包里注册 authority 与匹配 authority 不一致 → 外部按 `org.tasks.debug.api` 访问得到
   `Unknown URL`，因此 `TasksApiIntegrationTest` 7 个用例全挂
   （`Unknown authority org.tasks.api` / `Failed to find provider org.tasks.api for user 0`）。
   修法沿用第 5.1 节 `opentasks_authority` 的思路：给 kmp 的 `android.buildTypes.debug`
   加 `buildConfigField("String", "APPLICATION_ID", "\"<base>.debug\"")`，
   使 debug 变体的 kmp/data 与 manifest 对齐；release 不变。

   **已修复（2026-10-24）**：`kmp/build.gradle.kts` 新增 `android.buildTypes.debug` 的
   `buildConfigField`。配套改一处：`kmp/src/androidUnitTest/.../TasksContractDocTest.kt` 的
   `everyEndpointIsInTheReference()` 原先断言 `CONTENT_PROVIDER.md` 含
   `TasksContract.AUTHORITY / PERMISSION_READ / PERMISSION_WRITE`，而该文档写的是发布契约
   （`org.tasks.api`、`org.tasks.permission.*`）；CI 跑的是 `kmp:testDebugUnitTest`，
   改了 debug APPLICATION_ID 后这三条必然失败。改为对
   `TasksBuildConfig.APPLICATION_ID.removeSuffix(".debug")` 拼出的 release 标识做断言，
   文档仍是唯一权威契约。

   验证记录：

   | 检查 | 结果 |
   |---|---|
   | `kmp/build/generated/source/buildConfig/debug/.../BuildConfig.java` | `APPLICATION_ID = "org.tasks.debug"` |
   | 同上 release 变体 | `APPLICATION_ID = "org.tasks"`（未受影响） |
   | `./gradlew :kmp:testDebugUnitTest` | BUILD SUCCESSFUL，490 用例 / 0 失败 / 0 skipped；`TasksContractDocTest` 6/6，`TasksApi{Read,Write,Batch,Error}Test` 127/127 |
   | `./gradlew :app:assembleGenericDebug -PbuildAbi=arm64-v8a` | BUILD SUCCESSFUL |
   | 合并后 manifest（`processGenericDebugMainManifest`） | `package="org.tasks.debug"`；`android:authorities="org.tasks.debug.api"`；读写权限均为 `org.tasks.debug.permission.*` —— 与 kmp 侧 `TasksContract` 现在一致 |

   `app/src/debug/AndroidManifest.xml` 早先用 `tools:node="remove"` 摘掉 dmfs 库硬编码的
   `org.tasks.permission.{READ,WRITE}_TASKS`，与本修复方向一致，无需再动。
   `TasksApiIntegrationTest` 的 7 个用例只能由 CI 的 connected 测试判定，本机未跑仪器测试。

### 9.3 微软账户导入：fork 行为 vs 上游测试

`TasksJsonImporterTest.deduplicateMicrosoftByUsername` 报 `expected:<0> but was:<1>`
（断言在 `app/src/androidTest/java/org/tasks/backup/TasksJsonImporterTest.kt:232`，即
`assertEquals(0, result.importCount)`）。原因是 fork 当时的导入定制：
`app/src/main/java/org/tasks/backup/TasksJsonImporter.kt:232` 起会跳过 `TYPE_MICROSOFT` 账户
（要求用户手动重新登录），并把其日历重定向到本地账户，于是备份里的 Outlook 任务被当新任务导入，
而不是像上游那样按 username 去重成 0。属于**语义冲突**，需要决定：调整测试期望以匹配 fork 行为，
还是让微软去重路径继续成立。

> 决定与处理（2026-10-24）：**revert fork 的这段定制**，`TasksJsonImporter.kt` 恢复成合并时上游一侧
> （`34d26b372f^2`）的版本。理由：
> 1. `CaldavAccount.password` 带 `@Transient`（`data/src/commonMain/.../entity/CaldavAccount.kt`），
>    `TasksJsonExporter` 写出的备份不含凭据。即使不跳过，还原出来的 Microsoft 账户也是空壳，
>    用户仍必须手动登录 —— fork 那段"跳过 + 重定向到本地账户"保护到的收益很小。
> 2. 用户现有的 Microsoft 数据已转成本地清单，该定制的实际用途已归零。
> 3. 它是**每次合上游都会复发的冲突源**：上游测试断言 `importCount == 0`，fork 逻辑必然让它变成 1。
> 4. 行为回归面：导入含 Microsoft 账户的旧备份后，该账户无凭据、需手动登录 —— 与上游一致，非回归。
>
> 取证：`git diff 34d26b372f^2 HEAD -- TasksJsonImporter.kt` 只有 45+/2-，全部是微软相关；
> `TasksJsonImporterTest.kt` 与上游同一版本（diff 为 0）。所以 revert 不会顺带引入其它上游改动。
> 同步更新了 `docs/upstream-merge-conventions.md`（第 1 节原则 2 的举例、第 4 节冲突表），
> 否则下次合并会按旧约定把这段逻辑再"移植"回来。

### 9.4 假警报：`TitleParserTest.testDays`

connected 报告把它列进 "Failed tests"（`failed (0s)`、异常体为空）。但该用例在上游与本仓库
源码里都带 `@Ignore("Flaky test")`（`app/src/androidTest/java/com/todoroo/astrid/service/TitleParserTest.kt:166`），
文件与上游逐字相同；把上游全绿 run `39923500941` 的同名报告拉下来比对，`testDays` 同样被列为
failure、成功率同样 95%。所以这是 AGP connected HTML 报告渲染 `@Ignore` 的方式，
**不是** job 失败的原因。

### 9.5 其余

- `check / lint` 通过。
- `desktop-jar / appimage / android / desktop` 被 check 门控 skip/cancel，自身没有失败。
- `instrumentedTest (Googleplay, 29)` 的 `test-reports-Googleplay` artifact 为空
  （`No files were found with the provided path: app/build/reports/**`），其明细只能从 Generic 报告推断。

### 9.6 本地验证的坑：工作副本里的 CRLF（本机独有，CI 不受影响）

`git ls-files --eol` 显示本机工作副本有 **1062 个文件是 `i/lf w/crlf`**：仓库 blob 是 LF、
CI 检出的也是 LF，只有这份工作副本是 CRLF（早先 `core.autocrlf=true` 时代的遗留；
现在 global `~/.gitconfig` 是 `input`，system `D:/dev/git/etc/gitconfig` 是 `false`）。

后果是**换行敏感的测试在本地假失败**。本次实测一例：`:app:testGenericDebugUnitTest` 的
`iCalendarTest.parseProdId` 报
`ComparisonFailure: expected:<...la Calendar V1.1//EN[]> but was:<...la Calendar V1.1//EN[\r]>`，
因为 `app/src/test/resources/thunderbird/basic_due_date.txt` 在本地是 CRLF，而
`iCalendar.Companion.prodId()`（`kmp/src/commonMain/kotlin/org/tasks/caldav/iCalendar.kt:374`）
用 `PRODID_MATCHER` 的 `(.*)` 捕获，把行尾 `\r` 一起吃进结果。CI 上该用例是绿的。

处理要点：

- `git checkout -- <file>` **不会**重写 git 认为"干净"的文件（clean 过滤器把 CRLF 归一后与 blob
  相同，git 直接跳过写入）。必须 `rm <file> && git checkout -- <file>`，
  之后 `git ls-files --eol` 应变成 `w/lf`。本次就是这么修的（只动了这一个夹具）。
- 不必批量转换：`core.autocrlf=input` 在 add/commit 时会把 CRLF 归一成 LF，本地 CRLF 不会污染仓库，
  只在本地跑换行敏感测试时按需处理单个夹具。
- 顺带提醒：`app/scripts/resize-launcher.sh`、`app/scripts/resize-notification.sh` 在本地也是 CRLF
  （`build.sh` 是 LF），在 Git Bash 里直接执行可能报 `\r` 相关错误。
