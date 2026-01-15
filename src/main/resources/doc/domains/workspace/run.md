# Workspace：运行态（run 子系统）

> 双机部署提示：本页接口对外仍由 API 服务器（小机）暴露，但在双机模式下会被 API 服务器（小机）网关/Nginx 转发到 Workspace 开发服务器（大机）容器节点（workspace-node）执行。

## 职责

运行态子系统负责在用户容器内启动/停止“受控任务”（dev/preview/build/install），并对外暴露状态查询接口。

对应控制器：

- `fun.ai.studio.controller.workspace.run.FunAiWorkspaceRunController`
- 路由前缀：`/api/fun-ai/workspace/run`

## 核心约束

- **切换模式**：同一 `userId` 同一时间只能运行一个 `appId`。
- **固定端口（DEV/START）**：容器内预览服务必须绑定在 `containerPort`（默认 5173），否则 nginx 反代会命中错误端口。
- **平台最终控制权**：执行 build/preview/install 等受控接口时会先 stopRun，避免与 WS 终端自由命令并发导致状态/进程错乱。

## 接口

### start

- `POST /api/fun-ai/workspace/run/start?userId=...&appId=...`

行为（简化）：

- ensure 容器 + 目录
- 写入 `/workspace/run/current.json`（STARTING）
- 后台脚本执行 `npm install`（必要时）+ `npm run dev`
- 成功后写入 `/workspace/run/dev.pid` 与更新 `current.json`

### preview

- `POST /api/fun-ai/workspace/run/preview?userId=...&appId=...`

行为（简化）：

- stopRun（确保端口与运行态干净）
- ensure 容器 + 目录
- 写入 `/workspace/run/current.json`（STARTING，type=START）
- 后台脚本执行“可访问启动”（按项目 scripts 自动选择）：`start -> preview -> dev -> server`
  - 注入 `PORT/HOST/BASE_PATH` 等环境变量
  - 若选择 `preview/dev`，会尽量追加 `--host/--port/--strictPort/--base` 参数（适配 Vite 常见用法）
  - 若选择 `start/server` 且网关使用“/ws 前缀剥离转发（方案A）”，则 `BASE_PATH` 默认注入为 `/`（避免要求后端项目支持 `/ws/{userId}` 路由前缀）
- 成功后写入 `/workspace/run/dev.pid` 与更新 `current.json`

### build

- `POST /api/fun-ai/workspace/run/build?userId=...&appId=...`

行为（简化）：

- stopRun
- ensure 容器 + 目录
- 写入 `/workspace/run/current.json`（BUILDING，type=BUILD）
- 后台脚本执行 `npm run build`
- 结束后更新 `current.json`：写入 `finishedAt/exitCode` 并清理 `dev.pid`

### install

- `POST /api/fun-ai/workspace/run/install?userId=...&appId=...`

行为（简化）：

- stopRun
- ensure 容器 + 目录
- 写入 `/workspace/run/current.json`（INSTALLING，type=INSTALL）
- 后台脚本执行 `npm install`
- 结束后更新 `current.json`：写入 `finishedAt/exitCode` 并清理 `dev.pid`

### stop

- `POST /api/fun-ai/workspace/run/stop?userId=...`

行为：

- kill 进程组
- 清理 pid/meta 文件

### status

- `GET /api/fun-ai/workspace/run/status?userId=...`

状态枚举：

- `IDLE / STARTING / RUNNING / DEAD / UNKNOWN / BUILDING / INSTALLING / SUCCESS / FAILED`

`RUNNING` 时会返回 `previewUrl`（形如 `{previewBaseUrl}/ws/{userId}/`）。

说明：

- `type=DEV/START`：会做“进程存活 + 端口就绪”判定，`RUNNING` 时返回 `previewUrl`
- `type=BUILD/INSTALL`：不做端口探测；依据进程存活与 `exitCode` 返回 `BUILDING/INSTALLING/SUCCESS/FAILED`

### current.json（运行元数据）

`/workspace/run/current.json`（宿主机与容器 bind mount 共享）示例字段：

- `appId`
- `type`：`DEV / START / BUILD / INSTALL`
- `pid`：受控任务的进程组 leader（用于 stopRun）
- `startedAt`：epoch seconds
- `finishedAt`：epoch seconds（BUILD/INSTALL 常用）
- `exitCode`：退出码（BUILD/INSTALL 常用）
- `logPath`：`/workspace/run/dev.log`

## 与 Mongo（可选）的关系

当启用 workspace mongo 时，DEV/START 过程会设置环境变量：

- `MONGO_URL=mongodb://127.0.0.1:27017/{dbNamePrefix}{appId}`
- `MONGODB_URI` 同值（兼容）

Node 项目只需读取 `process.env.MONGO_URL`/`process.env.MONGODB_URI` 建立连接。

更完整的说明见：`mongo.md`


