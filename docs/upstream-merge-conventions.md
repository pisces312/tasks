# 上游合并约定（fork 维护手册）

适用仓库：`pisces312/tasks`（fork 自 `tasks/tasks`）。
本文是"合并上游"这件事的唯一权威约定；`AGENTS.md` 只保留入口与硬规则。
最近一次执行记录：[`upstream-sync-2026-10-19.md`](./upstream-sync-2026-10-19.md)。

## 1. 总原则

1. **默认保留 fork 定制**。冲突时先问"这条定制解决什么问题"，再决定怎么叠加，不要因为上游"看起来更完整"就整块丢弃。
2. **上游的结构性重构要接受，fork 的语义要叠加**。上游把导入器重构成 `canonicalized` / `accountUuidMap` / `calendarUuidMap` 这类结构性改动时，fork 叠加在上面的语义要移植到新结构，而不是回退到旧实现。（曾经的典型例子"Microsoft → 本地任务"已于 2026-10-24 revert，见第 4 节。）
3. **能用上游方案替代 fork 补丁时优先替代**，但要在记录里写明原因（例：androidTest 符号链接占位 → 上游 `app/src/sharedTest/java`）。
4. **不静默改变行为**。若合并导致 fork 功能语义变化（哪怕变得"更正确"），必须单独列出并请 user review。

## 2. 标准流程

```bash
# 0) 工作区必须干净；先确认 remote
git remote -v
git status -sb

# 1) 拉取上游（含 tag），记录分叉点
git fetch upstream --tags && git fetch origin
BASE=$(git merge-base main upstream/main)
git log --oneline $BASE..upstream/main | head
git log --oneline $BASE..main          # fork 独有提交，逐条过一遍

# 2) 建立回滚点 + 同步分支
git branch backup/pre-upstream-sync-YYYYMMDD main
git switch -c sync/upstream-YYYYMMDD

# 3) 合并（不自动提交，先看冲突）
git merge upstream/main --no-edit

# 4) 解决冲突（见第 3、4 节），逐项确认

# 5) 编译 → 整包 → 逆向校验（见第 6 节）

# 6) 提交 merge commit，快进 main
git switch main && git merge --ff-only sync/upstream-YYYYMMDD
```

约定：

- 回滚分支命名 `backup/pre-upstream-sync-YYYYMMDD`，用完**不要删**。
- 同步分支命名 `sync/upstream-YYYYMMDD`。
- 合并只在同步分支上做，`main` 只接受快进。
- **只 commit，不 push**；push 需 user 显式确认。

## 3. 合并前必看

```bash
# fork 改过哪些文件
git diff --name-only $BASE..main | sort > /tmp/fork.txt
# 上游改过哪些文件
git diff --name-only $BASE..upstream/main | sort > /tmp/up.txt
# 交集 = 潜在冲突
comm -12 /tmp/fork.txt /tmp/up.txt
```

- fork 独有提交要逐条读（`git log $BASE..main`），把每个提交当成一条待验证的功能。
- 冲突规模大时，用 `git merge-tree --write-tree --name-only <fork> <upstream>` 先拿到完整冲突清单，不必真合并。

## 4. 冲突解决要点

| 文件/区域 | 约定 |
|---|---|
| `gradle.properties` / `libs.versions.toml` / wrapper | 采用上游版本；保留 fork 的仓库镜像（腾讯）；**不要**把凭据写回文件（签名一律用环境变量） |
| `app/build.gradle.kts` | 上游工具链参数照收；fork 的 `splits.abi`、`signingConfigs.release`（env var）、JGit/jsch 依赖、`buildTypes.debug` 覆盖必须保留 |
| `app/src/debug/AndroidManifest.xml` | 红图标 + `tools:replace`、dmfs 权限 `tools:node="remove"` 保留；`allowBackup` 跟随上游 |
| `kmp/build.gradle.kts` | 上游工具链/依赖照收；`android.buildTypes.debug` 的 `APPLICATION_ID` buildConfigField 必须保留（见 5.2） |
| `BuildSetup.kt`（debug/release） | 保留 `GitSyncLogCollector.timberTree`，并叠加上游 kermit `minSeverity` |
| `TasksJsonImporter.kt` | **直接采用上游版本**。fork 曾在此叠加"导入时跳过 Microsoft 账户并把其日历改挂本地账户"（`8ddde1750e` / `bd189c39dd`），已于 2026-10-24 revert：备份不含凭据（`CaldavAccount.password` 是 `@Transient`），还原出的 Microsoft 账户本来就要手动登录，而这段定制会让上游 `TasksJsonImporterTest.deduplicateMicrosoftByUsername` 每次合并都红。理由详见 [`upstream-sync-2026-10-19.md`](./upstream-sync-2026-10-19.md) 9.3 |
| `BackupsViewModel.kt` | 保留设置导出/导入；上游改 `suspend` 就跟着改；上游删除的 `p_*` 键要从忽略表移除 |
| `strings.xml` / `values-zh-rCN` | 保留 `git_sync*` 与设置导入导出文案；上游删除的条目照删 |
| `app/google-services.json` | 上游从 `app/src/debug/` 移到 `app/` 后，必须同时登记 `org.tasks` 与 `org.tasks.debug` 两个 client |
| `app/src/androidTest/**` 符号链接 | Windows 不支持，接受上游删除，使用 `app/src/sharedTest/java` |

