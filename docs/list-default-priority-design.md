# 清单级新建任务默认优先级设计

> 创建时间：2026-10-22
> 状态：已实现（2026-10-22）

## 一、需求

为每个任务清单增加「新建任务默认优先级」设置，支持把某个清单的新建任务默认设为最高优先级。

- 设置粒度：**清单级**（每条清单独立配置）
- 取值：应用默认 / 高 / 中 / 低 / 无
- 「应用默认」表示跟随「设置 → 任务默认值 → Default priority」的当前全局值，不写死具体优先级
- 本次不修改全局默认的出厂值（仍为 Low），也不做子任务的清单级默认

## 二、存储方案

### 2.1 选择

在 `caldav_lists` 表增加可空列：

```sql
ALTER TABLE `caldav_lists` ADD COLUMN `cdl_default_priority` INTEGER
```

对应实体字段：

```kotlin
// data/src/commonMain/kotlin/org/tasks/data/entity/CaldavCalendar.kt
@ColumnInfo(name = "cdl_default_priority")
val defaultPriority: Int? = null
```

- `null`：跟随全局默认（存量清单升级后全部为 null，行为不变）
- 非 null：`Task.Priority.HIGH(0)` / `MEDIUM(1)` / `LOW(2)` / `NONE(3)`

### 2.2 为什么存在数据库而不是偏好项

1. `CaldavFilter` 已经携带完整的 `CaldavCalendar`，创建任务时可以同步读取，不需要额外异步查询偏好；
2. 清单删除时设置随行删除，不会留下孤儿键；
3. 完整 JSON 备份/恢复随 `caldav_lists` 一起走（`CaldavCalendar` 已标记 `@Serializable`）。

注意：`CaldavCalendar` 名字是历史遗留，实际表示**任务清单**（表名 `caldav_lists`），
覆盖本地、CalDAV、Google Tasks、Microsoft To Do、Etebase 等所有清单类型。
事件日历（Google Calendar 等）走 `defaultCalendar` / `calendarURI` / `GCalHelper`，与此无关。

### 2.3 数据库迁移

- `Database.version` 98 → 99
- 新增 `CommonMigrations.MIGRATION_98_99`
- 构建生成 `data/schemas/org.tasks.data.db.Database/99.json` 并提交
- 新增迁移测试：旧行保留、新列为 NULL

合并上游时如版本号冲突，将本迁移顺延为下一个版本号并同步修改 `Database.version`。

## 三、清单设置 UI

所有清单类型共用 Compose 设置页（Android 与 composeApp desktop/iOS 共用）：

- `ListSettingsState` 增加 `defaultPriority: Int?`
- `hasChanges` 纳入该字段，未保存时触发丢弃确认
- 在名称/颜色/图标区域下方增加一行「Default priority」：
  - 摘要：`App default` 或 `High / Medium / Low / None`
  - 点击弹出单选项：`App default`（哨兵值 `-1`）+ 现有 `PriorityOptions`
- `ListSettingsStateManager` 初始化自 `calendar.defaultPriority`，提供 `setDefaultPriority`
- 新建/编辑清单的四个保存实现都要写回该字段：
  - `LocalListSettingsViewModel`
  - `ListSettingsViewModel`（Google / Microsoft）
  - `CaldavCalendarSettingsViewModel`（CalDAV）
  - `EtebaseCalendarSettingsViewModel`
- 新增字符串 `app_default = "App default"`

设置只存储在本机，不写入 CalDAV / Google / Microsoft / Etebase 远端。

## 四、新建任务时如何取值

优先级来源顺序：

```
清单设置非 null  →  使用清单值
清单设置为 null  →  全局「任务默认值」中的 Default priority
```

### 4.1 Android：TaskCreator

`TaskCreator.create(...)` 增加可选参数 `defaultPriorityOverride: Int?`，仅当 `applyDefaults = true` 时生效：

```kotlin
priority = if (applyDefaults) {
    defaultPriorityOverride ?: preferences.defaultPriority()
} else {
    Task.Priority.NONE
}
```

取值来源：

