# 任务列表标题原地编辑调研与设计

日期：2026-10-20

## 背景与目标

在进入某个清单后的任务列表中，当前点击任务会打开完整任务详情。仅修改标题时，用户需要进入详情、修改、保存并返回，操作链路较长。

本功能目标：

- 点击任务标题文字后直接在列表行内显示光标并编辑标题。
- 不进入任务详情即可完成常见的标题修改。
- 保留稳定、始终可见的完整任务详情入口。
- 不改变复选框、日期、标签、多选和拖动的既有语义。

## 当前实现调研

Android 手机端的主页是 Compose 与传统 View 混合实现：

1. `HomeScreen` 使用 `ListDetailPaneScaffold` 组织列表 pane 和详情 pane。
2. 列表 pane 内嵌 `TaskListFragment`，实际列表仍由 RecyclerView 渲染。
3. `TaskViewHolder` 使用 `task_adapter_row.xml` 展示单个任务；标题目前是 `TextView`。
4. 普通状态下，任务行点击由 `TaskListFragment.onClick()` 处理，并通过 `MainActivityViewModel.setTask()` 打开 `TaskEditFragment`。
5. 标题展示支持 Markdown、链接、完成删除线和“显示完整标题”偏好。
6. 列表没有现成的主任务标题原地编辑功能。详情页的标题编辑和新建子任务输入可以作为交互参考，但不能直接嵌入当前 XML ViewHolder。
7. 标题保存应复用 `TaskSaver.save()`。该入口负责数据库更新、远端同步 dirty 标记、刷新广播、通知更新和后台同步调度；直接执行 DAO 标题更新会绕过这些副作用。

关键文件：

- `app/src/main/java/org/tasks/compose/home/HomeScreen.kt`
- `app/src/main/java/com/todoroo/astrid/activity/TaskListFragment.kt`
- `app/src/main/java/org/tasks/tasklist/TaskViewHolder.kt`
- `app/src/main/java/org/tasks/tasklist/TaskListRecyclerAdapter.kt`
- `app/src/main/java/org/tasks/tasklist/DragAndDropRecyclerAdapter.kt`
- `app/src/main/res/layout/task_adapter_row.xml`
- `app/src/main/java/org/tasks/ui/TaskListViewModel.kt`
- `kmp/src/commonMain/kotlin/org/tasks/data/TaskSaver.kt`

## 可选交互方案

### 方案 A：点击标题原地编辑，右侧按钮作为唯一详情入口（采用）

- 点击标题及标题容器内的空白：原地编辑。
- 点击右侧固定箭头：进入完整详情。
- 描述和普通行空白不再进入详情；编辑时点击这些区域只提交并退出编辑。
- 编辑状态下，右侧箭头切换为完成图标，点击后保存并退出编辑；保存成功后恢复为箭头。

优点：最符合“直接点击任务内容修改”的目标；标题无论占满一行还是多行，右侧详情按钮都不会消失；详情入口唯一且可发现，不会因标题命中边界产生误跳转。完成图标为编辑状态提供明确反馈，也给用户一个稳定的显式保存入口。

代价：改变了原先点击整行进入详情的行为，每行需要为尾部按钮保留固定宽度。

### 方案 B：点击整行进入详情，铅笔按钮原地编辑

优点：保留传统列表点击语义；误触编辑概率较低。

缺点：用户修改标题仍必须精确点击小按钮，不符合本次优先诉求。

### 方案 C：双击、长按或滑动进入其中一个模式

不采用。双击难发现；长按与多选/拖动冲突；横向滑动与现有子任务缩进拖动冲突。

### 方案 D：点击标题空白区域进入详情

不采用。标题占满时没有可靠入口，交互会随文本长度变化。

## 确认后的交互规则

| 区域/状态 | 行为 |
|---|---|
| 左侧复选框 | 完成或取消完成任务 |
| 标题非链接文字及标题容器空白 | 进入标题原地编辑 |
| 标题中的可点击链接 | 保持打开链接 |
| 右侧详情箭头（非编辑状态） | 打开完整任务详情；这是唯一详情入口 |
| 右侧完成图标（编辑状态） | 保存标题并退出编辑；保存期间禁用 |
| 日期 | 非编辑时保持现有行为；编辑时先保存再修改日期 |
| 描述或普通行空白 | 非编辑时不操作；编辑时保存并退出编辑，不进入详情 |
| 标签、地点、清单、子任务 chip | 编辑时先保存，再执行原有行为 |
| 长按 | 保持多选/拖动行为 |
| 只读任务标题 | 不进入编辑；打开详情以供查看 |
| 多选模式中的标题点击 | 切换选中状态，不进入编辑 |

编辑状态：

