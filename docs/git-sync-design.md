# Tasks.org Git Sync 功能设计文档

> 创建时间：2026-05-01
> 状态：编码完成（待构建验证）

## 一、功能概述

为 Tasks.org 添加「同步到 Git 仓库」功能，手动触发时将任务数据（JSON）提交并推送到指定的 Git 远程仓库。

- 认证方式：**仅 SSH Key**（从文件管理器选择 key 文件，复制到应用内部缓存）
- 导出格式：**Git-diff 友好的 JSON**（pretty-print + 确定性排序 + 剔除噪音字段）
- 触发方式：手动点击「立即同步」按钮

## 二、核心流程

```
用户点击「立即同步」
  → GitJsonExporter 导出 JSON 到 git repo 工作目录
  → JGit: git add .
  → JGit: git commit（自动生成 commit message，含时间戳）
  → JGit: git push（SSH 认证）
  → 更新上次同步时间
```

## 三、文件存储路径

| 路径 | 用途 |
|---|---|
| `context.filesDir/git-sync/` | Git 仓库根目录（clone 目标） |
| `context.filesDir/git-sync/tasks.json` | 导出的任务数据文件（固定文件名） |
| `context.filesDir/git-sync/.git/` | Git 元数据 |
| `context.filesDir/ssh/` | SSH 私钥存储目录 |

所有文件均在应用内部存储，无需外部存储权限，卸载时随应用一起清除。

## 四、Git 配置项（Preferences）

| 偏好项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `p_git_sync_enabled` | Boolean | false | 启用/禁用 Git 同步 |
| `p_git_repo_url` | String | "" | SSH 远程地址，如 `git@github.com:user/tasks-backup.git` |
| `p_git_branch` | String | "main" | 分支名 |
| `p_git_author_name` | String | "" | Commit 作者名 |
| `p_git_author_email` | String | "" | Commit 邮箱 |
| `p_git_last_sync` | Long | 0 | 上次成功同步时间戳 |

## 五、SSH Key 管理

### 5.1 SshKeyManager

```
org.tasks.backup.SshKeyManager
├── importKey(uri: Uri)     — 从 SAF 文件选择器导入私钥到内部目录
├── getKeyPath(): File      — 返回内部 key 文件路径 (filesDir/ssh/private_key)
├── hasKey(): Boolean       — 是否已导入 key
├── deleteKey()             — 删除已导入的 key
└── validateKey(): Boolean  — 用 JSch KeyPair.load 验证 key 格式
```

### 5.2 导入流程

1. 用户点击「选择 SSH Key」→ SAF 文件选择器
2. 用户选中私钥文件（id_rsa / id_ed25519 等）
3. ContentResolver 读取文件内容
4. 复制到 `context.filesDir/ssh/private_key`
5. JSch KeyPair.load 验证格式
6. 成功：显示 key 文件名 + 删除按钮；失败：提示无效 key

### 5.3 安全注意

- Key 存在 `filesDir/ssh/`，其他 app 无法访问
- 不存储 passphrase（与 M2Git 一致，要求用户使用无密码 key 或每次输入）
- 卸载时 key 随内部存储清除

## 六、Git 同步核心类

### 6.1 TasksSshSessionFactory

参照 M2Git 的 `SGitSessionFactory`：

```kotlin
class TasksSshSessionFactory(
    private val sshKeyPath: File
) : JschConfigSessionFactory() {

    override fun configure(host: Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        val userInfo = CredentialsProviderUserInfo(session, JschCredentialsProvider())
        session.setUserInfo(userInfo)
    }

    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        jsch.addIdentity(sshKeyPath.absolutePath)
        return jsch
    }
}
```

### 6.2 GitTransportCallback

参照 M2Git 的 `SgitTransportCallback`：

```kotlin
class GitTransportCallback(
    private val sshKeyPath: File
) : TransportConfigCallback {

    private val sshSessionFactory = TasksSshSessionFactory(sshKeyPath)

    override fun configure(transport: Transport) {
        if (transport is SshTransport) {
            transport.sshSessionFactory = sshSessionFactory
        }
    }
}
```

### 6.3 GitSyncManager

