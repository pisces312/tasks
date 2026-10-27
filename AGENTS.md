# AGENTS.md

本文件是 AI 代理在本仓库工作时的入口（原 `CLAUDE.md` 已并入）。
**这里只放入口和硬规则；具体做法见下方索引，不要只读这里就动手。**

## 仓库

`pisces312/tasks` —— Tasks.org 的 fork（上游 `tasks/tasks`）。fork 定制：Git 同步（JGit +
mwiede jsch）、设置导出/导入、debug 红图标与 `.debug` 包名、ABI splits、环境变量签名。
CI 由 `.github/workflows/fork-check.yml` 在 push 到 main 时触发，只调用上游 `check.yml`
（lint / jvmTest / iosTest / instrumentedTest×2）；上游 "Assemble bundle" 发布链已在本仓库停用
（缺签名 secrets），正式版仍走本机 `./build.sh`。

## 常用命令

```bash
./build.sh                                          # 正式版：release generic arm64-v8a（默认）
./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a   # 编译校验（快）
./gradlew :app:testGenericDebugUnitTest             # 单元测试
./gradlew lint                                      # Lint（app/lint.xml）
```

`build.sh` 在 Windows 上必须用 Git Bash（`D:\dev\git\bin\bash.exe`），不能用 WSL 的 bash。
完整命令、签名、JDK、ABI、模拟器跑仪器测试见 [`docs/build-and-environment.md`](docs/build-and-environment.md)。

## 硬规则

1. **只 commit，不 push**；push 需用户显式确认。
2. 破坏性操作（删除分支、`reset --hard`、批量改文件）先说明再执行。
3. 正式版只构建 `generic` + `arm64-v8a`（`./build.sh` 默认），不要构建其它 flavor / ABI。
4. 任何 `git merge upstream/main` 之前先读
   [`docs/upstream-merge-conventions.md`](docs/upstream-merge-conventions.md)；
   合完必须通过其第 6 节验证（不能只跑 `compile*`），并按第 8 节更新
   `docs/upstream-sync-YYYY-MM-DD.md` 与 `.workbuddy/memory/`。
5. 禁止把密钥、口令、keystore 内容写入仓库。
6. debug 包名的三处契约（`opentasks_authority`、kmp 的 `APPLICATION_ID`、红图标与权限移除）
   丢一处就会让 debug 包访问正式版的 provider 或让 Content Provider API 静默失效，
   见 [`docs/architecture.md`](docs/architecture.md) 的"debug 变体的包名契约"一节。

## 索引

| 主题 | 文档 |
|---|---|
| 模块、flavor、debug 包名契约、技术栈、**任务列表 UI 的接线（排查 UI 问题前必读）** | [`docs/architecture.md`](docs/architecture.md) |
| 构建命令、签名、JDK、ABI、本地跑仪器测试 | [`docs/build-and-environment.md`](docs/build-and-environment.md) |
| 模拟器上手动验证 UI（装包、驱动、录屏、帧级位移测量） | [`.qoder/skills/tasks-emulator-verify/SKILL.md`](.qoder/skills/tasks-emulator-verify/SKILL.md) |
| 多语言约定（只维护 en + zh-rCN） | [`docs/i18n.md`](docs/i18n.md) |
| **合并上游（必读）** | [`docs/upstream-merge-conventions.md`](docs/upstream-merge-conventions.md) |
| 最近一次合并与 CI 复查记录 | [`docs/upstream-sync-2026-10-19.md`](docs/upstream-sync-2026-10-19.md) |
| Git 同步设计 | [`docs/git-sync-design.md`](docs/git-sync-design.md) |
| 设置导入导出设计 | [`docs/settings-export-import-design.md`](docs/settings-export-import-design.md) |
| 标题原地编辑 / 子任务标题原地编辑 | [`docs/inline-task-title-editing-design.md`](docs/inline-task-title-editing-design.md) |
| 列表页快捷添加子任务入口（已设计未实现） | [`docs/subtask-quick-add-design.md`](docs/subtask-quick-add-design.md) |
