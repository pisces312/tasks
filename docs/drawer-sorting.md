# 导航抽屉排序与原地手动调序

> 创建时间：2026-10-24
> 状态：已实现并在设备验证（2026-10-24）

## 一、需求

进入某个清单后，向左/右划调出的清单抽屉里，条目原本只按一种顺序显示（名称的自然排序），
清单一多就找不到。需要：

1. 提供排序模式：**手动顺序** 与 **名称**；
2. 入口放在抽屉顶部一个排序图标里；
3. 中文清单名在「名称」模式下按**拼音**排序，而不是按码点；
4. 手动顺序要能**在那个界面原地调整**（长按拖动），不需要跳到「自定义抽屉」页。

明确不做的：

- 「按任务数排序」曾经实现过，最终**移除**。原因是任务数每次都要从 DAO 现查，抽屉打开即全表统计，
  收益不值这个开销。
- 分组标题（清单 / 标签 / 地点 / 每个 CalDAV 账号）之间的顺序**不变**，仍是
  filters → tags → places → 每账号一组。只有**子项**参与排序和拖拽。

## 二、排序模式

`kmp/src/commonMain/kotlin/org/tasks/filters/DrawerSortMode.kt`

```kotlin
enum class DrawerSortMode { MANUAL, NAME }
```

- `fromValue(value)` 解析失败或值为空时**回落到 `MANUAL`**，所以历史上写进 DataStore 的
  其它取值（例如已删除的 `TASKS` / `COUNT`）不会让抽屉崩或变空。
- 持久化：DataStore 字符串键 `TasksPreferences.drawerSortMode` = `"drawer_sort_mode"`，
  存枚举名。切换模式在 `DrawerViewModel.setSortMode()` 里先更新 `State.sortMode` 再落盘，
  失败只记日志，不影响 UI。

排序逻辑集中在 `DrawerSorting.kt`：

```kotlin
internal fun List<Filter>.sortForDrawer(mode: DrawerSortMode): List<Filter>
```

`FilterProvider` 里原先私有的 `List<Filter>.sort()` 和 `COMPARATOR` 被删掉，
四个子项构造点（自定义过滤器、标签、地点、每账号清单）统一改调 `sortForDrawer(sortMode)`，
`drawerItems()` 增加 `sortMode` 参数。账号/分组的顺序、内建过滤器（MyTasks、Inbox 等）都不受影响。

**MANUAL 的语义**：按各实体已存的 `order` 排；`order == NO_ORDER(-1)` 的项排在最后并按名称兜底；
如果整组都没设过 order，则完全退化成自然名称排序。也就是说从没拖过拽的用户看到的顺序和改动前一致。

**NAME 的语义**：忽略存储的 `order`，只按名称。存储的手动顺序不会被清掉，
切回 MANUAL 立刻恢复——这一点在设备上验证过。

## 三、名称排序与中文拼音

`AlphanumComparator` 增加一个可注入的文本比较器参数，默认值保持原行为：

```kotlin
class AlphanumComparator<T>(
    private val getTitle: (T) -> String?,
    private val compareText: Comparator<String> = CASE_INSENSITIVE,
)
```

这样数字段（`清单2` < `清单10`）的分段逻辑复用，只把「段内文本怎么比」换掉，
上游同文件冲突时也只需要看构造函数一处。

`LocalizedStringComparator.kt` 是 `expect fun localizedStringComparator(): Comparator<String>`：

| 平台 | 实现 | 说明 |
|---|---|---|
| JVM（Android + desktop） | `jvmCommonMain/.../LocalizedStringComparator.kt`：`java.text.Collator`，`strength = PRIMARY` | 只要任一侧含汉字就切到 `Locale.CHINA` 规则，其余用系统默认 locale。原因：root collation 对汉字按**部首/笔画**排，不是拼音 |
| iOS | `iosMain/.../LocalizedStringComparator.ios.kt`：`NSString.localizedStandardCompare` | 与系统文件列表一致，天然按拼音，且自带数字的自然序 |

`hasHan()` 覆盖 CJK 基本区 + 扩展 A + 兼容表意区（`0x3400-0x4DBF`、`0x4E00-0x9FFF`、`0xF900-0xFAFF`）。
函数**每次调用都新建 Collator**——`Collator` 不是线程安全的，而排序可能在 IO 协程里发生。

