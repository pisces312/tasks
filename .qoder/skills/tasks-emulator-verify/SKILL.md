---
name: tasks-emulator-verify
description: 在本地 pixel6 模拟器上构建、安装并驱动 Tasks.org fork 的 debug 包，用录屏 + 帧级 y 坐标测量证明或证伪一个 UI 行为（重点是导航抽屉长按拖拽排序的位移动画），并做冷启动持久化复核。当用户要求"在模拟器上验证一个 UI 修复"、需要给出可量化的拖拽/动画证据、或需要确认某个 UI 状态在 force-stop 后仍然保留时使用。
agent_created: true
---

# Tasks.org 模拟器验证

目标：把"看起来好了"变成**可复核的数字**。一次拖拽用 `screenrecord` 录下来，拆成帧，
对某一行的文字做模板匹配，逐帧读出它的屏幕 y 坐标；被拖行在拖动过程中是否下沉，
就变成一串可以直接看到 `+123` 这种跳变的数字。

两个脚本，都在本技能目录下（命令里的 `<skill>` 指 `.qoder/skills/tasks-emulator-verify`）：

| 脚本 | 作用 |
|---|---|
| `scripts/emu.py` | adb 包装：状态、安装、启动、截图、UI dump、按文字点击、拖拽、**录屏拖拽** |
| `scripts/frames.py` | 录屏后处理：拆帧、拼接触表（带帧号）、模板匹配出 y 序列（表 / CSV / 折线图） |

```bash
python <skill>/scripts/emu.py --help
python <skill>/scripts/frames.py track --help
```

脚本只依赖 Python 3 + `ffmpeg`/`ffprobe` + `opencv-python`/`numpy`/`pillow`/`matplotlib`
（本机已验证：ffmpeg 9.0.2，cv2 5.0.0，PIL 12.2.0，matplotlib 3.11.1）。

## 零、这些脚本只是 adb 的包装

模拟器上本来就可以直接用 `adb shell input`。`emu.py` 没有走任何私有通道，只是固定了串口、
绕开了 Git Bash 的路径改写，并把"录屏 + 拖拽"合成一次调用。等价命令：

| 目的 | 裸命令 |
|---|---|
| 点击 | `adb -s emulator-5554 shell input tap 514 1430` |
| 长按 | `adb ... shell input swipe 514 1430 514 1430 2000`（原地不动的 swipe） |
| 长按拖拽 | `adb ... shell input draganddrop 514 1430 514 1240 2500` |
| 滑动（开抽屉） | `adb ... shell input swipe 5 1200 700 1200 300` |
| 输入文字 | `adb ... shell input text Alpha` |
| 返回 / 主页 | `adb ... shell input keyevent 4` / `3` |
| 读 UI 文字 | `MSYS_NO_PATHCONV=1 adb ... shell uiautomator dump /sdcard/x.xml`，再 `adb ... exec-out cat /sdcard/x.xml` |
| 截图 | `adb ... exec-out screencap -p > D:/Temp/a.png` |
| 录屏 | `adb ... shell screenrecord --time-limit 10 /sdcard/a.mp4`，结束 `pkill -INT screenrecord`，再 `adb ... pull /sdcard/a.mp4 D:/Temp/a.mp4` |

手敲时要注意的几点：

- `input` 的参数里没有文件路径，**不**受 Git Bash 改写影响；受影响的是 `uiautomator dump`、
  `screenrecord`、`pull` 这些带 `/sdcard/...` 的命令，必须加 `MSYS_NO_PATHCONV=1`，
  且 pull 的本地目标要写成 `D:/Temp/...`。
- `input motionevent DOWN/MOVE/UP` 分多次调用**不构成一个手势**（每次独立进程，`downTime` 不同），
  Compose 的长按拖拽只能用 `input draganddrop`（或 `sendevent`，需要 root）。
- `input text` 走按键注入，非 ASCII（中文）打不进去；测试清单名用英文就绕开了输入法。
- `screenrecord` 和 `input` 分两次 shell 调用会因后台调度错位，拖拽可能落在 `time-limit` 之后；
  连成一条命令（或直接用 `record-drag`）才稳。

## 一、环境事实（已验证，不用重新发现）

- AVD **`pixel6`**，串口 `emulator-5554`，API 34 / Android 14，1080x2400 @ 420dpi，
  `abilist=x86_64,arm64-v8a`（x86_64 镜像自带 ARM 转译）。**arm64-v8a 的 debug APK 直接装，
  不要为了模拟器另建 x86_64/universal 变体。**
