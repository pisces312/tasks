# AGENTS.md

本文件是 AI 代理在本仓库工作时的入口（原 `CLAUDE.md` 已并入本文件）。
**详细约定见下方索引，不要只读这里就动手。**

## 仓库

`pisces312/tasks` —— Tasks.org 的 fork（上游 `tasks/tasks`）。

fork 定制：Git 同步（JGit + mwiede jsch）、设置导出/导入、debug 红图标与 `.debug` 包名、
ABI splits、环境变量签名。

## 常用命令

**发布目标固定：`generic` flavor（无 Google 服务：不含 GMS / Play billing / Google Tasks 登录）
+ `arm64-v8a` 单 ABI。** 这是用户的默认交付形态，`./build.sh` 不带参数即是它：

```bash
./build.sh          # = ./build.sh release generic arm64-v8a  ← 正式版就用这个
```

不要"顺手"构建 `googleplay` 或其它 ABI；只有在用户明确要求时才构建。

```bash
# build.sh（Windows 下必须用 Git Bash，位于 D:\dev\git\bin\bash.exe）
./build.sh                                    # generic release, arm64-v8a（默认）
./build.sh debug                              # generic debug
./build.sh release googleplay                 # googleplay release（仅明确要求时）
./build.sh release generic all                # all ABIs（仅明确要求时）

# 或直接用 Gradle
./gradlew assembleGenericDebug
./gradlew assembleGenericRelease -PbuildAbi=arm64-v8a
./gradlew assembleGenericDebugArm64-v8a

# 编译校验（快，但不等于整包通过）
./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a

# 测试
./gradlew test                                     # 单元测试
./gradlew testDebugUnitTest                        # 单元测试（debug）
./gradlew connectedDebugAndroidTest                # 仪器测试（需模拟器）
./gradlew testDebugUnitTest --tests "*.TaskTest"   # 单个测试类

# Lint
./gradlew lint                                     # 配置见 app/lint.xml
```

## 架构

**多模块 Android 项目**，并含 Kotlin Multiplatform 能力（composeApp 支持 desktop/iOS）。

| 模块 | 用途 |
|---|---|
| `app/` | 主 Android 应用 |
| `data/` | 数据层（Room DB、DAO） |
| `composeApp/` | Kotlin Multiplatform Compose UI（desktop/iOS） |
| `kmp/` | KMP 共享代码 |
| `icons/` | 应用图标资源 |
| `wear/` | Android Wear 应用 |
| `wear-datalayer/` | Wear 数据层（gRPC） |

**构建 flavor：** `generic`（**交付默认**，无 Google 服务）、`googleplay`

**交付约定（重要）：** 对外发布的正式版只出 `generic` + `arm64-v8a`。
产物：`app/build/outputs/apk/generic/release/app-generic-arm64-v8a-release.apk`。
`googleplay` flavor 仅用于对照验证编译链路是否被改坏，不作为交付物。
`generic` 不含 `play-services-oss-licenses`，因此"第三方许可证"入口按 Activity 可解析性隐藏
（见 `HelpAndFeedback.canOpenThirdPartyLicenses()`）。

**关键技术：**
- DI：Hilt/Dagger
- 数据库：Room
- UI：Compose + ViewBinding/DataBinding 混合
- 网络：Ktor + OkHttp
- 同步：JGit（Git 同步）、Google Tasks API、CalDAV、Etebase

**按 flavor 划分的 source set：**
- `main/` —— 共享代码
- `generic/` —— 仅 generic flavor
- `googleplay/` —— 仅 googleplay flavor（Play Services、billing、地图）

## 开发注意事项

**发布签名：** 需要环境变量 `KEY_STORE_LOCATION` / `KEY_ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`。
debug 使用默认 debug keystore。**禁止把任何密钥、口令、keystore 内容写入仓库。**

**Mapbox / Google API：** 在 `gradle.properties` 中添加
```
tasks_mapbox_key_debug="<key>"
tasks_google_key_debug="<key>"
```

**ABI splits：** 传 `-PbuildAbi=arm64-v8a` 只构建单个 ABI（更快），省略则构建全部。

**Java：** 需要 JDK 17+（JBR 位于 `D:\dev\AndroidStudio\jbr\`，旧路径 `D:\nili\dev\AndroidStudio\jbr\` 已被 `build.sh` 兼容）。

**Shell 环境（Windows）：** `build.sh` 必须用 **Git Bash**（`D:\dev\git\bin\bash.exe`）运行，
**不能用 WSL 的 `bash`**（`C:\Windows\System32\bash.exe`）。`local.properties` 里的
`sdk.dir` 是 Windows 路径（`D:\dev\android_sdk`），WSL 下会报
`SDK location not found`。从 PowerShell/cmd 调用时应显式指定
`& "D:\dev\git\bin\bash.exe" -lc "cd /d/3rd-party-projects/tasks-org-source && ./build.sh"`，
避免解析到 WSL bash。

## 索引

| 主题 | 文档 |
|---|---|
| **合并上游（必读）** | [`docs/upstream-merge-conventions.md`](docs/upstream-merge-conventions.md) |
| 最近一次合并记录 | [`docs/upstream-sync-2026-10-19.md`](docs/upstream-sync-2026-10-19.md) |
| Git 同步设计 | [`docs/git-sync-design.md`](docs/git-sync-design.md) |
| 设置导入导出设计 | [`docs/settings-export-import-design.md`](docs/settings-export-import-design.md) |

## 合并上游（摘要，细节以约定文档为准）

任何 `git merge upstream/main` 之前先读 [`docs/upstream-merge-conventions.md`](docs/upstream-merge-conventions.md)，
其中包含：标准流程、冲突处理表、**合并后必查 7 项**、验证标准、历史踩坑清单。

最容易丢的一条：`app/build.gradle.kts` 的 `buildTypes.debug` 里必须保留
`resValue("string", "opentasks_authority", "<applicationId>.debug.opentasks")`。
上游 `defaultConfig` 用基础 `applicationId` 生成该值（不含 `.debug` 后缀），
且**源码集 `src/debug/res/values` 覆盖不了 `resValue`**——丢了会导致 debug 包访问 release 的 provider。

合并完成后按约定文档"记录要求"更新 `docs/upstream-sync-YYYY-MM-DD.md` 与 `.workbuddy/memory/`。

## 工作纪律

1. 只 commit，不 push；push 需用户显式确认。
2. 破坏性操作（删除分支、`reset --hard`、批量改文件）先说明再执行。
3. 合并上游后必须通过 `docs/upstream-merge-conventions.md` 第 6 节的验证，不能只跑 `compile*`。
4. 合并完成后按第 8 节更新记录文档与 `.workbuddy/memory/`。
5. 正式版只构建 `generic` + `arm64-v8a`（`./build.sh` 默认），不要构建其它 flavor / ABI。
