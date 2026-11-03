# 列表页原地添加子任务（长按选中 → FAB 变身 → chip "+" 接力）

日期：2026-11-03（方案已全部实现；编译通过，功能已上机验证，TalkBack 朗读仍待单独验证）
前一版：2026-10-27 的「chip 行常驻 +」，已被本文档的 v2 方案取代（对比见第 9 节）

## 0. 实现状态（2026-11-03）

已落地（编译通过，`./gradlew :app:compileGenericDebugKotlin`）：

- 有子任务的任务，chip 行默认显示 "+"（`AddSubtaskChip`）。
- 点击 "+" 立即创建子任务 → 展开父任务 → 滚动到新行 → 直接进入行内标题编辑。
- 手动排序树同步插入（`SubtasksFilterUpdater.onCreateSubtask`）。
- **FAB 变身**：ActionMode 下选中恰好 1 个可写任务时，FAB 从圆形 "+" 扩展为
  「＋ 子任务」，点击即在该任务下建子任务并退出 ActionMode；其余情况（无选中 / ≥2 选中 /
  任务只读 / filter 不可写）恢复原「新建任务」语义。实现用
  `ExtendedFloatingActionButton`（`fragment_task_list.xml`），空闲态 `shrink()` 保持原视觉。
- **空标题回收**：提交空（或纯空白）标题时删除刚建的子任务并提示。
- **落库逻辑单一实现**：`SubtaskCreator.saveSubtask`，详情页（`TaskEditViewModel`）与列表页
  共用，含「已完成父任务的子任务继承 `completionDate`」与 Google / 非 Google 两条分支。
- **不可见时的降级**：新行在当前排序下不渲染时提示 `subtask_added`，不再静默返回。

本轮（2026-11-03 审阅）修正的点：`Chip` 的 `contentDescription` 落在 `Surface` 的同一语义
节点（原 `Box(mergeDescendants)` 包不住自带合并的 Surface，会拆成两个节点）；chip "+" 在
ActionMode 下先退出多选再创建；未提交标记移入 `TaskListViewModel`（配置重建不丢）；FAB 在
首次布局前变更状态时改用 `doOnNextLayout` 补一次。

两个实现中的坑（后续改动别踩回去）：

1. `commitInlineTitleEdit` 在「原标题 == 草稿」时走**同步快路径**，直接清状态返回，
   **不发出 `inlineTitleCommitResults`**。新子任务的原标题是空串，用户不输入时正好命中这条
   路径，所以「在提交结果里回收空行」永远不会执行。回收必须放在 `commitInlineTitleEdit`
   入口处拦截，而不是依赖结果回调。
2. Material 1.13+ 的 `ExtendedFloatingActionButton` 继承自 **`MaterialButton`**（不是
   `FloatingActionButton`），图标着色属性是 `iconTint`，XML 图标属性是 `app:icon`
   （`app:srcCompat` 无效）。写成 `imageTintList` 会直接编译失败。
3. **不要按位置遍历 adapter 去找行**（2026-11-03 真机崩溃教训）。`SectionedDataSource`
   有两个数对不上：`size` = `tasks.size + sections.size`，而 `getItem(position)` 对非 header
   位置用 `position - 分组头数量` 去索引 `tasks`。开启分组后（真机列表有「高优先级」这类头）
   且存在折叠分组时，这两个计数会漂移，`for (position in 0 until itemCount) getItem(position)`
   就会 `IndexOutOfBoundsException: Index 195 out of bounds for length 195`。
   正确做法：从**平铺任务列表**查找，再反算 sectioned 位置
   （`SectionedDataSource.findTask`：`index + 分组头数(firstPosition <= index)`），
   两侧共用同一份 `firstPosition`，永不越界。`TaskListFragment.findRow` 已改走这条路径，
   FAB 查找选中任务与 `revealSubtask` 定位新行都受益。
   注意：`taskAdapter.count` / `isHeader(i)` 是另一套（平铺任务数）语义，混用会静默找不到行。


## 1. 目标与非目标

目标：不进入任务详情页就能创建子任务，并且创建后立刻输入标题。

- **一次可达的入口**：长按任务（已进入多选 ActionMode）时，右下角 FAB 从"新建任务"
  变为"添加子任务"，点一下就在该任务下建出子任务。
- **可持续追加**：父任务第一次拥有子任务后，chip 行的展开按钮（`SubtaskChip`）旁边
  出现"+"chip，之后每次点它都继续在该父任务下建子任务，不再需要长按。