```kotlin
@Singleton
class GitSyncManager @Inject constructor(
    private val gitJsonExporter: GitJsonExporter,
    private val sshKeyManager: SshKeyManager,
    private val preferences: Preferences,
    @ApplicationContext private val context: Context,
) {
    // Git 仓库本地路径
    private val repoDir get() = File(context.filesDir, "git-sync")

    suspend fun initOrCloneRepo()       — 首次 clone 或打开已有 repo
    suspend fun syncToGit()            — 完整同步流程
    suspend fun hasUncommittedChanges() — 检查是否有未提交更改
    suspend fun pullFirst()            — 先 pull 再 push（处理远程变更）
    private fun getGit(): Git          — 打开本地 repo 或抛异常
    private fun getTransportCallback() — 构建 SSH TransportCallback
}
```

### 6.4 syncToGit() 详细流程

```kotlin
suspend fun syncToGit() {
    // 1. 前置检查
    require(sshKeyManager.hasKey()) { "SSH key not configured" }
    require(getRepoUrl().isNotBlank()) { "Git repo URL not configured" }

    // 2. 初始化或克隆仓库
    initOrCloneRepo()

    // 3. 切换到目标分支
    checkoutBranch()

    // 4. 先 pull 远程变更（避免冲突）
    pullFirst()

    // 5. 导出 JSON 到 repo 工作目录
    gitJsonExporter.exportTo(repoDir)

    // 6. git add .
    val git = getGit()
    git.add().addFilepattern(".").setRenormalize(false).call()
    git.add().setUpdate(true).addFilepattern(".").setRenormalize(false).call()

    // 7. 检查是否有变更
    val status = git.status().call()
    if (!status.hasUncommittedChanges()) {
        // 无变更，跳过 commit/push
        return
    }

    // 8. git commit
    git.commit()
        .setCommitter(getAuthorName(), getAuthorEmail())
        .setMessage("sync: ${DateTimeFormatter.format(now)}")
        .call()

    // 9. git push
    git.push()
        .setRemote("origin")
        .setRefSpecs(RefSpec(getBranchName()))
        .setTransportConfigCallback(getTransportCallback())
        .call()

    // 10. 更新同步时间
    preferences.setLong(R.string.p_git_last_sync, currentTimeMillis())
}
```

## 七、GitJsonExporter — Git-diff 友好的 JSON 导出

与现有 `TasksJsonExporter` 的关键区别：

| | TasksJsonExporter | GitJsonExporter |
|---|---|---|
| 格式 | 压缩单行 | Pretty-print（缩进 2 空格） |
| Key 顺序 | 随机（HashMap） | 按字母排序（TreeMap / sorted） |
| 时间戳 | 包含 `timestamp` | 剔除（每次导出都不同，产生噪音 diff） |
| 任务排序 | 按 ID | 按 UUID 字母排序 |
| 文件名 | 含时间戳 `backup_20260501.json` | 固定 `tasks.json` |
| 输出位置 | 备份目录 | git repo 工作目录 |
| 噪音字段 | 全部导出 | 跳过 `lastSync`、`timestamp` 等 |

### 7.1 确定性排序实现

```kotlin
// 使用 kotlinx.serialization 的 Json 配置
private val gitJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    // 确定性输出
}

// 任务按 UUID 排序
val sortedTasks = taskIds.sortedBy { id ->
    taskDao.fetch(id)?.uuid ?: ""
}
```

### 7.2 数据分文件（可选，暂不实现）

如果后续数据量大，可拆分：
```
tasks.json        ← 任务数据
metadata.json     ← tags/filters/accounts 等元数据
preferences.json  ← 偏好设置
```

现阶段一个 `tasks.json` 足够。

## 八、依赖

在 `app/build.gradle.kts` 中添加：

```kotlin
// JGit 核心
implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
// JGit SSH (JSch 后端)
implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.10.1.202505221210-r")
// JSch 替代版（安全修复）
implementation("com.github.mwiede:jsch:0.2.11")
```

### 依赖冲突处理

M2Git 使用 `configurations.all` 替换原版 jsch：
```groovy
details.useTarget("com.github.mwiede:jsch:0.2.11")
```
Tasks 项目同样需要此替换。

### ProGuard 规则

需在 `app/proguard.pro` 添加 JGit keep 规则（待确认具体内容）。

