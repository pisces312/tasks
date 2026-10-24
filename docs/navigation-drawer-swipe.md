# 导航抽屉滑动打开距离调研

> 日期：2026-10-24
> 状态：调研完成；仅添加代码注释，未修改手势行为

## 一、问题

进入某个清单的任务列表界面后，用左手从屏幕中部向右滑动打开清单抽屉，需要滑动很长距离。手指够不到终点，中途松手会回弹，手势体验不好。

## 二、当前实现

- `HomeScreen` 使用 Material3 `ModalNavigationDrawer`（`app/src/main/java/org/tasks/compose/home/HomeScreen.kt`），抽屉内容为 `TaskListDrawer`。
- 外层包了 `TouchSlopMultiplier`，默认 `multiplier = 3f`（`kmp/src/commonMain/kotlin/org/tasks/compose/TouchSlopMultiplier.kt`）。它只影响 Compose 手势；任务列表的原生 RecyclerView 不受影响。
- `gesturesEnabled = isListVisible`：列表 pane 可见时启用抽屉拖拽；打开任务详情时禁用。
- M3 的拖拽修饰符挂在 `fillMaxSize()` 的根 Box 上，整屏都能发起拖拽，不限于屏幕边缘。Android 系统返回手势占用了左右边缘，这也是 M3 采用整屏拖拽的原因。

## 三、根因

调研时核对的是 Gradle 缓存中的 Material3 `1.5.0-alpha27` 与 Compose Foundation `1.12.1` 源码；项目通过 Compose BOM `2026.09.00` 引入，未额外执行 `dependencyInsight` 确认实际解析版本。升级 Compose 后需要重新核对以下常量。

| 项 | 值 | 依据 |
|---|---|---|
| 位置阈值 | 抽屉宽度的 50% | Material3 `DrawerPositionalThreshold = 0.5f` |
| 速度阈值 | 400 dp/s | Material3 `DrawerVelocityThreshold` |
| 抽屉最大宽度 | 360 dp | `NavigationDrawerTokens.ContainerWidth` |
| 默认 touchSlop | 约 8 dp | Compose `ViewConfiguration` |
| 实际起手 slop | 约 24 dp | `TouchSlopMultiplier(3f)` |

慢速拖动需要约 `24dp + 180dp ≈ 204dp` 的行程；只有快速甩动（水平速度 > 400dp/s）可以不等距离直接打开。从屏幕中部起手，左拇指通常无法完成这个行程。

Compose 的 `TouchSlopDetector.getPostSlopOffset()` 只比较主轴位移，不检查横向/纵向占比。因此 `TouchSlopMultiplier(3f)` 是在补偿“整屏可拖”带来的误触：默认 8dp 时，竖向滚动中手指的横向抖动就可能让抽屉开始跟手。

## 四、候选方案与结论

| 方案 | 做法 | 结论 |
|---|---|---|
| A | 在 content 上加 `pointerInput`，8dp 起手、方向占比锁定，右滑累计约 48dp 时调用 `drawerState.open()` | 可行，但新增常驻手势检测器和 `while (true)` 循环；打开是触发后动画，不跟手。暂不做 |
| A2 | 关闭状态下禁用 M3 的开手势（`gesturesEnabled = isOpen`），开方向交给短滑检测器；关方向和 scrim 仍用 M3 | 比 A 少一层手势竞争，但仍需手势检测器。暂不做 |
| A′ | 监听 `drawerState.currentOffset`，拖过 32dp 后调用 `open()` | 不可行：`open()` 走 `MutatePriority.Default`，进行中的用户拖拽是 `UserInput`；`MutatorMutex.tryMutateOrCancel` 对低优先级调用直接抛 `CancellationException`，还会取消监听协程。松手后再调又会和 fling（同为 Default 优先级）竞争，可能出现先关后开 |
| B | 自建 `AnchoredDraggableState` 抽屉，位置阈值固定 48dp、速度阈值 250dp/s | 能跟手，但等于复制 M3 抽屉内部实现，还要处理 RTL、平板双栏、predictive back 和上游合并维护。暂不做 |
| 去掉/调小 `TouchSlopMultiplier` | 3× 改为 1× 或 1.5× | 只减少约 16dp（204→188dp），180dp 的位置阈值不变；去掉后斜向滚动更容易把抽屉带出来。保留 3× |
| 零代码兜底 | 快速右甩（>400dp/s）；底栏左下角汉堡按钮 | 可用，但不解决慢速拖动 |

## 五、最终决定

- 保留现有 M3 抽屉和 `TouchSlopMultiplier(3f)`，**不修改手势逻辑**。
- 在代码中补“为什么”的注释，避免后续被当成无用代码删掉，或误以为它能修“够不到”：
  - `HomeScreen.kt`：在 `TouchSlopMultiplier {` 上方说明整屏拖拽、主轴 slop、3× 防斜向误触，以及它不改变 50% 位置阈值和 RecyclerView 滚动。
  - `TouchSlopMultiplier.kt`：KDoc 说明只作用于 Compose 手势，内部原生 View 仍使用自己的 slop。
- 对短滑方案（A/A2）的取舍：为约 150dp 的体验收益引入常驻手势检测器和 `while (true)` 循环，维护成本不划算，保持现状。

## 六、后续如果要改进

1. 先重新核对依赖升级后的 M3 阈值常量（位置阈值、速度阈值、抽屉宽度），不要假设仍是 0.5 / 400dp/s / 360dp。
2. 若接受“触发后动画、不跟手”：A2 是风险较低的实现。触发距离 40–48dp，方向占比 ≥ 1.5 才算水平滑动；`gesturesEnabled` 只在打开状态启用，避免和 `open()` 抢 `MutatorMutex`。
3. 若要跟手：需要自建 `AnchoredDraggableState` 抽屉（B），并补 RTL、平板双栏、predictive back 和关闭手势的测试。
4. 验证矩阵：正常竖向滚动、斜向滚动（左右各偏 20–30°）、右滑打开、打开后左滑关闭、点 scrim 关闭、系统返回、RTL。

## 七、源码依据

| 事实 | 源码位置 |
|---|---|
| 位置阈值 50%、速度阈值 400dp/s | Material3 `NavigationDrawer.kt`：`DrawerPositionalThreshold`、`DrawerVelocityThreshold` |
| 抽屉宽度上限 360dp | Material3 `NavigationDrawerTokens.kt`：`ContainerWidth` |
| 拖拽挂在整屏 | Material3 `ModalNavigationDrawer`：根 `Box.fillMaxSize().anchoredDraggable(...)` |
| 锚点 `Closed = -width`、`Open = 0` | Material3 `ModalNavigationDrawer` 的 `updateAnchors` |
| 只比较主轴 | Compose Foundation `TouchSlopDetector.getPostSlopOffset()`：只取 `mainAxis()` 与 `touchSlop` 比较 |
| 用户拖拽优先级为 `UserInput` | Compose Foundation `DraggableNode.drag()`：`state.drag(MutatePriority.UserInput)` |
| 低优先级调用被取消 | Compose Foundation `MutatorMutex.tryMutateOrCancel()`：低优先级直接抛 `CancellationException` |

## 八、验证记录

- 改动仅为注释，无行为变化，未运行 Gradle 构建。
- `git diff --check` 无空白问题；`TouchSlopMultiplier.kt` 有仓库常规的 CRLF→LF 归一化提示。