- **零额外行高**：没有子任务的行保持现状（长按才有入口），不像 v1 那样给所有顶层任务
  撑出第二行。

非目标：不做批量添加、不做"在指定位置插入"、不改变子任务缩进/拖动排序语义。顺序上只有
`preferences.addTasksToTop()` 影响 SQL 层的平面排序；手动排序树里新节点固定插到父节点的
`children[0]`（与 `SubtasksFilterUpdater.onCreateTask` 一致），即"作为父任务的第一个子任务"，
不随该偏好变化。

## 2. 交互定义

### 2.1 状态机

| 状态 | FAB 外观 | FAB 点击行为 | chip 行 "+" |
|---|---|---|---|
| 空闲（无 ActionMode） | 圆形 "+"，`action_create_new_task` | 现有 `createNewTask()` | 仅在 `children > 0` 且父任务可写时显示 |
| ActionMode，选中 **恰好 1 个**可写任务 | **扩展 FAB**：`+` 图标 + 文字"子任务" | `addSubtaskTo(selectedTask)`，随后**退出 ActionMode** | 同上；ActionMode 下点它同样**先退出多选**再创建（否则行内编辑与多选态互相打架） |
| ActionMode，选中 ≥ 2 个 | 恢复圆形 "+"（保持现有语义） | 现有 `createNewTask()` | 同上 |
| ActionMode，选中的 1 个任务只读 / 按 filter 不可写 | 恢复圆形 "+" | 现有 | 不显示 |

只读判定沿用现有两处：`filter.isWritable`（`TaskListFragment.kt:511`）与
`Task.isReadOnly`（`TaskListFragment.kt:1470`、`:1381` 的用法）。

### 2.2 创建流程（两个入口共用同一段逻辑）

1. `commitInlineTitleEdit {}`：先把可能进行中的行内标题提交掉，与
   `onChangeDueDate`（`TaskListFragment.kt:1543`）同模式。
2. 记下父任务（`Task`）与其清单（`CaldavTask`/`GoogleTask`），**退出 ActionMode**
   （原因见 4.1，非风格选择）。
3. 若父任务处于折叠态，`taskSaver.setCollapsed(parentId, false)` 展开。
4. 创建子任务（复用详情页的 provider 分支，见 4.2）。
5. 在手动排序树中把新节点挂到父节点下（见 4.3），否则它会以 indent 0 出现。
6. `listViewModel.invalidate()` → 列表刷新 → `RecyclerView.scrollToPosition` 到新行
   → `listViewModel.beginInlineTitleEdit(newId, "", null, null, null)` 起键盘。
7. 若用户最终提交的是空标题：删除这条刚建的空任务（第 6 节决策 D2）。

### 2.3 chip "+" 的渲染条件

**已确认（2026-11-03）：已有子任务的任务，chip 行默认就显示 "+"，不需要任何前置操作、
不受偏好开关控制。**

在 `TaskViewHolder.setupChips()`（`TaskViewHolder.kt:551`）里，紧跟 `SubtaskChip`：

```
if (canAddSubtask) {
    AddSubtaskChip(onClick = { callback.onAddSubtask(this) })
}
```

其中 `canAddSubtask = children > 0 && !task.isReadOnly && filter.isWritable`。

- 位置：`SubtaskChip` 之后、其余 chip 之前，读作「▼ 3 ＋ ［标签］…」。
- 尺寸与样式与 `SubtaskChip` 同款（`kmp/.../chips/Chip.kt`，26dp 高、`shapes.small`），
  因此**不会额外增加行高**（该行本来就已渲染）。
- **不依赖 `preferences.showSubtaskChip`**：用户关掉展开 chip（`SubtaskChip` 那一项）时，
  "+" 仍然显示——添加子任务与"是否显示展开按钮"是两件事。
- 无状态：不给"刚刚添加过的父任务"做会话标记。理由是对比"仅对当前父任务显示"，
  无状态版本在滚动复用、`notifyItemChanged`、进程重建后都自然正确，成本为零。
- `indent > 0` 的子任务只要有子任务同样显示（Tasks 支持多层嵌套，与详情页
  `SubtaskRow` 一致）。
