# 任务详情页子任务标题原地编辑设计

日期：2026-10-26

## 背景与目标

主任务列表已经支持“点击标题原地编辑、右侧尖括号进入完整详情”。任务详情页中的已有子任务仍由
`ExistingSubtaskRow` 展示，点击整行会立即进入子任务详情，仅新建子任务支持直接输入标题。

本功能将主任务列表的交互延伸到任务详情页的已有子任务：

- 点击子任务标题后直接在当前行编辑，不进入子任务详情。
- 每个已有子任务保留固定、明确的右侧详情入口。
- 复用现有标题保存、同步和失败恢复逻辑。
- 不改变一级任务右侧尖括号进入任务详情的导航，也不新增子任务下钻列表。
- 不改变复选框、子任务展开/收起和新建子任务的既有语义。

相关文档：[`inline-task-title-editing-design.md`](inline-task-title-editing-design.md)。

## 当前实现

- `app/src/main/java/org/tasks/compose/edit/SubtaskRow.kt`
  - `ExistingSubtaskRow` 使用只读 `Text`，整行点击调用 `openSubtask()`。
  - `NewSubtaskRow` 使用 `BasicTextField`，但草稿属于父任务详情的 `newSubtasks`，不能用于已有任务。
- `app/src/main/java/org/tasks/ui/SubtaskControlSet.kt`
  - 已通过 `SubtaskFilter` 加载已有子任务。
  - 已创建 Android `TaskListViewModel`，可复用其原地标题编辑状态和保存入口。
- `app/src/main/java/org/tasks/ui/TaskListViewModel.kt`
  - 已有 `InlineTitleEditState`、草稿更新、提交结果和失败事件。
  - 保存时按任务 ID 重新读取最新任务，只替换标题，并调用
    `TaskSaver.save(updated, current, preserveHierarchy = true)`。

## 交互规则

| 区域/状态 | 行为 |
|---|---|
| 已有子任务标题 | 进入原地编辑，自动聚焦并显示键盘，首版光标位于句尾 |
| 右侧尖括号（非编辑状态） | 打开该子任务的完整详情 |
| 右侧勾选（编辑状态） | 保存标题并退出编辑，保存期间禁用 |
| 复选框 | 先提交正在编辑的标题，再完成或取消完成 |
| 子任务数量 chip | 先提交标题，再展开或收起 |
| 点击另一已有子任务标题 | 保存当前项后开始编辑新项，尽量保持键盘显示 |
| 键盘 Done、输入框失焦、编辑行空白 | 保存并退出编辑 |
| 返回键 | 优先保存并退出子任务标题编辑，再次返回才执行详情页原有返回逻辑 |
| 只读子任务 | 标题不进入编辑，右侧详情入口仍可用 |
| 新建子任务 | 保持当前直接输入及随父任务详情保存的行为 |

同时只允许编辑一个已有子任务。已有子任务标题提交后立即落库并参与同步，不依赖一级任务详情的
保存按钮；之后即使取消一级任务详情修改，已提交的子任务标题也不会回滚。

## 技术设计

### Compose 行布局

在 `SubtaskRow.kt` 中：

- 移除 `ExistingSubtaskRow` 的整行详情点击。
- 普通状态使用 `Text` 展示标题，标题点击进入编辑。
- 编辑状态使用 `BasicTextField`，保留多行、完成删除线、禁用透明度等展示规则。
- 行尾增加固定 `IconButton`：普通状态显示右尖括号，编辑状态显示勾选。
- 子任务 chip 继续显示；按钮使用现有 `open_task_details` 和 `save_task_title` 无障碍文案。
- 编辑中的输入框在保存期间只读，避免通过 disabled 样式引入颜色跳变。

### 状态与保存协调

在 `SubtaskControlSet.kt` 中：

- 收集并传递 `listViewModel.inlineTitleEditState`。
- 标题输入通过带任务 ID 的 `updateInlineTitleDraft()` 写入 ViewModel，避免重组或配置变化丢失草稿，也避免切换编辑项时把旧输入写到新任务。
- 开始编辑另一项、打开详情、完成、折叠和添加子任务前，先调用
  `commitInlineTitleEdit()`。
- 异步保存期间的后续动作采用 latest-wins；只有保存成功且界面仍处于可用状态时才执行。
- 打开详情时优先使用提交结果中的最新 `Task`，避免把旧标题快照传给下一个详情页。
- 保存失败保留草稿和编辑状态，复用 `inline_title_save_failed` 提示，并允许明确取消、丢弃草稿。
- 页面暂停时提交当前标题；返回键优先提交编辑，不直接离开详情页。
- 保存中不重新聚焦输入框，避免配置变化或视图重建时重新弹出只读键盘。

待执行动作使用 `SubtaskTitleAction`（只携带任务数据，不捕获 Fragment 或 Compose 回调），
保存在父任务编辑的 `TaskEditViewModel` 中；标题提交结果保存在 `TaskListViewModel` 中并由界面消费
后清除。这样在异步保存尚未完成时旋转屏幕、切换视图，仍能执行用户请求的打开详情、完成、折叠等动作，
也不会把旧 Fragment 泄漏到 ViewModel。

无需新增 DAO 或独立保存服务，也不把已有子任务草稿混入 `TaskEditViewModel.newSubtasks`。

## 验证重点

- 点击已有子任务标题不再打开详情；右侧尖括号始终可以打开详情。
- 勾选、Done、失焦、返回和切换编辑项均能正确保存。
- 保存成功后的主列表、当前子任务列表和重新打开的详情标题一致。
- 保存失败时不跳转、不丢草稿；取消后可以明确丢弃。
- 完成、折叠、多级子任务、只读任务及新建子任务行为不回归。
- 长标题、多行标题、深色主题和配置变化显示正常。
- 执行 `:app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a` 编译校验及相关测试。

## 范围

本次只包含 Android 任务详情页中已有子任务的标题原地编辑，不包含：

- 描述、日期、标签等字段的行内编辑。
- 修改一级任务或子任务详情的导航层级。
- 将详情页子任务列表迁移到 RecyclerView。
- desktop、iOS 或 Wear 的子任务编辑交互。