| 场景 | 来源 |
|---|---|
| `createWithValues(CaldavFilter, ...)` | `filter.calendar.defaultPriority` |
| `createWithValues(其他 Filter, ...)`（今天/标签/搜索） | 新任务最终落到默认清单，取 `defaultFilterProvider.getDefaultList().calendar.defaultPriority` |
| `createWithValues(null, ...)` / `create(null, ...)` | 保持全局默认，避免影响子任务、同步占位任务 |
| `basicQuickAddTask` 无 filter（小组件/通知/手表/Tasker） | 显式解析默认清单并取该清单设置 |
| API（`applyDefaults = false`） | 仍为 `Task.Priority.NONE`，不套用任何默认 |

显式来源仍然优先：values 中的 `IMPORTANCE` 和标题解析（如 `!!!`）会覆盖默认值。

### 4.2 KMP 编辑页：TaskEditLoader

新建空白任务时先解析目标清单，再应用默认值：

```kotlin
.apply {
    applyDefaults(defaults, list?.calendar?.defaultPriority)
    setDefaultReminders(defaults)
}
```

`applyDefaults` 中 `priority = listPriority ?: defaults.defaultPriority`。

## 五、边界

- 已存在任务、编辑任务不受影响
- 子任务仍走全局默认（本次不做）
- 编辑过程中切换清单不会重新计算优先级；默认值只在任务创建时应用
- 「共享文本到 Tasks」等无清单上下文入口仍走全局默认
- 清单级设置仅本机生效，不同步到远端服务
- fork 的「设置导出/导入」只覆盖偏好项，不包含该字段；完整 JSON 备份包含

## 六、测试

| 测试 | 覆盖点 |
|---|---|
| `CommonMigrationsTest` | 98→99 增加可空列、旧数据保留 |
| `TaskEditViewModelTest`（KMP） | 清单有值覆盖全局默认；清单为 null 回退全局默认 |
| `TaskCreatorTest`（Android） | CaldavFilter 清单值生效；`applyDefaults = false` 仍为 NONE |

### 验证结果（2026-10-22）

- `:data:jvmTest --tests "org.tasks.data.db.CommonMigrationsTest"`：12 项通过
- `:kmp:jvmTest --tests "org.tasks.viewmodel.TaskEditViewModelTest"`：180 项通过
- `:app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a`：通过
- `:app:compileGenericDebugAndroidTestKotlin -PbuildAbi=arm64-v8a`：通过
- `data/schemas/org.tasks.data.db.Database/99.json` 已生成

说明：JVM 目标编译到 class 69（JDK 25），本机只有 JDK 21；上述 JVM 测试临时把
`gradle/libs.versions.toml` 的 `jdk` 调为 21 运行，运行后已还原。Android 仪器测试仅编译，
未在真机/模拟器执行。

手工验证：

1. 清单 A 设为 High，清单 B 保持「应用默认」
2. 在 A 中 FAB / 快捷添加新建 → High
3. 在 B 中新建 → 当前全局默认
4. 从「今天」/ 标签视图新建（最终落到默认清单）→ 默认清单的设置
5. 全局默认改为 Medium → A 仍 High，B 跟随 Medium
6. 清单改回「应用默认」→ 跟随全局

## 七、改动文件索引

| 层 | 文件 |
|---|---|
| 实体/迁移 | `data/src/commonMain/kotlin/org/tasks/data/entity/CaldavCalendar.kt`、`data/src/commonMain/kotlin/org/tasks/data/db/Database.kt`、`data/src/commonMain/kotlin/org/tasks/data/db/CommonMigrations.kt` |
| 清单设置 | `kmp/src/commonMain/kotlin/org/tasks/compose/settings/ListSettingsScreen.kt`、`kmp/src/commonMain/kotlin/org/tasks/viewmodel/ListSettingsStateManager.kt`、`ListSettingsCallbacks.kt` |
| 保存 | `LocalListSettingsViewModel.kt`、`ListSettingsViewModel.kt`、`CaldavCalendarSettingsViewModel.kt`、`EtebaseCalendarSettingsViewModel.kt` |
| 创建 | `app/src/main/java/com/todoroo/astrid/service/TaskCreator.kt`、`kmp/src/commonMain/kotlin/org/tasks/viewmodel/TaskEditLoader.kt` |
| 字符串 | `kmp/src/commonMain/composeResources/values/strings.xml` |