## 5. 合并后必查清单（7 项）

### 5.1 debug 变体的 `opentasks_authority`（最容易丢）

- 位置：`app/build.gradle.kts` → `buildTypes.debug { resValue("string", "opentasks_authority", "<applicationId>.debug.opentasks") }`
- 原因：上游在 `defaultConfig` 用 `resValue(..., "$applicationId.opentasks")` 生成，`$applicationId` 在**配置期**求值，只等于基础包名 `org.tasks`，**不含** `applicationIdSuffix = ".debug"`。
- 丢失后果：debug 包解析到 release 应用的 dmfs provider → `SecurityException`（历史上由 `55a0fbe0da` 修复）。
- **陷阱**：在 `app/src/debug/res/values/keys.xml` 里写同名 `<string>` **无效**——生成的 `resValue` 资源优先级更高（实测 `aapt2 dump resources` 仍是旧值）。覆盖必须写在 `buildTypes.debug` 里。
- 验证：

  ```bash
  ./gradlew :app:processGenericDebugResources -PbuildAbi=arm64-v8a
  AAPT2=$ANDROID_HOME/build-tools/37.0.0/aapt2
  $AAPT2 dump resources app/build/intermediates/linked_resources_binary_format/genericDebug/processGenericDebugResources/*.ap_ | grep -A1 string/opentasks_authority
  # 期望 "org.tasks.debug.opentasks"；release 变体为 "org.tasks.opentasks"
  ```

### 5.2 debug 独立包名与图标

- `applicationIdSuffix = ".debug"` 在；
- 合并清单 `android:icon` / `android:roundIcon` 为 `@mipmap/ic_launcher_red`；
- 清单中不存在 `org.tasks.permission.*`，自定义权限为 `org.tasks.debug.permission.*`；
- `kmp/build.gradle.kts` 的 `android.buildTypes.debug` 里有
  `buildConfigField("String", "APPLICATION_ID", "\"<base>.debug\"")`。
  library 模块不吃 app 的 `applicationIdSuffix`，少了这行则 `TasksContract.AUTHORITY` /
  `PERMISSION_*` 在 debug 包里仍指向 `org.tasks.api` 与 `org.tasks.permission.*`，
  与合并清单注册的 `org.tasks.debug.api` 不一致 → `TasksApiProvider` 的 `UriMatcher` 匹配不上，
  debug 包的 Content Provider API 实际不可用（表现为 `TasksApiIntegrationTest` 全挂
  `Unknown authority org.tasks.api`）。改完跑 `:kmp:testDebugUnitTest`；
  注意 `TasksContractDocTest` 断言的是去掉 `.debug` 后缀的 release 标识，
  因为 `CONTENT_PROVIDER.md` 记录的是对外发布契约。

### 5.3 构建与签名定制

- `splits.abi` + `-PbuildAbi=<abi>` 生效；
- `signingConfigs.release` 只读 `KEY_STORE_LOCATION` / `KEY_ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`；
- JGit 三件套依赖 + `app/proguard.pro` 的 `org.eclipse.jgit.**` / `com.jcraft.jsch.**` keep 规则在；
- `com.google.android.gms.oss-licenses-plugin` 为**启用**状态（2026-10-19 起：Gradle 9.8.1 + AGP 9.4.1
  下已实测兼容，`*OssLicensesTask` 正常生成 `raw/third_party_licenses`）。若上游再升 Gradle 导致
  strict task validation 报错，按上游版本决定是修还是临时注释，并在第 7 节记录。
  注意 `play-services-oss-licenses` 一直是 `googleplayImplementation`：**generic 包里没有
  `OssLicensesMenuActivity`**。fork 已在 `HelpAndFeedback` 里按 `PackageManager` 可解析性隐藏该入口
  （`canOpenThirdPartyLicenses()`），所以 generic 既不显示菜单项也不会崩。合并上游时要确认这段
  判断没被覆盖掉（上游只用 `isGooglePlay` 这个 flavor/billing 判断，没有可解析性检查）。

### 5.4 google-services 双 client

`app/google-services.json` 内 `package_name` 必须同时出现 `org.tasks` 与 `org.tasks.debug`，
否则某个变体的 `processGoogleServices*` 会 `No matching client found`。