- 无障碍：`Chip` 已有可选 `contentDescription`，描述写在 `Surface` 的 `modifier` 上——
  `Surface(onClick)` 内部把 `clickable` 施加在同一个 layout node 上，因此描述与点击动作落在
  **同一个语义节点**。**不要**改成外层 `Box(Modifier.semantics(mergeDescendants = true))`：
  clickable 节点自身是 merging 节点，父节点无法合并它，会拆成"有描述无动作 + 无描述按钮"
  两个朗读站点。图标 chip 拿到描述时同时把图标自身的语义置空（`TasksIcon(contentDescription
  = null)`），否则图标名 "add" 会被合并进朗读内容。

## 3. 与既有交互的冲突核对

| 既有交互 | 冲突点 | 处理 |
|---|---|---|
| 长按 = 多选 ActionMode / 拖动排序（`TaskListFragment.kt:1525`） | 本方案借用该状态，不新增手势 | 不变量：拖动排序模式下 `startActionMode` 不被调用（`recyclerAdapter?.dragAndDropEnabled() != true`），FAB 也不会变身，两个模式天然互斥 |
| 行尾 48dp 详情箭头 | 无（不新增行内热区） | — |
| 点击标题 = 原地编辑 | **ActionMode 激活时点击标题是"选中切换"而不是编辑**（`TaskListFragment.kt:1465`） | 这是"创建后必须退出 ActionMode"的硬性原因（4.1） |
| 横向拖动改缩进 | chip 行新增热区位于行首，正是最容易被当作拖动起点的位置 | 在模拟器上验证（第 8 节 V3）；必要时把 "+"chip 的点击热区限制为 `Surface` 自身（`Chip` 已如此） |
| 批量操作 FAB 误触 | 用户长按是为了删除/移动，FAB 突然变成"加子任务"，误触会在任意任务下生成空子任务 | 单选中才变身 + 扩展 FAB 带文字 + 空标题自动回收（D2） |
| Snackbar 锚点 `setAnchorView(R.id.fab)`（`:1049`） | FAB 类型若换成另一个视图，锚点会失效 | 换的是**同一个视图的类**（`ExtendedFloatingActionButton` 支持 `shrink()/extend()`），不新增第二个视图 |

## 4. 实现路径

### 4.1 FAB 变身

- `fragment_task_list.xml:83` 的 `FloatingActionButton` 换成
  `com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton`，
  同时声明 `app:icon="@drawable/ic_outline_add_24px"` 与 `android:text="@string/subtask_add"`，
  初始 `shrink()`，仅在单选中时 `extend()`。注意收起态**不是正圆**：`ExtendedFloatingActionButton`
  是圆角矩形（默认 16dp 圆角 + 56dp 高），与原 `FloatingActionButton` 的正圆有可见差异，
  该差异未做补偿，属待上机确认项（第 8 节 V1/V8）。
- 切换点在 `TaskListFragment.updateModeTitle()`（`:1341`）—— 这是选中数量的**唯一**
  汇合点（`TaskListRecyclerAdapter.toggle` `:138`、`setSelected` `:1367`、
  `startActionMode` `:1332` 都走它）；建议改名为 `updateSelectionUi()` 并顺带刷新 FAB。
- 恢复时机：`onDestroyActionMode`（`:1308`）里 `shrink()`。
- 需要知道"选中的那一个任务是否可写/属于哪张清单"：`TaskAdapter` 只存 id
  （`TaskAdapter.kt:49`），用 `taskDao.fetch(id)` 异步取一次并缓存到字段即可。
- 无障碍：`contentDescription` 随状态切换（`subtask_add` / `action_create_new_task`）。

**为什么创建后必须退出 ActionMode（技术约束，不是取舍）**：ActionMode 激活时
`onTitleClick`/`onOpenDetails`/`onLinkClicked` 全部走 `recyclerAdapter.toggle`
（`:1451`、`:1465`、`:1502`），新的子任务行无法进入行内标题编辑。若想保留 ActionMode，
就只剩"建空白行、事后再点"这条退路，与"原地输入"的目标不符。

### 4.2 子任务落库（详情页与列表页共用一份实现）

参考实现原本是 `TaskEditViewModel.saveTask` 里的子任务循环，现已抽出为
`app/.../service/SubtaskCreator.kt`，**两个入口都调它**，不要再复制这段逻辑：

- `createSubtask(parent, title)`：列表页入口。取父任务所在清单（`DefaultFilterProvider`），
  清单只读返回 null，然后建任务并落库。
- `saveSubtask(parent, subtask, list, addToTop)`：真正落库的部分，也是详情页（子任务已在 UI 里
  建好、字段齐全）走的那条路径。`addToTop` 由调用方给：列表页传
  `preferences.addTasksToTop()`，详情页传 `if (isNew) false else preferences.addTasksToTop()`。

