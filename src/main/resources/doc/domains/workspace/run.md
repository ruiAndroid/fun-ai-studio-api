# Workspace：运行态（run 子系统）

## 职责

运行态子系统负责在用户容器内启动/停止 dev server（典型是 `npm run dev`），并对外暴露状态查询接口。

对应控制器：

- `fun.ai.studio.workspace.run.FunAiWorkspaceRunController`
- 路由前缀：`/api/fun-ai/workspace/run`

## 核心约束

- **切换模式**：同一 `userId` 同一时间只能运行一个 `appId`。
- **固定端口**：容器内 dev server 必须绑定在 `containerPort`（默认 5173），否则 nginx 反代会命中错误端口。

## 接口

### start

- `POST /api/fun-ai/workspace/run/start?userId=...&appId=...`

行为（简化）：

- ensure 容器 + 目录
- 写入 `/workspace/run/current.json`（STARTING）
- 后台脚本执行 `npm install`（必要时）+ `npm run dev`
- 成功后写入 `/workspace/run/dev.pid` 与更新 `current.json`

### stop

- `POST /api/fun-ai/workspace/run/stop?userId=...`

行为：

- kill 进程组
- 清理 pid/meta 文件

### status

- `GET /api/fun-ai/workspace/run/status?userId=...`

状态枚举：

- `IDLE / STARTING / RUNNING / DEAD / UNKNOWN`

`RUNNING` 时会返回 `previewUrl`（形如 `{previewBaseUrl}/ws/{userId}/`）。

## 与 Mongo（可选）的关系

当启用 workspace mongo 时，start 过程会在启动 `npm run dev` 前设置环境变量：

- `MONGO_URL=mongodb://127.0.0.1:27017/{dbNamePrefix}{appId}`
- `MONGODB_URI` 同值（兼容）

Node 项目只需读取 `process.env.MONGO_URL`/`process.env.MONGODB_URI` 建立连接。