- 自动请求焦点、显示键盘，光标优先位于用户点击位置。
- 右侧尾部按钮从详情箭头切换为完成图标；点击完成图标、键盘 Done、失去焦点、按返回键或点击其他区域时提交并退出编辑。
- 点击另一任务标题时先保存当前标题，再进入新标题编辑。
- 普通行区域不会进入详情；退出编辑后必须再次明确点击箭头才能打开详情。
- 标题未变化时不写数据库。
- 空白标题沿用详情页规则，保存为本地化的“无标题”文案。
- 保存失败时保留草稿和编辑状态，并显示错误提示。
- 同时只允许编辑一个任务。

## 技术设计

### 行布局

在 `task_adapter_row.xml` 中：

- 使用标题容器包裹展示 `TextView` 与编辑 `EditText`；容器覆盖标题的完整矩形命中区域，子控件未消费的点击也由容器进入编辑，不会落到任务行详情导航。
- 保留原标题 `TextView`，继续承担 Markdown、链接、删除线和展示偏好。
- 增加位置重叠的 `EditText`，仅编辑状态可见；它复制标题的字体、行距、单/多行模式、最大行数和自动换行规则。原标题在编辑时保持 `INVISIBLE` 占位，因此切换时高度不跳变；继续输入导致更多换行时，描述会动态锚定编辑框并随之下移，避免与描述或 chip 重叠。
- 尾部使用固定 48dp 按钮：非编辑状态显示 24dp 向右箭头，作为唯一详情入口；编辑状态显示完成图标，作为显式保存入口。箭头在 RTL 布局中自动镜像。
- 保存期间禁用完成按钮；成功后恢复箭头，失败时保留完成图标和草稿。
- 日期位于详情按钮左侧，标题位于日期左侧；日期隐藏时标题仍止于详情按钮。

### 点击位置与光标

- 标题接收 `ACTION_UP` 时记录相对于标题控件的点击坐标，但不消费事件，因此链接处理保持不变。
- 非链接点击先使用展示文本偏移与原始标题的顺序字符映射，优先把光标放到用户点击的逻辑字符；Markdown 结构无法可靠映射时，再等待 `EditText` 完成同尺寸布局并使用 `getOffsetForPosition(x, y)` 计算屏幕位置。
- 坐标请求带唯一 ID；ViewHolder 重绑前取消旧的定位任务，成功定位后按任务 ID 和请求 ID 原子清除，避免快速重绑重复移动光标。
- 无触摸坐标的无障碍点击，以及无法映射或恢复的请求，明确回退到句尾；ViewHolder 绑定新任务时会清除旧触摸坐标和旧选择状态。

### 编辑状态与保存协调

编辑状态保存在 Android `TaskListViewModel`，不保存在 ViewHolder：

```kotlin
data class InlineTitleEditState(
    val taskId: Long,
    val originalTitle: String?,
    val draftTitle: String,
    val saving: Boolean,
    val preserveKeyboard: Boolean,
    val cursorRequestId: Long?,
    val cursorOffset: Int?,
    val cursorX: Float?,
    val cursorY: Float?,
)
```

这样可避免 RecyclerView 回收、滚动和配置变化导致草稿丢失或串到其他任务。提交开始时立即将 `saving` 设为 `true`，但不把 `EditText` 设为 disabled：

- 普通退出路径临时移除 `keyListener`、清除焦点、隐藏光标和禁止长按，形成只读输入边界，并保持与展示标题相同的文字颜色。
- 标题切换路径设置 `preserveKeyboard = true`，旧编辑框继续持有焦点和 IME 输入连接，同时通过拒绝所有变更的临时 `InputFilter` 冻结文字；保存完成后焦点直接转移到新编辑框。

两种路径都不会触发深色主题的 disabled 灰色。

ViewModel 不保存 Fragment 或 View 回调，只发布提交结果事件；当前 Fragment 在自身 view lifecycle 内消费结果。保存期间发生多个后续操作时采用 **latest-wins**：只保留用户最后一次请求的动作，避免保存完成后同时执行导航、完成任务和弹出日期选择器。

### 保存流程

1. 记录当前任务 ID 和标题草稿。
2. 根据 ID 从 `TaskDao` 重新读取最新任务，避免用列表旧快照覆盖并发修改。
3. 检查任务未删除且非只读。
4. 仅替换最新任务对象的标题。
5. 调用 `TaskSaver.save(updated, current, preserveHierarchy = true)`。
6. 成功后退出编辑并由刷新广播更新列表；瞬时失败时保留草稿。
7. 任务已删除或转为只读属于终端失败：退出编辑并刷新列表。
8. 保存失败提示提供“取消”操作，允许明确丢弃草稿，不会把用户困在编辑状态。

`preserveHierarchy = true` 用于避免标题保存覆盖拖动过程中变化的父子层级和排序字段。

### RecyclerView、渲染稳定性与手势