搜索态（输入了查询词）走的是拉平后的结果列表，`DrawerViewModel.updateSearch()` 在 NAME 模式下
用同一个本地化比较器按名称排；MANUAL 模式保持分组返回的顺序，因为手动顺序是**每组内部**的语义，
把不同组的 `order` 混在一起排会得到无意义的交错结果。

## 四、抽屉顶部入口

`kmp/src/commonMain/kotlin/org/tasks/compose/drawer/TaskListDrawer.kt`

- `DrawerSortButton`：`TasksIcons.SWAP_VERT` 图标按钮，`Alignment.TopEnd`，48dp。
  `contentDescription` 复用上游已有串 `sort_mode`（Sort / 排序）。
- 弹出 `DropdownMenu`，`DrawerSortMode.entries` 逐项渲染 `RadioButton` 表示当前选中。
- 选中 MANUAL 时菜单底部附一行 `drawer_reorder_hint`（"长按并拖动可调整顺序"），
  否则这个能力没有任何可见入口。
- 只在 `expanded` 时显示；`topGutter` 相应增加 `SortButtonSize + SortButtonGap`（48 + 8 dp），
  避免第一行被按钮压住。窄侧栏（rail，`expanded = false`）不显示按钮，也不允许拖拽。
- 新增字符串只加 `values` 与 `values-zh-rCN`（fork 约定：不为其它语言补翻译，回退英文）：
  `drawer_sort_manual`、`drawer_sort_name`、`drawer_reorder_hint`。

## 五、原地拖拽调序

拆成一个不含 Compose 的状态类 `DrawerReorderState`（`kmp/.../compose/drawer/DrawerReorder.kt`），
便于纯 JVM 单测；UI 侧只负责把手势喂给它。

**可拖范围**由 `DrawerItem.reorderGroup()` 决定，同一字符串才算一组：

| 条目 | group key |
|---|---|
| `CaldavFilter` | `caldav:<accountUuid>`（每个账号的清单各自成组，不能跨账号拖） |
| `TagFilter` | `tag` |
| `PlaceFilter` | `place` |
| `CustomFilter` | `filter` |
| 分组标题 / 内建过滤器 / 登录提示 / 错误项 | `null`，不可拖 |

**启用条件**：`expanded && query.isBlank() && sortMode == MANUAL`。
NAME 模式下手势完全禁用，避免出现「拖了但顺序又被名称排回去」的错觉。

**拖拽算法**：`detectDragGesturesAfterLongPress`（Compose Foundation），
`onDragStart` 记录起始项和行高，`onDrag(deltaY)` 累加位移，每越过**半行**就与相邻的同组项交换一次
并把 `offsetY` 减去一整行，返回残余位移作为该行的 `translationY`。
越过组边界（邻居 `reorderGroup()` 不同）时交换失败并停下，手指继续拖也不会把条目挤出组外。
行高优先取 `listState.layoutInfo.visibleItemsInfo` 里这一行的真实高度，
取不到时回落到 48dp 常量。

**视觉反馈**：拖拽中的行 `zIndex = 1f` + `graphicsLayer { translationY }` + `shadowElevation 6dp`，
其余行靠 `Modifier.animateItem()` 平移到新位置；起手时给一次 `HapticFeedbackType.LongPress`。

**不回弹**：松手后 `reorderItems` 继续显示拖出来的顺序，直到 `drawerState.drawerItems`
（数据库刷新后重新下发的列表）到达，由 `LaunchedEffect(drawerState.drawerItems)` 清空。
`onDragCancel` 直接丢弃临时顺序。`finish()` 在没发生交换时返回 `null`，
因此纯粹的长按不拖不会写库。

## 六、顺序持久化

`DrawerViewModel.setManualOrder(ordered: List<Filter>)` 拿到拖完的那一组，
按新视觉顺序写索引；每组单独编号，因此不存在跨组串号。

| 类型 | DAO | 列 |
|---|---|---|
| 清单 | `caldavDao.setOrder(calendarId, order)` | `caldav_lists.cdl_order` |
| 标签 | `tagDataDao.setOrder(tagId, order)` | `tags.td_order` |
| 地点 | `locationDao.setOrder(placeId, order)` | `places.place_order` |
| 自定义过滤器 | `filterDao.setOrder(id, order)` | `filters.f_order` |

