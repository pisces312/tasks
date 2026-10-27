# 上游同步记录（2026-10-27）

本文记录把 `upstream/main`（tasks/tasks）合并进 fork 的一次执行。约定见
[`upstream-merge-conventions.md`](./upstream-merge-conventions.md)。

## 1. 概要

| 项 | 值 |
|---|---|
| 分叉点 | `84ed8f5c20`（`Translated using Weblate (Esperanto)`，即 2026-10-19 那次 992 提交合并时的上游 HEAD） |
| 上游新增提交 | 70 |
| fork 独有提交 | 54 |
| 合并提交 | `67b4668dbb` `Merge upstream/main (70 commits) into fork` |
| 回滚点 | `backup/pre-upstream-sync-20261027` → `f6a1d1ebf7` |
| 同步分支 | `sync/upstream-20261027`（已快进 main，分支保留） |
| 变更规模 | 76 个文件，+5772 / −1958 |
| 上游版本 | 15.13，与 fork 的 `version.properties`（`15.13` / `151306`）一致，**未 bump、未出包** |

上游这 70 个提交的构成：

| 类别 | 数量 | 说明 |
|---|---|---|
| Stripe 订阅链路 | ~20 | 新增 `StripeCheckoutViewModel`、`StripeClientImpl`、`CardCheckout`、`CardPortalLauncher`、`PurchaseTerms` 等，kmp +3267 行、composeApp +312 行 |
| 依赖升级 | ~22 | firebase-bom 34.18→35、ktor 3.5.2→3.6.0、posthog、markdown-renderer 0.39.2→0.46.0、work 2.11→2.12、mockito 5.24、qrose 1.3.1 等 |
| 翻译（Weblate） | ~20 | 新增 `values-uz`，日/韩/俄/荷/土/法/中 等更新 |
| 数据与备份行为 | 5 | 见第 3 节 |
| 构建配置 | 3 | 移除 foojay 插件、Kotlin 降级、etebase 换仓库 |

## 2. 操作步骤

```bash
git fetch upstream --tags
git branch backup/pre-upstream-sync-20261027 main
git switch -c sync/upstream-20261027
git merge upstream/main --no-edit            # 仅 deps_*.txt 冲突
git checkout --theirs deps_fdroid.txt deps_googleplay.txt
git add deps_*.txt && git commit             # 67b4668dbb
git switch main && git merge --ff-only sync/upstream-20261027
```

合并前用 `git merge-tree --write-tree --name-only HEAD upstream/main` 做过一次
不落盘预演，结论与实际合并完全一致（只有 2 个冲突文件）。

## 3. 冲突清单与处理决定

| 文件 | 冲突原因 | 处理 |
|---|---|---|
| `deps_fdroid.txt` | fork 加了 JGit/jsch 三件套，上游把 Kotlin 2.4.21 降到 2.4.10 | **取上游版本**。这是 `update_dependency_diff` 生成的依赖树快照，不是构建输入；快照里因此少了 JGit 三行，属文档性缺失 |
| `deps_googleplay.txt` | 同上 | 同上 |

其余 7 个双方都改过的文件全部自动合并成功，已逐一抽查 fork 定制仍在（见第 4 节）。

## 4. 上游结构性变更（本次需要留意的四处）

1. **etebase 换成上游自己发布的包**：`com.etebase:client` 2.3.2（含仓库内
   `libs/client-jvm-2.3.2.jar`）→ `org.tasks:etebase-android` / `org.tasks:etebase-jvm`
   `1.0.0-beta02`（Maven Central）。`libs/` 目录整体删除，`kmp/build.gradle.kts` 的
   jvmMain/androidMain 改引用 catalog。fork 从未改过 etebase 代码，属纯跟随；但这是
   **EteSync 同步客户端整体换版**，本地无法用单元测试覆盖。
2. **bundled sqlite 全版本启用**：app 模块里的 `setDriver()`（仅 API<30 用 bundled，
   并带 `setQueryCoroutineContext(Dispatchers.IO)`）被删除，改为 `data` 模块 commonMain 的
   `setBundledDriver()`（全版本 bundled，**不再设置 query coroutine context**）。
   即：所有机型改用 bundled SQLite 打开 Room 数据库，查询调度改回 Room 默认 executor。
3. **Kotlin 2.4.21 → 2.4.10**：与 fork 的 `9ac20f84e3 Pin Kotlin back to 2.4.10 to fix
   the iOS link crash` 完全同向，`ksp` 同步从 2.3.12 降到 2.3.11。合并后
   `libs.versions.toml` 的这两个值上下一致，fork 的补丁不再需要单独维护。
