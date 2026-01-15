# Workspace：容器级（container 子系统）

> 双机部署提示：本页接口对外仍由 API 服务器（小机）暴露，但在双机模式下会被 API 服务器（小机）网关/Nginx 转发到 Workspace 开发服务器（大机）容器节点（workspace-node）执行。

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

### remove（删除用户容器）

- `POST /api/fun-ai/workspace/container/remove?userId=...`

用途：

- 当容器进入异常状态（例如挂载/环境变量变更需要重建）时，允许通过 API 主动删除 `ws-u-{userId}` 容器，替代手工 `podman rm -f ws-u-...`

重要说明：

- **默认只删除容器本身**（`docker rm -f`），不会删除宿主机持久化目录 `{hostRoot}/{userId}`，因此项目代码/运行日志等仍会保留。
- 删除后再次调用 `container/ensure` 或 `open-editor` 会自动重建容器。

## 与 Mongo（可选）的关系

当配置启用 `funai.workspace.mongo.enabled=true` 时，容器 ensure 过程会额外准备并挂载用户级 Mongo 持久化目录（建议与 workspace 目录分离）：

- 宿主机：`{mongo.hostRoot}/{userId}/mongo/db`、`{mongo.hostRoot}/{userId}/mongo/log`
- 容器：`/data/db`、`/var/log/mongodb`（可配置）

更多细节（dbName 隔离规则、环境变量注入、排错等）见：`mongo.md`

## 资源限制（推荐生产环境显式配置）

当前 workspace 容器默认为长驻（`--restart=always`），在小规格机器（如 2c2g）上建议对单用户容器加资源上限，避免 `npm install/build` 或常驻 `mongod` 把宿主机打满。

新增配置（可选，不配置则不生效）：

- `funai.workspace.dockerMemory`: 注入 `docker run --memory`，示例 `1400m`
- `funai.workspace.dockerMemorySwap`: 注入 `docker run --memory-swap`，示例 `1400m`
- `funai.workspace.dockerCpus`: 注入 `docker run --cpus`，示例 `1.5`
- `funai.workspace.pidsLimit`: 注入 `docker run --pids-limit`，示例 `512`

2c2g 推荐起点（仅供参考，按业务压测调整）：

- `funai.workspace.dockerMemory=1400m`
- `funai.workspace.dockerCpus=1.5`
- `funai.workspace.pidsLimit=512`