```
子任务 = taskCreator.createWithValues(title)
if (!subtask.isCompleted) subtask.completionDate = parent.completionDate   // 子任务随父完成
taskDao.createNew(subtask); alarmDao.insert(getDefaultAlarms(...))
if (list.isGoogleTasks) {
    googleTask.isMoved = true; subtask.parent = parent.id
    googleTaskDao.insertAndShift(subtask, googleTask, top = addToTop)
} else {
    if (list.account.pushesRemoteParent)
        caldavTask.remoteParent = caldavDao.getRemoteIdForTask(parent.id)
    subtask.parent = parent.id
    taskSaver.save(subtask, null)                 // 必须用 TaskSaver，不能直连 DAO
    caldavDao.insert(subtask, caldavTask, addToTop = addToTop)
}
```

- `Task.parent` 是本地 id（`data/.../entity/Task.kt:87`），不是 uuid。
- **不要**直接 `taskDao.createNew()` 了事：只建任务不挂清单，缺 `remoteParent` 与 Google Tasks
  的 shift 处理，行会以"不属于任何清单"的状态出现。
- `completionDate` 继承是详情页既有行为（在已完成任务下加子任务，子任务同样是已完成的）；
  列表页 chip 因此**不需要**排除已完成父任务，两处行为保持一致。

### 4.3 手动排序树必须同步

列表里的缩进来自 `SubtasksFilterUpdater` 的序列化树，而不是 SQL 的 `parent` 字段：

- 新增顶层任务的路径是 `taskAdapter.onTaskCreated(uuid)` → `updater.onCreateTask()`
  （`SubtasksFilterUpdater.kt:284`），它把节点加到 `treeRoot.children[0]`、indent 0。
- 子任务需要 `onCreateSubtask(parentUuid, uuid)`：插入到父节点 `children[0]`，
  indents 沿用 `setNodeIndent(node, parent.indent + 1)`（`:198`）并走 `applyToFilter()`。
- 不做这一步的后果：新行以 indent 0 出现在列表顶部/末尾，看起来"没有成为子任务"，
  直到某次完整 `initialize()` 重建树才恢复。
- 只在 `filter is AstridOrderingFilter` 时执行；其它排序模式按 4.4 的降级处理。

### 4.4 新行不可见时的降级（已实现）

子任务行是否渲染受排序/分组与"显示子任务"配置影响。`revealSubtask` 的实际做法：

1. 刷新后 3 秒内能在数据源里找到新行 → 滚动 + 行内编辑。
2. 找不到（当前排序不展示子任务行、分组折叠、或父任务已完成而被隐藏）→ 只更新父任务的
   chip 计数并 Snackbar `subtask_added`（"已添加子任务"），不起键盘，不报错。
3. 子任务本身已经创建成功，降级只影响"能不能立刻输入标题"，不撤销创建。

## 5. 空标题的回收（决策 D2）

新建子任务以空标题落地，用户若直接返回/点别处：

- 方案 A（推荐，已实现）：`commitInlineTitleEdit` 入口处拦截——新子任务原标题为空串，用户
  不输入时命中"无变化"同步快路径、不会回调，所以回收判断放在入口而不是提交结果回调里。
  删除走 `listViewModel.markDeleted(listOf(id))`（与批量删除同一条路径），Snackbar 提示
  `subtask_add_discarded`（"已移除空白子任务"）。
- 方案 B：保留空标题任务，与上游"新建任务后直接返回会留下空任务"一致（
  `createNewTask()` 就是 `addTask("")`）。

推荐 A：列表里留一条无标题行没有任何信息价值，而这条任务是我们刚刚创建、可判定归属的，
删除风险可控（只删 `id == justCreatedId` 且标题仍为空的情况）。

## 6. 待确认决策

| 编号 | 决策 | 推荐 |
|---|---|---|
| D1 | ≥2 选中时 FAB 行为：恢复"新建任务" / 隐藏 | 恢复现状（回归面最小） |
| D2 | 空标题子任务：自动回收 / 保留 | 自动回收 + Snackbar |
| D3 | FAB 变身用扩展 FAB（带"子任务"文字）/ 只换图标 | 扩展 FAB（换图标无法自解释，误触代价是脏数据） |

## 7. 改动文件清单