## 九、UI 设计

### 9.1 入口

在现有 Backups 设置页（`Backups.kt` / `BackupsScreen`）中新增「Git 同步」区域，
或新建独立 Fragment `GitSync.kt`，从设置主页导航进入。

### 9.2 界面元素

- Switch：启用/禁用 Git 同步
- TextField：SSH 仓库地址（`git@...`）
- TextField：分支名（默认 main）
- TextField：作者名
- TextField：作者邮箱
- Button：「选择 SSH Key」→ SAF 文件选择器
- Text：已导入 key 的文件名 + 删除按钮
- Button：「立即同步」
- Text：上次同步时间
- Text：同步状态（进行中 / 成功 / 失败）

### 9.3 查看 JSON 内容入口

在 Git 同步设置区域添加「查看 JSON」按钮，点击后：

1. 读取 `context.filesDir/git-sync/tasks.json` 文件内容
2. 在新页面/对话框中以只读方式展示 JSON 文本
3. 如果文件不存在（尚未同步过），显示「尚未导出」提示

**实现方式**：新增 `JsonViewerActivity` 或 Compose Dialog，读取文件内容并以
语法高亮或纯文本方式展示。文件可能较大，使用 `ScrollView` 或 LazyColumn。

**UI 元素**：
- Button：「查看 JSON」— 位于「立即同步」按钮附近
- 页面/Dialog：只读文本展示，支持滚动
- 可选：复制到剪贴板按钮

## 十、错误处理

| 场景 | 处理 |
|---|---|
| SSH key 未配置 | Toast 提示先导入 key |
| 仓库地址为空 | Toast 提示先配置地址 |
| 网络失败 | Toast 提示网络错误，可重试 |
| SSH 认证失败 | Toast 提示检查 key 是否匹配 |
| Push 拒绝（非 fast-forward） | 自动 pull → rebase → 重试 push |
| 本地 repo 损坏 | 删除本地 repo 目录，重新 clone |
| Clone 失败 | Toast 提示，保留错误详情 |

## 十一、新增/修改文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `app/build.gradle.kts` | 修改 | 添加 JGit + JSch 依赖 |
| `app/proguard.pro` | 修改 | JGit keep 规则 |
| `org/tasks/backup/SshKeyManager.kt` | 新建 | SSH Key 导入/管理 |
| `org/tasks/backup/GitJsonExporter.kt` | 新建 | Git-diff 友好的 JSON 导出 |
| `org/tasks/backup/GitSyncManager.kt` | 新建 | Git 同步核心逻辑 |
| `org/tasks/backup/TasksSshSessionFactory.kt` | 新建 | JGit SSH Session Factory |
| `org/tasks/backup/GitTransportCallback.kt` | 新建 | SSH Transport 回调 |
| `org/tasks/backup/GitSyncWorker.kt` | 新建（可选） | WorkManager 后台同步 |
| `org/tasks/preferences/fragments/GitSync.kt` | 新建 | 设置页 UI |
| `org/tasks/preferences/fragments/GitSyncViewModel.kt` | 新建 | UI 状态管理 |
| `org/tasks/backup/JsonViewerActivity.kt` | 新建 | JSON 内容查看页面 |
| strings / 界面 资源 | 修改 | 新增文案 |

## 十二、参考：M2Git 关键文件映射

| M2Git 文件 | Tasks 对应 | 说明 |
|---|---|---|
| `CommitAction.java` | GitSyncManager | commit 触发逻辑 |
| `CommitChangesTask.java` | GitSyncManager.syncToGit() | add + commit 实现 |
| `PushAction.java` / `PushTask.java` | GitSyncManager.syncToGit() | push 实现 |
| `SGitSessionFactory.java` | TasksSshSessionFactory | SSH session 配置 |
| `SgitTransportCallback.java` | GitTransportCallback | Transport 回调 |
| `PrivateKeyUtils.java` | SshKeyManager | key 文件管理 |
| `Repo.java` | GitSyncManager | repo 模型（简化版） |

## 十三、后续迭代（暂不实现）

- [ ] 自动定时同步（WorkManager）
- [ ] SSH passphrase 输入弹窗
- [ ] 数据拆分多文件导出
- [ ] 同步历史记录
- [ ] 多仓库支持