### 5.5 版本号

- 来源：根目录 `version.properties`（`VERSION_NAME` / `VERSION_CODE`），不再是 `libs.versions.toml`。
- `settings.gradle.kts` 断言 `VERSION_CODE % 2 == 0`（wear 用 `+1`）。
- fork 编号规则 `MAJOR.MINOR.PATCH.FORK_INC` + `YYMMDDNN` 要落在"偶数"约束内。

### 5.6 fork 功能文件与文案

```bash
ls app/src/main/java/org/tasks/backup/Git*.kt
grep -c 'name="git_sync\|name="export_settings\|name="import_settings' app/src/main/res/values/strings.xml
grep -rn "GitSyncLogCollector" app/src/{debug,release}/java/org/tasks/BuildSetup.kt
```

### 5.7 语言资源：不要留 symlink 残留

上游在 `app/src/main/res/` 与 `kmp/src/commonMain/composeResources/` 里用符号链接
`values-in → values-id`、`values-iw → values-he`（印尼语 / 希伯来语的旧语言码）。

- Windows 无法创建 symlink，git 检出时会写成**内容为目标名的 9 字节文本文件**，既不是目录也不提供翻译。
- 因此仓库只保留 `values-id` / `values-he`：`minSdk 26` 起系统已使用现代语言码，旧码不再需要。
- 每次合并后检查并删除被 git 重新生成的残留：

  ```bash
  ls app/src/main/res | grep -E '^values-(in|iw)$'
  ls kmp/src/commonMain/composeResources | grep -E '^values-(in|iw)$'
  # 若有输出（且是文件不是目录）：git rm 掉
  ```

## 6. 验证标准（缺一不可）

```bash
# 1) 编译
./gradlew :app:compileGenericDebugKotlin   -PbuildAbi=arm64-v8a
./gradlew :app:compileGenericReleaseKotlin -PbuildAbi=arm64-v8a

# 2) 整包（release 需签名环境变量）
./gradlew :app:assembleGenericDebug   -PbuildAbi=arm64-v8a
./gradlew :app:assembleGenericRelease -PbuildAbi=arm64-v8a \
  -x uploadCrashlyticsMappingFileGenericRelease

# 3) 逆向校验
aapt2 dump badging   app/build/outputs/apk/generic/{debug,release}/*.apk | grep ^package
aapt2 dump resources app/build/outputs/apk/generic/debug/*.apk | grep -A1 string/opentasks_authority
apksigner verify --print-certs app/build/outputs/apk/generic/release/*.apk
grep -c "org.eclipse.jgit" app/build/outputs/mapping/genericRelease/mapping.txt

# 4) 冲突标记残留（应为空）
grep -rl '^<<<<<<< ' . --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle
```

- 只跑 `compile*` 不够：资源合并、清单合并、R8、签名都可能在整包阶段才暴露问题。
- 编译通过 ≠ 功能正确。Git 同步往返、设置导入导出、旧备份导入仍需真机验证。

## 7. 常见坑（历史踩过）

| 现象 | 根因 | 处理 |
|---|---|---|
| 所有 Kotlin 编译失败：`IrGenerationExtensionException: Invalid file path` | Kotlin 2.4 module 名含 `:`，Compose 指标文件名在 Windows 非法 | 指标改为显式 `-PcomposeMetrics=<dir>` 才开启 |
| debug 包访问 provider 报 `SecurityException` | `opentasks_authority` 指向 release 包 | 见 5.1 |
| release 变体 `No matching client found` | `google-services.json` 只有 debug client | 见 5.4 |
| `getFullDateTime` / `material-icons` 找不到 | 上游删除 | 改用 `DateFormatter`（suspend）与项目自有 `TasksIcons` + `SymbolIcon` |
| Git 同步 `tasks.json` 无法通过"导入备份"还原 | 导出器无 `"data"` 包裹，导入器只认 `version`/`timestamp`/`data`（合并前即存在） | 属已知限制；要支持需给导出器加包裹 |
| `androidTest` 编译报符号链接错误 | Windows 不支持仓库内 symlink | 使用上游 `app/src/sharedTest/java` |
| `values-in` / `values-iw` 变成 9 字节文本文件 | 同上，git 检出 symlink 失败 | 见 5.7，直接删除，靠 `values-id` / `values-he` |

## 8. 记录要求

每次合并完成后：

1. 在 `docs/upstream-sync-YYYY-MM-DD.md` 记录：分叉点、上游提交数、合并提交、回滚点、变更规模、冲突清单与处理决定、fork 功能核验表、遗留风险、验证命令与结果。
2. 在本文件第 7 节补充新踩到的坑（如果是通用问题）。
3. 更新 `.workbuddy/memory/` 当日日志与 `MEMORY.md` 的长期结论。
