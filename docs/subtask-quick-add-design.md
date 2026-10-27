# 列表页快捷添加子任务入口设计

日期：2026-10-27（方案已确认，**尚未实现**）

## 背景

- 有子任务的任务在 chip 行（标题下方第二行，`chip_group`）显示展开按钮
  `SubtaskChip`（`TaskViewHolder.kt`，仅 `hasChildren()` 时渲染）。
- 添加子任务目前必须进入任务详情页。目标：不进详情、保持列表简洁、避免误操作。
- 生效实现是老 UI（RecyclerView + `TaskViewHolder`），接线见
  [`architecture.md`](architecture.md)「任务列表 UI 的接线」——以下所有否决理由
  均基于该实现，**不是** composeApp 的 `TaskRow.kt`（那套不在手机 APK 里）。

## 已否方案（记录决策依据，勿翻案）

| 方案 | 否决理由 |
|---|---|
| 行尾"+"（详情箭头左侧） | 行尾已有 48dp 详情箭头（标题原地编辑定制），两个 48dp 热区挤在行尾，误操作风险高 |
| 长按弹菜单 | 长按已被多选 ActionMode / 拖动排序占用（`TaskListFragment.onLongPress`），无语义空间 |
| 滑动露出按钮 / 全滑触发 | 与子任务缩进的横向拖动冲突；与抽屉左缘横拖手势仲裁复杂；TalkBack 不可达；实现量最大 |

## 采用方案：chip 行常驻"+"

- **位置**：`chip_group` 第二行首位，与展开按钮同区；样式与 `SubtaskChip` 同款
  （26dp 小 chip），获得第一个子任务后"+"自然与展开按钮并排，位置语义连续。
- **显示条件**：顶层任务（`indent == 0`）且列表可写；子任务行（`indent > 0`）、
  已完成、只读、`doomed` 状态不显示。
- **已知代价**：无子任务的顶层任务行从 48dp 涨至约 78-80dp（chip 行没有硬预留，
  行高由 48dp 元素锚定，chip 渲染会撑高），一屏可见任务数下降约 1/3（单行任务）。
  接受该代价以换取一步可达 + 零手势冲突。
- **点击行为（实现时二选一）**：
  1. 轻量输入弹层（推荐）：单输入框，保存走 `TaskSaver` 并设置 parent；
     不动 TaskEdit 链路。
  2. 跳全屏新建子任务页：注意 `TaskEditDestination.isSubtaskDraft` 绑定在父任务
     编辑 VM 的 draft 树（`TaskEditLoader`），**不能复用**；需给
     `TaskEditDestination` / `TaskEditLoader` 加 `parentUuid` 通路。
- i18n：新增字符串只加 en + zh-rCN（见 [`i18n.md`](i18n.md)）。

## 实现要点（待实现时展开）

1. chips 渲染处（`TaskViewHolder.kt` 的 `chip_group` ComposeView 内容）加"+"chip；
   回调链 `TaskViewHolder.ViewHolderCallbacks` → `TaskListFragment`。
2. 保存复用 `TaskSaver.save()`（勿直接 DAO 更新，会绕过同步 dirty/刷新广播）。
3. 编辑中标题（`InlineTitleEditState`）与"+"点击的先后：先提交行内编辑再弹输入，
   与日期点击（`onChangeDueDate` 的 `commitInlineTitleEdit` 包装）同模式。

## 上游迁移提示

上游 Android 切到 KMP UI（`composeApp/.../TaskRow.kt`）后，本方案在 chip 行
（`hasChips` 分支）复刻即可，属轻交互定制，复刻成本低。
