# tasks-org-source 隐私安全审查报告

**项目**: tasks/tasks（原 Astrid）  
**版本**: 15.3  
**许可证**: GPL-3.0  
**路径**: `D:\nili\3rd_party_projects\tasks-org-source`  
**审查日期**: 2026-04-28

---

## 一、项目概述

tasks-org-source 是开源任务/待办事项应用，基于已停更的 Astrid，Kotlin 编写，支持 CalDAV/Google Tasks/EteSync 等同步。采用双 flavor 架构区分专有依赖版本和自由版本。

---

## 二、Product Flavors 对比

| 特性 | googleplay | fdroid |
|------|-----------|--------|
| Firebase Cloud Messaging (FCM) | ✅ 推送通知 | ❌ 无 |
| Firebase Crashlytics | ✅ 崩溃上报 | ❌ 无 |
| Firebase Remote Config | ✅ 远程配置 | ❌ 无 |
| PostHog Analytics | ✅ 行为追踪 | ❌ 无 |
| Google Play Billing | ✅ 订阅付费 | ❌ 无 |
| Google Play Services | ✅ 完整依赖 | ❌ 空壳 stub |
| `isLibre` | `false` | `true` |
| 法律披露弹窗 | 显示 | 不显示 |
| 自用推荐 | ❌ | ✅ |

**结论：自用编译应选 fdroid flavor，完全无追踪。**

---

## 三、googleplay Flavor 追踪详情

### 3.1 PostHog Analytics

- **端点**: `us.i.posthog.com`
- **追踪范围**: 约 80 个 SharedPreferences 键值变化（`trackedPrefs`）
- **追踪类别**: 主题设置、同步偏好、提醒配置、UI 偏好等
- **混淆字段**: 4 个敏感路径偏好（备份目录、附件目录）经 `obfuscatedPrefs` 处理
- **开关**: `isTrackingEnabled` 控制，但默认开启
- **位置**: `composeApp/src/commonMain/kotlin/org/tasks/analytics/`

### 3.2 Firebase Crashlytics

- 自动崩溃上报，googleplay flavor 独有
- fdroid flavor 中 Firebase 为 stub（仅 Timber 日志输出）

### 3.3 Firebase Remote Config

- 控制安装提示、评价弹窗、订阅提示的冷却期
- 默认值：安装 14 天、评价 30 天、订阅 30 天
- 无安全隐患，仅影响 UI 弹窗频率

### 3.4 硬编码 Google API Key

- **文件**: `google-services.json`
- **项目**: `tasks-98543`（ID: 363426363175）
- **Debug API Key**: `AIzaSyACOYpQZEIkpF2EykbufMOZXn9QVrP_5lI`（上游开发者密钥）
- 此为上游开发者所有，非本 fork 密钥，无需特别处理

---

## 四、安全发现

### 4.1 FileProvider 暴露外部存储根目录

- **文件**: `file_provider_paths.xml`
- **配置**: `<external-path name="external_files" path="."/>`
- **风险**: 暴露整个外部存储根目录，任何应用可通过 content:// URI 访问
- **影响**: 中等。需要知道确切 URI 才能访问具体文件
- **建议**: 收窄 path 到具体子目录（如 `Tasks` 或 `Android/data/org.tasks/`）

### 4.2 WidgetIconProvider exported=true

- `android:exported="true"` 配合 `tools:ignore="ExportedContentProvider"`
- Lint 警告被显式抑制
- **风险**: 低。仅提供小部件图标，无敏感数据

### 4.3 Timber 日志调用

- 全项目约 384 处 `Timber.*` 调用
- Release 构建下 ProGuard 规则可剥离 Timber 日志
- **风险**: 低。ProGuard 处理后不影响生产包

### 4.4 自定义证书处理

- 使用 cert4android 的 `CustomCertManager` 处理自签名证书
- `OkHostnameVerifier` 用于证书验证
- Cookie 持久化通过 `SharedPrefsCookiePersistor`
- **无 TrustAll 证书绕过**，SSL 安全

### 4.5 项目根目录 keystore 文件

- `tasks-release.jks` 存在于项目根目录
- 已在 `.gitignore` 中排除，不会被提交
- **风险**: 低。仅影响开发者本地

### 4.6 keys.asc 文件

- 包含部分 PGP 公钥块
- 用于签名验证，非私钥
- **风险**: 无

---

## 五、CI/CD 安全

### GitHub Actions Workflows

| Workflow | 用途 | 密钥管理 |
|----------|------|---------|
| `bundle.yml` | 构建打包（含 Pebble SDK、签名、Conveyor 桌面包） | 密钥通过 GitHub Secrets 注入 |
| `check.yml` | Lint 检查 | 无密钥 |
| `dependency_diff.yml` | 依赖差异对比 | 无密钥 |
| `deploy.yml` | 部署 | 密钥通过 Secrets |
| `release.yml` | 发布 | 密钥通过 Secrets |

CI 密钥管理合规，未硬编码。

---

## 六、技术栈

| 组件 | 版本 |
|------|------|
| AGP | 9.1.0 |
| Kotlin | 2.3.10 |
| Compose BOM | 2026.03.00 |
| compileSdk | 36 |
| targetSdk | 35 |
| minSdk | 26 |
| Room | 数据库 ORM |
| Hilt/Dagger + Koin | 依赖注入 |
| Ktor + gRPC/Protobuf | 网络层 |
| Wear OS + Horologist | 穿戴设备支持 |
| Compose Multiplatform | 桌面端目标 |

---

## 七、构建建议

```bash
# 自用无追踪构建（fdroid flavor）
./gradlew assembleFdroidRelease

# 签名（使用自己的 keystore）
java -jar apksigner.jar sign \
  --ks /path/to/keystore \
  --ks-key-alias alias \
  --out tasks-fdroid-signed.apk \
  app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```

---

## 八、总结

| 维度 | 评分 | 说明 |
|------|------|------|
| 隐私安全 | ⭐⭐⭐⭐ | fdroid flavor 完全干净，googleplay 有 PostHog 追踪 |
| 代码安全 | ⭐⭐⭐⭐ | 无 TrustAll、无广告 SDK，FileProvider 可收窄 |
| 依赖安全 | ⭐⭐⭐⭐ | 主流库，版本较新 |
| CI/CD 安全 | ⭐⭐⭐⭐⭐ | 密钥管理规范 |

**最终建议：使用 fdroid flavor 构建自用版本，可完全避开所有隐私追踪。**
