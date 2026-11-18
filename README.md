# DSH Minecraft GameTest

DeepSeek Harness 插件通过 MCP 操作 Minecraft Java 版客户端。插件可以把操作和断言保存为 JSON 测试，NeoForge 模组在游戏客户端逐步执行并报告结果。

## 构建与安装

使用 Node.js 24.9+、pnpm 11、JDK 25 和 NeoForge ModDevGradle。构建 1.21.11 时，NeoForm 还需要可被 Gradle 找到的完整 JDK 21。

```sh
pnpm install --frozen-lockfile
pnpm build
pnpm pack
./mods/gradlew -p mods build
```

| Minecraft Java 版 | NeoForge 构建 | 模组产物目录 |
| --- | --- | --- |
| 1.21.11 | 21.11.48 | `mods/1.21.11/build/libs/` |
| 26.1.2 | 26.1.2.127 | `mods/26.1.2/build/libs/` |
| 26.2 | 26.2.0.95 | `mods/26.2/build/libs/` |
| 26.3 | 26.3.0.113 | `mods/26.3/build/libs/` |

1. 把对应版本的 JAR 放入 NeoForge 客户端的 `mods` 目录，启动游戏。模组在 `http://127.0.0.1:4723/mcp` 提供 Streamable HTTP 服务。多个测试实例可用 JVM 参数 `-Ddsh.gametest.port=4724` 指定其他端口。
2. 把插件和 loader 装进同一个 DSH profile。插件使用 loader 2.x 配置面板，Web 组件由 `@dsh-plugin/dsh-ui-components` 构建。以下是 DSH 0.2.2-rc.4 的安装方式。

   ```sh
   dsh plugin --profile web add @dsh-plugin/dsh-loader@2.0.0-dev.43799314028 ./dsh-plugin-dsh-minecraft-gametest-0.1.0.tgz
   dsh web
   ```

3. 在 DSH 的插件详情页展开 **Minecraft GameTest** 配置卡片，填写游戏实例内 `config/dsh-minecraft-gametest/mcp-token` 的绝对路径，再点 **Test connection**。也可在 DSH 进程里设置 `DSH_MINECRAFT_MCP_TOKEN_FILE`，端口不同则设置 `DSH_MINECRAFT_MCP_ENDPOINT`。独立 MCP 客户端用此文件里的值作为 Bearer 令牌。

## 工具

DSH 中的工具名带有 `mc_` 前缀。

| 能力 | MCP 工具 |
| --- | --- |
| 版本、维度和当前界面 | `status` |
| PNG 截图、带控件编号的截图 | `screenshot`、`screenshot_ui` |
| 视角朝向坐标、yaw/pitch 或实体 UUID | `look` |
| 两个角点内的已加载实体和方块 | `get_entities`、`get_blocks` |
| 方块状态和客户端持有的方块实体 SNBT | `get_block` |
| 玩家坐标、朝向、生命值、饥饿值和背包 | `get_player` |
| 编号点击、键盘按住、鼠标点击、拖拽和滚动 | `click_ui`、`key_input`、`mouse_input` |
| 执行游戏命令并获取回执 | `run_command` |
| 寻路、挖掘、耕种、跟随和飞行 | `task` |
| 显式等待和断言 | `wait_ticks`、`assert_block`、`assert_player_position`、`assert_screen` |
| 模组内重放和结果 | `replay_start`、`replay_status` |

打开界面后调用 `screenshot_ui`，指定 `widget`（原版按钮等控件）、`slot`（容器物品栏）或 `all`。把截图返回的 `screen`、`layout`、`control_type` 和数字 ID 一起交给 `click_ui`。如果界面控件列表改变，需要重新截图。任意界面的鼠标坐标使用截图元数据中的 GUI 像素尺寸。自绘控件可用 `mouse_input` 按坐标操作。

方块区域查询每次最多 4096 格。多人世界的数据范围由服务端同步决定。

## 游戏命令

用 MCP 的 `run_command` 或 DSH 的 `mc_run_command` 执行单行命令，例如 `{"command":"/list"}`。前导斜杠可以省略。命令使用当前玩家的权限。