- 包名 **`org.tasks.debug`**。正式包 `org.tasks` 与它共存但数据目录独立；模拟器上没启动过正式包时
  它没有 data 目录，别拿它做验证。
- adb：`D:/dev/android_sdk/platform-tools/adb.exe`。`emu.py` 依次找 `$ADB`、
  `$ANDROID_HOME/platform-tools/adb.exe`、上面这个固定路径、最后 `PATH`。所有命令都带
  `-s emulator-5554`（`--serial` 可改）。
- **Git Bash 会把 adb 命令里的 `/sdcard/...` 改写成 `D:/dev/git/sdcard/...`**，`adb pull` 的本地
  目标又必须是 Windows 路径。`emu.py` 内部绕开了这两个坑；手敲 adb 时用
  `MSYS_NO_PATHCONV=1 adb shell ...`，pull 目标写 `D:/Temp/...`。
- 截图 `adb exec-out screencap -p` 得到的就是 1080x2400 物理像素；`input tap/swipe` 用的是
  同一套坐标，无需换算。

## 二、标准流程

### 1. 构建 + 安装 + 启动

```bash
./build.sh debug     # 产物固定为 app/build/outputs/apk/generic/debug/app-generic-arm64-v8a-debug.apk
python <skill>/scripts/emu.py install     # 默认就是上面那个 APK；adb install -r，保留数据
python <skill>/scripts/emu.py launch
```

只想确认编译过，用 `./gradlew :app:compileGenericDebugKotlin -PbuildAbi=arm64-v8a`（快得多）；
但录屏验证必须装 APK，`compile` 不算数。

### 2. 首次启动与测试数据

首次启动有欢迎页，点 **Continue without sync**：

```bash
python <skill>/scripts/emu.py tap "Continue without sync"
```

抽屉里的"本地清单"至少要两条才有东西可拖。默认只有 `Default list`；再建一条：
点抽屉里的 `Create new list`（content-desc，中心约 `861,1178`）→ 在标题框输入名字
（`python <skill>/scripts/emu.py text Alpha`）→ 保存。

> **不要 `pm clear`**：会连本地清单、排序模式一起清掉，还得重建数据。`install -r` 足够。

### 3. 打开抽屉并切到手动排序

- 打开抽屉：点汉堡按钮（约 `94,2169`），或从左边缘右滑。
- 排序按钮是抽屉右上角的 `Sort`（content-desc，中心约 `861,191`），点开选 **Manual**。
- 拖拽只在 `expanded && 查询为空 && MANUAL` 时启用；NAME 模式下手势被禁用。状态存在
  DataStore，装新包后保留。

### 4. 录一次拖拽

先 dump 出两行的真实位置，别猜：

```bash
python <skill>/scripts/emu.py ui | grep -E "Alpha|Default list"
```

然后一次调用完成"开录 → 等待 → 拖 → 停录 → 拉回本地 → 报帧数"：

```bash
python <skill>/scripts/emu.py record-drag D:/Temp/drag.mp4 514 1430 514 1180 2500
```

参数顺序是 `OUT x1 y1 x2 y2 [duration=2500] [--lead 2.0] [--tail 1.0]`。
必须用 `input draganddrop`（不能拆成 `motionevent`，原因见第零节）：它会先按住再拖，
正好触发 `detectDragGesturesAfterLongPress`。

`--lead` 留 2s 让录屏先稳定，`--tail` 留 1s 把松手后的落位录进去。脚本用
`pkill -INT screenrecord` 提前结束（比等 `--time-limit` 到点快，且能正常收尾 mp4）。

### 5. 拆帧、看帧、量化

```bash
python <skill>/scripts/frames.py extract D:/Temp/drag.mp4 D:/Temp/drag-frames
python <skill>/scripts/frames.py sheet D:/Temp/drag-frames D:/Temp/drag-sheet.png \
    --crop 0,1060,1080,1520 --cols 6
python <skill>/scripts/frames.py track D:/Temp/drag-frames \
    --text-box 168,1411,360,1461 --invert-y \
    --csv D:/Temp/drag.csv --plot D:/Temp/drag.png --label "dragged row"
```

