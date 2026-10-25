# 构建与环境

本仓库的构建、签名、工具链与本地环境约定。`AGENTS.md` 只保留高频入口，细节以此文为准。

## 交付形态（固定）

对外发布的正式版**只出 `generic` flavor + `arm64-v8a` 单 ABI**：

```
app/build/outputs/apk/generic/release/app-generic-arm64-v8a-release.apk
```

- `generic`：无 Google 服务（不含 GMS / Play billing / Google Tasks 登录）。
- `googleplay` flavor 只用于对照验证编译链路没被改坏，**不是交付物**。
- 不要"顺手"构建 `googleplay` 或其它 ABI；只有用户明确要求时才构建。

## `build.sh`

```bash
./build.sh                                # release generic arm64-v8a（默认，正式版就是它）
./build.sh debug                          # debug generic arm64-v8a
./build.sh release googleplay             # 仅对照验证时
./build.sh release generic all            # 全部 ABI（仅明确要求时）
./build.sh release generic armeabi-v7a    # 指定单 ABI
```

参数顺序：`[debug|release] [generic|googleplay] [abi]`，省略即默认值。

**Windows 上必须用 Git Bash 运行**（`D:\dev\git\bin\bash.exe`），**不能用 WSL 的 bash**
（`C:\Windows\System32\bash.exe`）：`local.properties` 的 `sdk.dir` 是 Windows 路径
`D:\dev\android_sdk`，WSL 下会报 `SDK location not found`。从 PowerShell/cmd 调用时显式指定：

```powershell
& "D:\dev\git\bin\bash.exe" -lc "cd /d/3rd-party-projects/tasks-org-source && ./build.sh"
```

## Gradle 命令

```bash
# 编译校验（快，但不等于整包通过）
./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a

# 打包
./gradlew assembleGenericDebug
./gradlew assembleGenericRelease -PbuildAbi=arm64-v8a
./gradlew assembleGenericDebugArm64-v8a

# 测试
./gradlew test                                     # 单元测试
./gradlew testDebugUnitTest                        # 单元测试（debug）
./gradlew :kmp:testDebugUnitTest                   # kmp 的 androidUnitTest（CI 也跑）
./gradlew connectedDebugAndroidTest                # 仪器测试（需模拟器）
./gradlew testDebugUnitTest --tests "*.TaskTest"   # 单个测试类

# Lint
./gradlew lint                                     # 配置见 app/lint.xml
```

`-PbuildAbi=<abi>` 只构建单个 ABI（更快）；省略则构建全部。
release 打包若卡在 Crashlytics 映射上传，用 `-x uploadCrashlyticsMappingFileGenericRelease`。

## 工具链与缓存

| 项 | 现状 |
|---|---|
| Gradle | 9.8.1，wrapper 走腾讯镜像（`mirrors.cloud.tencent.com/gradle`），`validateDistributionUrl=false` |
| AGP | 9.4.1 |
| Kotlin | 2.4.10，KSP 2.3.11（**不要升到 2.4.21**，会在 K/N 后端崩，见 `docs/upstream-sync-2026-10-19.md` 9.1） |
| Room | `androidx.room3`，KSP 生成 |
| JDK | 17+ 必需。本机为 Temurin **25.0.4.1**，位于 `D:\dev\jdk-25`，`JAVA_HOME` 已指向它 |
| Android Studio JBR | `D:\dev\AndroidStudio\jbr\`（旧路径 `D:\nili\dev\AndroidStudio\jbr\` 已被 `build.sh` 兼容） |

JDK 25 下 `kmp` 的 JVM 测试目标（class 69）可直接运行，**不需要**为了跑测试临时下调
`gradle/libs.versions.toml` 的 `jdk`。

Compose 编译指标默认关闭，需要时显式 `-PcomposeMetrics=<绝对目录>`：Kotlin 2.4 的 module 名含
`:`，在 Windows 下会生成非法文件名。

## 签名

release 签名只从环境变量读取：`KEY_STORE_LOCATION` / `KEY_ALIAS` / `KEY_STORE_PASSWORD` /
`KEY_PASSWORD`。debug 使用默认 debug keystore。
**禁止把任何密钥、口令、keystore 内容写入仓库。**

## 地图 / Google API key

在 `gradle.properties`（不入库）中添加：

```properties
tasks_mapbox_key_debug="<key>"
tasks_google_key_debug="<key>"
```

## 本地跑仪器测试

前提：模拟器已起（`pixel6` AVD，见 `android-emulator-manager` 技能；
`adb shell getprop sys.boot_completed` 为 1）。

```bash
# 跑单个测试类（AGP 的 class 参数）
./gradlew :app:connectedGenericDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.tasks.backup.TasksJsonImporterTest
```

两个实测过的坑：

1. **不要带 `-PbuildAbi=arm64-v8a`。** 本机 AVD 是 `sdk_gphone64_x86_64`，arm64-only APK 靠
   native bridge 转译运行时，Mockito 的 inline mock maker 无法 attach JVMTI
   （`Native-bridge agents unsupported: libdexmakerjvmtiagent.so`），于是**所有**
   `InjectingTestCase` 子类会在 `TestModule.getWorkManager` 处
   `NullPointerException ... MockMaker$TypeMockability.mockable() on a null object reference`，
   看起来像"测试全挂"其实与代码无关。
2. `-Pandroid.testInstrumentationRunnerArguments.class=A,B` 实测**只跑第一个类**；
   要跑多个类就分多次运行，或改用 package 过滤。

## 本地与 CI 的换行差异

本机工作副本有大量 `i/lf w/crlf` 文件（早期 `core.autocrlf=true` 遗留），换行敏感的测试会在
本地假失败而 CI 是绿的。修法是 `rm <file> && git checkout -- <file>`（单独 `checkout --` 无效）；
不必批量转换，`core.autocrlf=input` 在提交时会归一。详见
`docs/upstream-sync-2026-10-19.md` 9.6。