| 文件 | 改动 |
|---|---|
| `app/src/main/res/layout/fragment_task_list.xml` | FAB → `ExtendedFloatingActionButton`（icon + text） |
| `app/src/main/java/.../TaskListFragment.kt` | `updateFabForSelection()`（FAB 状态 + 选中任务解析）、`onFabClicked()`、`onAddSubtask()`（chip 回调，ActionMode 下先退出多选）、`revealSubtask()` 降级提示、行内编辑接线 |
| `app/src/main/java/org/tasks/tasklist/TaskViewHolder.kt` | `setupChips()` 加 `AddSubtaskChip`；`ViewHolderCallbacks.onAddSubtask(task)` |
| `kmp/src/commonMain/kotlin/org/tasks/compose/chips/` | 新增 `AddSubtaskChip`（`Chip(icon = TasksIcons.ADD)`）；`Chip` 增加可选 `contentDescription`；`TasksIcon` 增加 `contentDescription`（可置空，避免图标名进入 chip 的朗读） |
| `kmp/src/commonMain/kotlin/org/tasks/tasklist/SectionedDataSource.kt` | `findTask`：从平铺列表反算 sectioned 位置（`findRow` 用） |
| `app/src/main/java/org/tasks/ui/TaskListViewModel.kt` | 未提交子任务标记（`mark/is/forgetUncommittedSubtask`），配置重建不丢 |
| `app/src/main/java/com/todoroo/astrid/subtasks/SubtasksFilterUpdater.kt` | `onCreateSubtask(parentUuid, uuid)` |
| `app/src/main/java/.../service/SubtaskCreator.kt`（新） | `createSubtask` + `saveSubtask`；`TaskEditViewModel.saveTask` 已改为调用 `saveSubtask` |
| `app/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml` | `subtask_add`、`subtask_added`、`subtask_add_discarded`（只加 en + zh-rCN） |

## 8. 验证清单

- 构建：`./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a`；整包
  `:app:assembleGenericDebug`。
- 仪器/手工（用 `.qoder/skills/tasks-emulator-verify`）：V1 单选中 FAB 变扩展态且带文字；
  V2 多点一个任务后 FAB 立刻缩回；V3 chip "+" 的点击不被横向拖动缩进抢走；
  V4 父任务折叠态下添加后自动展开且新行可见；V5 行内输入空标题后任务被回收；
  V6 未选中/只读清单下入口不出现；V7 TalkBack 能念出"添加子任务"（**待验证**：需开 TalkBack
  确认只有**一个**焦点站点、且朗读里不含图标名 "add"）；V8 深色主题下 chip 对比度与收起态
  FAB 的圆角矩形外观。
- 已完成上机验证：chip "+" 点击建子任务、进入行内编辑（真机）。
- 账户矩阵：本地清单、Google Tasks、CalDAV（`pushesRemoteParent = false/true`）各一次，
  确认新子任务的 `parent`/`remoteParent` 正确、同步后不丢失层级。
- 单元测试：`SubtaskCreator`（Google / 非 Google 两条分支）、
  `SubtasksFilterUpdater.onCreateSubtask`（插入位置与 indent）。

## 9. 与 v1（2026-10-27）的对比

| | v1：chip 行常驻 "+" | v2：长按 + FAB 变身 + chip 接力（本方案） |
|---|---|---|
| 无子任务的行 | 行高涨到 ~78-80dp，一屏少约 1/3 条 | 不变 |
| 可达性 | 一步 | 两步（长按 → FAB），之后由 chip "+" 一步 |
| 手势冲突 | 无 | 复用已有的多选状态；FAB 语义在选中时改变 |
| 误操作风险 | 低 | 中（多选流程中误触 FAB 会建出空子任务，靠 D2 回收） |
| 与详情页的观感一致性 | 高 | 高（chip "+" 与展开按钮并排，位置语义连续） |

v1 的否决理由表（行尾 "+"、长按弹菜单、滑动露出）仍然有效，其中"长按弹菜单"被否是
因为长按已被多选占用——v2 不再新增菜单，而是改写选中态下 FAB 的语义，绕开了该限制。

## 10. 上游迁移影响

本方案落在老 UI（`TaskListFragment` + `TaskViewHolder` + `fragment_task_list.xml`）。
上游切到 KMP 列表（`composeApp/.../TaskRow.kt`）后需要复刻的部分：

- chip "+"：轻交互，在 `hasChips` 分支加一个图标 chip，成本低。
- FAB 语义随选择态变化：需要新 UI 侧暴露"当前选中集合"，成本中等；
  若届时新 UI 没有多选态，可退化为"只在 chip 行提供 +"，即本方案的 Phase 1。