4. **备份恢复改走 cache 副本**：`TasksFileBackupHelper` 先把 `filesDir/backup.json`
   复制到 `cacheDir` 再导入，结束后删除；`TasksJsonImporter` 追加忽略
   `p_last_backup` / `p_install_date` 两个偏好键（配 `WorkManagerImpl` 改用
   `preferences.installDate`）。

## 5. fork 定制影响核查

| 定制 | 结果 | 依据 |
|---|---|---|
| ABI splits + `-PbuildAbi` | 保留 | `app/build.gradle.kts:151-156` |
| env var 签名（`KEY_STORE_LOCATION` 等） | 保留 | `app/build.gradle.kts:66-68`；release 包 apksigner 验签通过 |
| JGit + mwiede jsch | 保留 | 依赖在 `app/build.gradle.kts:317-319`；release `mapping.txt` 含 31608 行 `org.eclipse.jgit` |
| debug `opentasks_authority` 覆盖 | 保留 | debug APK 资源实测 `org.tasks.debug.opentasks`，release 为 `org.tasks.opentasks` |
| kmp debug `APPLICATION_ID` buildConfigField | 保留 | `kmp/build.gradle.kts:252-258` |
| debug 红图标 + 双 google-services client | 保留 | debug 图标为 `ic_launcher_red`；`google-services.json` 含 `org.tasks` 与 `org.tasks.debug` |
| Git 同步（5 个 `Git*.kt` + 日志面板） | 未受影响 | 上游未触碰 `app/src/main/java/org/tasks/backup/Git*` |
| 设置导出/导入 | 未受影响 | 上游未触碰 `BackupsViewModel` / 相关 strings |
| 标题原地编辑 + 子任务标题原地编辑 | 未受影响 | 上游未触碰 `app/.../tasklist/**` 与 `task_adapter_row.xml` |
| 抽屉排序 / 手动重排（composeApp） | 保留 | `App.kt` 的 `onSortModeChange` ×2、`CoreModule.kt` 的 `tagMetadataSync` 等仍在 |
| kmp 的 `drawer_sort_*` / `app_default` 文案 | 保留 | en 与 zh-rCN 各 4 处仍在（与上游 Weblate 中文改动同文件、自动合并） |
| OSS licenses 入口可解析性判断 | 未受影响 | 上游未触碰 `HelpAndFeedback` |

## 6. 遗留事项与风险

1. **etebase 换版的真机验证缺口**：EteSync 账户的登录与双向同步往返必须在真机上跑一次，
   编译与单测无法覆盖。这是本次合并在功能上唯一的高风险项。
2. **bundled sqlite 全版本切换**：老用户升级后数据库由 bundled 驱动打开，需确认 Room
   迁移脚本正常、备份导入正常、无 StrictMode/ANR 回归。
3. **`setQueryCoroutineContext(Dispatchers.IO)` 被上游移除**：Room 查询改回默认 executor。
   属上游行为变更，fork 跟随、不另行加回，观察即可。
4. **deps 快照缺 JGit 三行**：本次按约定选了"取上游"。若将来需要完整快照，本地跑
   `./update_dependency_diff`（需先从 GitHub release 下 `dependency-tree-diff.jar`）。
5. **`git push` 未执行**：按约定需 user 明确确认。push 后 `fork-check.yml` 会跑
   lint / jvmTest / iosTest / instrumentedTest×2。

## 7. 验证记录（本地，2026-10-27）

| 命令 | 结果 |
|---|---|
| `./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a` | BUILD SUCCESSFUL（仅既有 deprecated 警告） |
| `./gradlew :app:compileGenericReleaseKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:testGenericDebugUnitTest` | BUILD SUCCESSFUL |
| `./gradlew :kmp:jvmTest` | BUILD SUCCESSFUL |
| `./gradlew :app:assembleGenericDebug -PbuildAbi=arm64-v8a` | 产出 `app-generic-arm64-v8a-debug.apk` |
| `./gradlew :app:assembleGenericRelease -x uploadCrashlyticsMappingFileGenericRelease` | 产出 `app-generic-arm64-v8a-release.apk` |
| `aapt2 dump badging` | debug `org.tasks.debug` / release `org.tasks`，均 `151306` / `15.13` |
| `aapt2 dump resources`（authority） | debug `org.tasks.debug.opentasks`，release `org.tasks.opentasks` |
| `apksigner verify --print-certs` | V2 签名通过 |
| `grep -c org.eclipse.jgit mapping.txt` | 31608 |
| `values-in` / `values-iw` symlink 残留检查 | 无残留 |
| 冲突标记残留 `grep -rl '^<<<<<<< '` | 无 |