- ViewHolder 完整绑定时根据 ViewModel 的 `taskId` 决定显示标题还是输入框。
- 编辑状态变化使用专用 payload，扫描位置时跳过分组 header，只定向更新旧编辑行和新编辑行的标题控件、焦点与尾部图标；不再调用 `notifyItemRangeChanged()` 重绑全列表，也不重复渲染无关行的 Markdown、描述、日期和 chip。
- 从一行切换到另一行时，保存结果先执行“开始编辑新行”，再在同一批更新中让旧行退出、新行进入，避免绘制“所有行都未编辑”的中间帧；该路径不调用 `hideKeyboard()`。
- 保存旧行期间保留其焦点和 IME 连接，临时 InputFilter 阻止软键盘、硬件键盘或辅助功能修改提交快照；新行就绪后直接接管焦点。
- 新编辑框只在 `WindowInsets` 显示 IME 当前不可见时调用 `showSoftInput()`；输入法已显示时只转移焦点，避免收起后再弹出的动画。
- 保存成功的标题由 adapter 记录为短期展示覆盖，并随 payload 更新旧行 ViewHolder；后续任何全量绑定在数据库新快照真正应用前仍使用该覆盖，避免恢复旧标题。覆盖不修改 adapter/`DiffUtil` 持有的数据快照；在 `drainQueue()` 应用新列表后，若新标题等于已提交值则清除，若出现不同于原值的外部更新则让外部更新胜出。
- 保存状态不使用 `EditText.isEnabled = false`，因此不会触发主题 disabled 颜色；输入冻结不改变标题亮度。
- TextWatcher 在重新绑定前移除，避免重复监听。
- 编辑中的任务在 `ItemTouchHelper.getMovementFlags()` 中返回不可拖动。
- 开始多选或长按前提交当前编辑。
- 普通行本身不作为无障碍焦点，避免非编辑状态出现可聚焦但无动作的节点；实体触摸的行空白仍可在编辑时提交退出。
- 只读标题不注册编辑点击或无障碍点击动作；仍可通过尾部箭头进入详情查看。
- 所有独立按钮消费自己的点击事件，不向任务行冒泡。

## 范围

本次实现只包含已有任务的标题原地编辑和固定详情入口。

以下内容不在本次范围：

- 将“新建任务”改为列表内直接创建。
- 描述、日期、标签等字段的行内编辑。
- 撤销历史或多任务同时编辑。
- 将 RecyclerView 任务列表迁移到 Compose。
- 新增用户设置以切换单击语义。

## 验证重点

- 标题点击不打开详情，而是显示输入框和键盘。
- 详情按钮始终可用，长标题不会遮挡。
- Done、失焦和返回均能保存。
- 保存后数据库和刷新后的列表标题一致。
- 复选框、日期、链接和 chip 行为不回归。
- 多选、拖动、只读任务与编辑状态无冲突。
- ViewHolder 回收后草稿不会出现在其他任务。
- 从一行标题切换到另一行时，旧行不出现 disabled 变色、旧标题回闪或全列表闪烁，输入法保持显示且不执行收起/展开动画。
- 深色主题下保存中与退出编辑后的标题颜色保持稳定。
- `generic` debug Kotlin 编译通过；正式交付仍遵循 `generic` + `arm64-v8a`。

## KMP UI 迁移可行性评估（2026-10-27）

上游把 Android 迁往 composeApp 的 KMP UI 后，本功能需要在 `TaskRow.kt` 复刻。
评估结论：**技术可行，且老 UI 实现中的主要难点在 Compose 下会自然消失**；成本集中在
跟随上游迁移节奏的维护，而非代码本身。

现成可复用资产（均在 `kmp/`，新老 UI 共享）：

1. 保存链路：`TaskSaver.save(preserveHierarchy = true)` 不变。
2. 点击位置→光标：详情页子任务行内编辑的 `SubtaskTitleField` 已解决 caret 定位
   （`cursorRectAt` + 偏移映射），即 [`subtask-title-cursor-at-tap-design.md`](subtask-title-cursor-at-tap-design.md) 那套。
3. 输入组件：`MarkdownEditField` / `SubtaskTitleField` 都在 `commonMain`，跨平台可用。

难点映射：

| 老 UI 的难点 | KMP UI 下的情况 |
|---|---|
| RecyclerView 回收导致草稿串行 → payload 局部刷新 + 短期展示覆盖 | 状态提升到 ViewModel + 重组，问题不存在 |
| 两行焦点切换保 IME 连接、临时 InputFilter 冻结 | `FocusRequester` + 状态切换，天然支持 |
| `EditText` 与 `TextView` 同尺寸重叠防跳变 | 同一 slot 内 `BasicTextField` 替换 Markdown 展示 |
| 拖动/多选与编辑态仲裁 | KMP UI 目前无拖动/多选，待上游实现后再补仲裁规则 |

注意点：

- composeApp 的 `MainActivity` 为 `windowSoftInputMode="adjustNothing"`，键盘遮挡需
  `WindowInsets.ime` + `imePadding()` + 编辑行 `scrollToItem`。
- `TaskRow` / `App.kt` 是上游迁移期改动最频繁的文件，重交互定制每次同步上游都要 rebase；
  且上游当前行模型是“点击进详情”，是否自带行内编辑未知。**建议等上游 UI 迁移收尾后再平移**。
