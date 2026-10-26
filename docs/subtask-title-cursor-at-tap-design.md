# 子任务标题编辑光标定位到点击位置设计

日期：2026-10-26

## 背景与目标

任务详情页的已有子任务标题已支持原地编辑（见
[`inline-subtask-title-editing-design.md`](inline-subtask-title-editing-design.md)），但进入编辑时
光标固定落在标题末尾。主任务列表的行为是光标落在手指点击的字符位置。

本功能将主列表的光标定位行为延伸到详情页子任务标题编辑：

- 点击子任务标题进入编辑时，光标落在点击的字符位置，而不是标题末尾。
- 多行、换行标题按视觉位置换算，点击第二行中间即定位到第二行对应字符。
- 通过无障碍（读屏、键盘）进入编辑时没有点击坐标，退化为句尾光标，与主列表 fallback 一致。
- 不改变保存、失败恢复、返回键、复选框、chip 等既有语义。

相关文档：[`inline-task-title-editing-design.md`](inline-task-title-editing-design.md)（主列表行为）。

## 现状差异

| | 主任务列表 | 详情页子任务行 |
|---|---|---|
| 点击位置获取 | `TaskViewHolder` 记录触摸坐标，`sourceOffsetAtTap()` 用 `TextView.getOffsetForPosition` 换算字符偏移 | `Modifier.clickable` 不携带坐标信息 |
| 光标初始化 | `setSelection(点击偏移)`，经 `cursorRequestId` 消费一次 | `TextRange(draftTitle.length)` 固定末尾 |
| 状态通道 | `beginInlineTitleEdit(..., cursorOffset, cursorX, cursorY)` | 只传 `taskId, title` |

关键点：`TaskListViewModel.InlineTitleEditState` 已有 `cursorOffset / cursorX / cursorY /
cursorRequestId` 定位机制和 `clearInlineTitleCursorRequest()` 消费入口，本次不需要新增
ViewModel 状态，只需把子任务行接入。

主列表还存在 `displayedTitleSource` 与渲染文本不一致时的 source 映射；子任务行直接渲染
`task.title`，偏移与源文本 1:1 对应，不需要该映射。

## 技术设计

### 1. 只读 Text：点击时换算字符偏移（`SubtaskRow.kt`）

- 用 `onTextLayout` 捕获 `TextLayoutResult`。
- 保留标题现有 `clickable`（无障碍 `onClick` 语义、按压水波纹、整块点击区域不变），
  在其外侧加一个 `pointerInput` 手势观察器，用 `awaitPointerEvent(PointerEventPass.Initial)`
  记录手指按下/抬起坐标——与主列表 `TaskViewHolder.recordTitleTouch` 的做法一致：
  Initial 通道在同一事件被 `clickable` 处理之前派发，位置记录无时序竞争。
- 点击回调触发时用 `textLayoutResult.getOffsetForPosition(点击坐标 - 标题顶部内边距)`
  换算字符偏移（天然支持换行行），回调 `onTitleClick(cursorOffset)`。
- 坐标空间：观察器位于 `.padding(top = SUBTASK_TITLE_TOP_PADDING)` 之前，坐标以整块点击区
  为原点；`TextLayoutResult` 以文本绘制区为原点，因此换算时 Y 减去顶部内边距像素值。
  两者由 modifier 顺序约定关联，不依赖内边距的具体数值。
- 无障碍点击（读屏、键盘）无触摸坐标，`cursorOffset` 为 null，退化为句尾光标，
  与主列表 fallback 一致。
- 偏移按 `coerceIn(0, title.length)` 钳制；空标题返回 0。

### 2. 编辑态 BasicTextField：按请求定位光标（`SubtaskRow.kt`）

- 初始 `TextFieldValue` 的 `selection` 改为：

  ```kotlin
  TextRange(
      (editState.cursorOffset ?: editState.draftTitle.length)
          .coerceIn(0, editState.draftTitle.length)
  )
  ```

- `remember(editState.taskId)` 只在编辑会话开始时初始化一次，符合"进入时定位、之后用户
  自由移动"的语义。
- 应用定位后，编辑行通过 `onCursorRequestConsumed(taskId, cursorRequestId)` 回调通知
  `SubtaskControlSet`，由它调用已有的 `listViewModel.clearInlineTitleCursorRequest()`
  消费请求，避免保存中视图重建（如旋转）时把光标重新拉回点击处。回调只在输入框实际
  组合并应用定位后触发，避免行未渲染时提前消费。

### 3. 回调链路传递偏移

- `ExistingSubtaskRow.onTitleClick: () -> Unit` → `(cursorOffset: Int?) -> Unit`。
- `SubtaskRow` 的 `editSubtask: (Task) -> Unit` → `(Task, Int?) -> Unit`。
- `SubtaskControlSet.beginInlineTitleEdit(task, cursorOffset)` →
  `SubtaskTitleAction.BeginEdit(task, cursorOffset)`（跨视图重建仍携带偏移）→
  `listViewModel.beginInlineTitleEdit(task.id, task.title, cursorOffset = cursorOffset)`。
  `TaskListViewModel` 参数已存在，无需修改。

### 4. 标题顶部内边距 12.dp 的来源

`SubtaskRow.kt` 中三处 `.padding(top = 12.dp)`（新增子任务行、只读标题、编辑输入框）是
**硬编码字面量**，不来自资源或方法。仓库内的命名尺寸是 `Constants.KEYLINE_FIRST = 16.dp`、
`Constants.HALF_KEYLINE = 8.dp`、`dimens.xml` 的 `widget_padding = 12dp`（widget 用途，与
此处无关），均不对应此处的标题-复选框垂直对齐值。

处理：数值保持 12.dp 不变（避免视觉回归），提取为 `SubtaskRow.kt` 私有常量
`SUBTASK_TITLE_TOP_PADDING`，三处统一引用，防止今后改动时坐标空间与视觉错位。
光标换算逻辑不依赖该数值（见第 1 节 modifier 顺序约定）。

## 不改的部分

- 主任务列表的 `TaskViewHolder` 定位逻辑（含 source 映射）。
- `cursorX / cursorY` 坐标兜底通道不接：Compose 侧用 `TextLayoutResult` 直接换算更准确。
- 保存、失败 Snackbar、返回键、失焦提交、切换编辑项、复选框、chip、新建子任务语义均不动。
- 一级任务右侧尖括号进入详情的导航不动。

## 验证重点

- 点击标题行首、中间、行尾，光标落在对应字符位置。
- 多行长标题点击第二行中间，定位到第二行对应字符。
- 无障碍/键盘进入编辑时句尾光标。
- 点击进入后旋转屏幕：光标不跳回点击处，草稿不丢。
- 切换编辑另一个子任务：光标落在新点击位置。
- `:app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a`、`:app:testGenericDebugUnitTest`。
- 真机验证多行标题、深色主题显示正常。

## 范围

本次只覆盖 Android 任务详情页已有子任务标题的进入编辑光标定位，不含 desktop、iOS、Wear，
也不含主列表行为变更。