工具在 `wait_ticks` 个客户端刻内收集服务端系统聊天，默认等待 30 刻。`feedback` 包含回执原文、是否为覆盖提示，以及可用的翻译键。成功消息、语法错误和权限错误均通过回执返回。无回执时返回 `no_feedback`。Minecraft 的系统聊天没有命令 ID，窗口内的其他系统消息也可能出现。

命令可录制和重放。测试可用 `expect_feedback` 要求某条回执包含指定文本。缺少指定文本会让这一步失败，并返回已收到的回执。

## 自动化任务

通过 MCP 的 `task` 或 DSH 的 `mc_task` 启动任务，例如 `{"action":"#tunnel","direction":"north","length":8}`。动作名也可省略 `#`。每次只运行一个任务。启动即返回 `task_id`，用 `status` 查看状态、进度和结果。

| 动作 | 行为 |
| --- | --- |
| `move_to` | 走到 `position` 指定的脚底方块中心。地面路径支持一格高度差，目标距离上限 64 格。 |
| `tunnel` | 水平开挖宽一格、高两格的隧道。可指定 `direction` 和 `length`，默认沿当前朝向挖 8 格。路线需要可站立的地面。 |
| `mine` | 在已加载的搜索区域寻找并开采 `block` 指定的方块，走过去拾取掉落。可用 `count` 限制数量，生存模式会从快捷栏选择工具。 |
| `farm` | 识别并采收成熟的小麦、胡萝卜、马铃薯、甜菜和下界疣。指定品种 `crop` 后，可用 `farm_mode: "replant"` 补种，或用 `"till"` 翻土种植。种子和锄头从快捷栏使用。 |
| `follow` | 跟随 `entity_uuid` 指定的已加载实体。默认保持约 3 格距离，运行 600 个活动刻，可用 `distance` 和 `duration_ticks` 调整。 |
| `surface` | 沿可行走的已加载地面路线寻找可见天空的位置。 |
| `elytra` | 装备鞘翅后按已加载地形寻找飞行通道并飞近 `position`。默认从空中进入滑翔，也会尝试从地面起飞。需要爬升时会使用副手或快捷栏里的烟花火箭。目标距离上限 160 格。 |
| `pause`、`paused`、`resume`、`cancel` | 暂停、查询暂停状态、恢复或取消任务。暂停会释放任务操控并停止计时，已经开始的游戏物理仍会继续。 |

`mine` 和 `farm` 可用 `from`、`to` 指定含两端的搜索区域，也可用 `radius` 从当前位置搜索。地面寻路使用正常步行与跳跃，停在未加载的地形边界。任务期间可以查询世界和截图。

## 记录和重放

1. 加入测试世界，调用 `mc_recording_start`，传入测试名，例如 `inventory-check`。默认目录是 DSH 会话工作区内的 `minecraft-tests`。
2. 通过 DSH 操作游戏并添加断言。插件按执行顺序保存成功的操作、等待和断言。截图与只读查询供模型观察游戏。用 `mc_wait_ticks` 表达游戏内等待。异步任务完成后会作为一个步骤写入录像。重放会等该任务结束后再执行下一步。
3. 调用 `mc_recording_stop` 获取文件路径。把游戏恢复到测试所需的位置与界面，再调用 `mc_replay`，传入同一个测试名。用 `mc_replay_status` 查看逐步结果。模组遇到首个失败步骤就停止，并返回步骤及原因。
4. 也可以把 JSON 放入游戏实例的 `config/dsh-minecraft-gametest/replays/`，在游戏中运行 `/mctest replay inventory-check`，用 `/mctest status` 检查结果。

录像格式为 `version: 1`，包含 `dimension` 和有序的 `steps`。每步包含原始 MCP `tool` 和 `arguments`。重放使用当前世界状态，实体 UUID 和模组界面等依赖需要由测试世界提供。

## 开发启动

```sh
./mods/gradlew -p mods :26.3:runClient
export DSH_MINECRAFT_MCP_TOKEN_FILE="$PWD/mods/26.3/run/config/dsh-minecraft-gametest/mcp-token"
```
