# Workspace：容器级（container 子系统）

## 职责

容器级子系统负责“确保用户容器存在且运行、挂载就绪、心跳活跃”，不涉及具体 app 的运行态。

对应控制器：

- `fun.ai.studio.controller.workspace.container.FunAiWorkspaceContainerController`
- 路由前缀：`/api/fun-ai/workspace/container`

## 接口

### ensure（创建目录并确保容器运行）

- `POST /api/fun-ai/workspace/container/ensure?userId=...`

副作用：

- 创建宿主机目录：`{hostRoot}/{userId}/apps`、`{hostRoot}/{userId}/run`
- 创建/启动容器：`ws-u-{userId}`
- 挂载：`{hostRoot}/{userId}` -> 容器 `/workspace`（可配置）

返回：

- `containerName / hostPort / containerPort / hostWorkspaceDir / containerWorkspaceDir ...`

### status（查询容器状态）

- `GET /api/fun-ai/workspace/container/status?userId=...`

返回容器状态（NOT_CREATED/RUNNING/EXITED/UNKNOWN）、端口与宿主机目录信息。

### heartbeat（活跃心跳）

- `POST /api/fun-ai/workspace/container/heartbeat?userId=...`

用途：

- 维持“该用户活跃”的最后时间，用于 idle 回收策略（先 stop run，再 stop container）。

## 与 Mongo（可选）的关系

当配置启用 `funai.workspace.mongo.enabled=true` 时，容器 ensure 过程会额外准备并挂载用户级 Mongo 持久化目录（建议与 workspace 目录分离）：

- 宿主机：`{mongo.hostRoot}/{userId}/mongo/db`、`{mongo.hostRoot}/{userId}/mongo/log`
- 容器：`/data/db`、`/var/log/mongodb`（可配置）