**只有标签是同步元数据**：拖过标签后额外调用 `tagMetadataSync.markOrderDirty()` 并触发
`syncAdapters.sync(SyncSource.METADATA_CHANGE)`，把顺序带到其它设备。
清单/地点/自定义过滤器的 order 是本地字段，不写进 CalDAV。

写完统一 `updateFilters()` 重新下发列表。任何异常只 `Logger.e`，不抛出到 UI。

`DrawerViewModel` 构造函数新增 `tagDataDao`、`filterDao`、`locationDao`、`tagMetadataSync`、
`syncAdapters` 五个依赖，两个 DI 站点同步更新：
`app/src/main/java/org/tasks/injection/ApplicationModule.kt`（Hilt）和
`composeApp/src/commonMain/kotlin/org/tasks/di/CoreModule.kt`（Koin）。
Android 侧在 `HomeScreen.kt` 接线，desktop/iPad 在 `App.kt` 的两个 `TaskListDrawer` 调用点
（模态抽屉 + 侧栏）接线 `onSortModeChange` / `onReorder`。

## 七、测试

| 文件 | 覆盖 |
|---|---|
| `kmp/src/commonTest/kotlin/org/tasks/filters/DrawerSortingTest.kt` | MANUAL 无存储顺序时回落自然名称序；MANUAL 尊重存储 order；NAME 忽略 order |
| `kmp/src/jvmTest/kotlin/org/tasks/filters/DrawerSortingPinyinTest.kt` | 香蕉/苹果/梨/北京按拼音排；中文前缀下数字段仍是数值序（清单1 < 清单2 < 清单10） |
| `kmp/src/commonTest/kotlin/org/tasks/compose/drawer/DrawerReorderTest.kt` | 8 项：同账号成组/跨账号不交换、标题与内建项无组、向下/向上拖交换、拖到组尾停住、贴边不动则 `finish()` 返回 null、不足半行不算调序、只有组内子项能起手 |

命令：`./gradlew :kmp:jvmTest`（本地 1389 项通过）。

## 八、设备验证

`org.tasks.debug`（generic debug，arm64-v8a）装到 pixel6 模拟器（API 34）后逐项确认：

1. MANUAL 下长按「Zebra」拖到「Default list」下方 → 列表原地重排，logcat 记录新顺序；
2. `force-stop` 后冷启动，顺序保持；
3. 切到 NAME：中文清单按拼音排（苹果在香蕉前），长按无反应、顺序不变；
4. 切回 MANUAL：第 1 步拖出来的顺序恢复；
5. 拖回原位再验证一次（此时已移除临时调试日志的最终 APK）。

`adb shell input motionevent DOWN/MOVE/UP` 分多次调用**不会**构成一个手势（每次进程独立 downTime），
用 `adb shell input draganddrop x1 y1 x2 y2 duration` 才能触发 Compose 的长按拖拽。
另外截图像素是缩放后的，点击坐标要按真实分辨率换算。

## 九、构建

- `./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a`
- `./gradlew :composeApp:compileKotlinDesktop`（确认 iOS/desktop 共用的 expect/actual 与 App.kt 接线没断）
- `./gradlew :app:assembleGenericDebug -PbuildAbi=arm64-v8a`

## 十、合并上游注意

1. `FilterProvider` 的私有 `sort()` / `COMPARATOR` 已删除。若上游再引入一处 `.sort()`，改成
   `sortForDrawer(sortMode)` 并把 `sortMode` 参数沿调用链透传下去。
2. `AlphanumComparator` 构造函数第二参数带默认值，上游新增的使用点不受影响。
3. `DrawerViewModel` 构造参数顺序为
   `filterProvider, taskDao, caldavDao, tagDataDao, filterDao, locationDao, tagMetadataSync, syncAdapters, tasksPreferences, purchaseState, ...`；
   上游若在此处加依赖，两个 DI 站点（Hilt + Koin）都要同步。
4. iOS 端没有 `java.text.Collator`，走 `localizedStandardCompare`。上游若新增其它 `expect` 平台函数，
   记得三端（`jvmCommonMain` 覆盖 Android 与 desktop、`iosMain`）都有 actual，否则 composeApp 编译失败。
