# AGP 弃用选项迁移计划

## 目标
移除 5 个已弃用的 gradle.properties 选项，迁移到 AGP 9.0+ 内置 Kotlin 支持，消除构建警告。

## 当前状态

### gradle.properties 中的弃用选项

```properties
android.defaults.buildfeatures.resvalues=true   # 默认已改为 false
android.enableR8.fullMode=false                 # 默认已改为 true
android.r8.optimizedResourceShrinking=false     # 默认已改为 true
android.newDsl=false                            # 默认已改为 true
android.builtInKotlin=false                     # 默认已改为 true
```

### 当前行为 vs 新默认值

| 选项 | 当前值 | 新默认值 | 影响 |
|------|--------|---------|------|
| `android.enableR8.fullMode` | `false` | `true` | R8 完整模式：更激进优化，可能改变混淆行为 |
| `android.builtInKotlin` | `false` | `true` | 使用 AGP 内置 Kotlin 支持，无需单独插件 |
| `android.newDsl` | `false` | `true` | 使用新 DSL 语法 |
| `android.r8.optimizedResourceShrinking` | `false` | `true` | 启用优化资源压缩 |
| `android.defaults.buildfeatures.resvalues` | `true` | `false` | 关闭 resValues 构建特性 |

## 迁移步骤

### 步骤 1：移除弃用选项（gradle.properties）

删除以下 5 行：
```properties
android.defaults.buildfeatures.resvalues=true
android.enableR8.fullMode=false
android.r8.optimizedResourceShrinking=false
android.newDsl=false
android.builtInKotlin=false
```

保留的选项：
```properties
org.gradle.jvmargs=-Xmx8G -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
kotlin.daemon.jvmargs=-Xmx4G
android.r8.strictFullModeForKeepRules=false
tasksStoreFile=tasks-release.jks
tasksKeyAlias=tasks
tasksStorePassword=tasks123
tasksKeyPassword=tasks123
```

### 步骤 2：移除 kotlin("android") 插件（app/build.gradle.kts）

当前：
```kotlin
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    kotlin("android")  // <-- 移除这行
    id("dagger.hilt.android.plugin")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
}
```

改为：
```kotlin
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("dagger.hilt.android.plugin")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
}
```

### 步骤 3：验证构建

```bash
./gradlew :app:assembleGenericDebug
```

## 风险与验证

| 风险项 | 验证方法 |
|--------|---------|
| R8 fullMode 改变混淆规则 | 构建后检查 mapping.txt，测试 APK 运行 |
| 内置 Kotlin 编译差异 | 对比编译日志，确认无新错误 |
| resValues 关闭影响 | 检查是否有代码使用 generated res values |
| 资源压缩优化 | 对比 APK 大小 |

## 回滚方案

若构建失败或运行时异常，恢复删除的 5 行到 gradle.properties，并重新添加 `kotlin("android")` 插件。

## 参考

- [AGP Built-in Kotlin Migration](https://kotl.in/gradle/agp-built-in-kotlin)
- AGP 10.0 将彻底移除这些选项