- `extract` **保留编码的每一帧**（`-fps_mode passthrough`）。不要按固定 fps 重采样：
  screenrecord 是可变帧率，静止时每秒只有几帧、拖动时 ~30 fps，重采样会造出假的台阶。
- `--text-box` 是**参考帧里**那一行文字的边界，直接抄 `uiautomator` 的 `bounds`（上面 dump 的
  `[168, 1273, 861, 1340]` 里取文字左半段即可）。参考帧默认第 2 帧，必须是**拖动开始前**，
  否则模板里已经带着 blur/位移。
- `track` 默认搜索窗口 = text-box 上下各 400px、左右各 150px；拖得远就显式给
  `--search x0,y0,x1,y1`。
- `--invert-y` 让"屏幕往上"在图上也是往上，读图不别扭。

### 6. 冷启动复核（顺序是否持久化）

```bash
python <skill>/scripts/emu.py stop
python <skill>/scripts/emu.py launch
sleep 8
python <skill>/scripts/emu.py tap 94,2169     # 冷启动后落在上次打开的清单页，汉堡在左下
python <skill>/scripts/emu.py ui | grep -E "Alpha|Default list"
```

`launch` 只是发了 monkey 意图，窗口画出来还要几秒；**刚启动就点会点空**（进程已起、按钮还没画）。
两行的 y 顺序应与拖完一致。

## 三、判定标准（抽屉拖拽类问题）

1. **拖动过程中**被拖行的 y 应单调（向上拖 = 非递增）；任何一帧出现 `+半行以上`（本机一行
   126px，半行 63px）的跳变就是"下沉"复现。
2. **被挤开的邻行仍要有 ~3–7px/帧 的平滑位移**——这证明 `animateItem()` 只从被拖行上摘掉了，
   不是把动画整体关掉。
3. **松手瞬间允许一次跳变**：行从"跟手位置"落回自己的槽位，这是正常落位，不是下沉。
4. 冷启动后顺序不变，才算真的写进 `order` 列。

## 四、已知坑

- **静态画面只有几帧**：VFR 下不动就不编码，录 8 秒可能只有 20 帧。别据此判断录制失败。
- **录屏被截断**：把"开录 / 拖 / 停录"拆成多次 shell 调用时，后台调度会把拖拽推到
  `time-limit` 之后，录不到交换。用 `record-drag` 一条命令做完；命令里的 `time-limit`
  由 `lead + duration + tail + 4` 自动算。
- **`ffmpeg -vsync 0` 在 ffmpeg 9 已移除**，用 `-fps_mode passthrough`（`frames.py` 已内置）。
- **不要用 `ffmpeg drawtext` 做帧号**：本机没有 fontconfig 配置，直接段错误。帧号由
  `frames.py sheet` 用 PIL 画。
- **`uiautomator dump` 偶发 `null root`**（启动/动画期间）：`emu.py` 已重试 4 次；仍失败就等
  一秒再 dump。
- **模板取错行**是最常见的误判来源：拖动前先 dump，用文字 bounds 定位；上下两条清单名相近时
  用 `sheet` 的接触表肉眼核对一次再下结论。
- **release 包**（`org.tasks`）不要拿来验证：debug 包名契约是三处联动的（见
  `docs/architecture.md`），而且模拟器上的正式包通常从没启动过。
- **`launch` 之后要等 5–8 秒**：monkey 意图返回不代表窗口已就绪，立刻 `tap` 会点空、
  `ui` 会 dump 到旧画面。冷启动后应用落回上次打开的清单页，汉堡按钮在左下 `94,2169`。

## 五、2026-10-25 基线（可对标）

问题：手动排序下长按清单向上拖，每越过上面一条，被拖行先下沉再还原。原因是被拖行也挂了
`Modifier.animateItem()`，槽位跳变那一帧它从旧槽位开始做 placement 动画，而 `translationY`
的补偿已经加上（见 `docs/drawer-sorting.md` 第五节）。修复：只给非拖拽行挂 `animateItem()`。

同一次拖动（Alpha 从 y=1411 拖到 y=1285，一行 126px）：

| | 拖动过程 | 交换那一帧 | 松手 |
|---|---|---|---|
| 修复前 | 1411→1350 正常跟手 | **+123px（沉到 1473）**，再逐帧爬回 | 落位 |
| 修复后 | 1411→1163 单调，无正向跳变 | 无 | +122px 落位 |

邻行位移仍在（~3–7px/帧），冷启动后 Alpha 仍在 Default list 之上。
