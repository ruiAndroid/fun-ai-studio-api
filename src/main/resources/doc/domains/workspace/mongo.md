# Workspace：容器内 Mongo（可选）

> 双机部署提示：本页相关接口（以及运行态注入的 Mongo 环境变量）在双机模式下由大机容器节点执行；小机负责鉴权/归属校验与转发。

## 背景与设计目标

workspace 容器镜像可选内置 MongoDB（`mongod`/`mongosh`），用于给用户的 Node 项目提供“开箱即用”的本地数据库能力。

关键原则：

- **一个用户容器仅一个 `mongod` 实例**（用户级）
- **一个项目一个逻辑库（database）**：`dbName = {dbNamePrefix}{appId}`（默认 `db_2002` 这种形式）
- **数据持久化目录必须与 workspace 代码目录分离**：避免在线编辑器/同步机制干扰 WiredTiger 文件（见下文）

## 开关与配置项

配置前缀：`funai.workspace.mongo.*`

- **`funai.workspace.mongo.enabled`**：是否启用（默认 `false`）
- **`funai.workspace.mongo.hostRoot`**：宿主机 Mongo 持久化根目录（强烈建议与 `funai.workspace.hostRoot` 分离）
- **`funai.workspace.mongo.containerDbPath`**：容器内 `mongod --dbpath`（默认 `/data/db`）
- **`funai.workspace.mongo.containerLogDir`**：容器内日志目录（默认 `/var/log/mongodb`）
- **`funai.workspace.mongo.bindIp`**：`mongod --bind_ip`（默认 `127.0.0.1`，仅容器内访问）
- **`funai.workspace.mongo.port`**：端口（默认 `27017`）
- **`funai.workspace.mongo.dbNamePrefix`**：逻辑库名前缀（默认 `db_`）
- **`funai.workspace.mongo.logFileName`**：日志文件名（默认 `mongod.log`）

启用条件：

- workspace 镜像内需要包含 `mongod`（以及可选的 `mongosh` 便于排错/调试）
- 后端必须配置 `funai.workspace.mongo.hostRoot`（否则 ensure 会直接报错）

## 目录结构与 bind mount

当调用 workspace 的 container ensure（创建/确保容器）时，如果启用 Mongo，会额外准备并挂载用户级目录：

- **宿主机目录（按 userId 隔离）**
  - `{mongo.hostRoot}/{userId}/mongo/db`
  - `{mongo.hostRoot}/{userId}/mongo/log`
- **容器内目录**
  - `{mongo.containerDbPath}`（默认 `/data/db`）
  - `{mongo.containerLogDir}`（默认 `/var/log/mongodb`）

为什么不把 Mongo 数据目录放在 workspace 目录下？

- workspace 目录通常会被在线编辑器/同步机制“频繁扫描/同步/增量写入”
- Mongo 的 WiredTiger 文件对这种外部干扰非常敏感，容易出现数据损坏/锁冲突/性能抖动
- 因此实现上明确要求使用独立的 `mongo.hostRoot` 做持久化

## 容器启动时如何拉起 mongod

当 `funai.workspace.mongo.enabled=true` 时，workspace 容器的 keep-alive bootstrap 会尝试启动 `mongod`：

- 若镜像内存在 `mongod`：后台启动
  - `mongod --dbpath ... --bind_ip ... --port ... --logpath ... --logappend`
- 若镜像内不存在 `mongod`：打印提示并跳过（容器仍会常驻，但不会有 Mongo 服务）

注意：

- `bindIp` 默认 `127.0.0.1`，意味着 **Mongo 仅能在容器内部访问**
- 容器对外只映射了预览端口（`containerPort` -> `hostPort`），**Mongo 端口默认不会做 `-p` 映射**

## “每个项目一个库”的隔离规则

你们采用的是“同一 `mongod` 多库隔离”：

- **dbName 计算规则**：`{dbNamePrefix}{appId}`
  - 例如：`dbNamePrefix=db_`，`appId=2002` → `db_2002`
- 一个用户容器内：
  - 多个 app 共用同一个 `mongod` 实例
  - 但使用不同的 database（逻辑库）隔离数据

## 运行态（DEV/START）如何把连接信息注入到项目

当启用 Mongo 时，后端在启动 DEV/START 受控任务时会注入环境变量（项目侧直接读取即可）：

- **`MONGO_HOST`**：`127.0.0.1`
- **`MONGO_PORT`**：`27017`（或配置值）
- **`MONGO_DB_PREFIX`**：`db_`（或配置值）
- **`MONGO_DB_NAME`**：`${MONGO_DB_PREFIX}{appId}`
- **`MONGO_URL`**：`mongodb://${MONGO_HOST}:${MONGO_PORT}/${MONGO_DB_NAME}`
- **`MONGODB_URI`**：同 `MONGO_URL`（兼容常见框架/脚手架）

项目侧（Node）建议用法：

- 优先读 `process.env.MONGODB_URI` 或 `process.env.MONGO_URL`
- 不要把 dbName 写死在代码里（避免和平台隔离规则冲突）

## 运维/排错指引（容器内）

常见现象 1：启用了 Mongo，但项目连接不上（ECONNREFUSED）。

排查思路：

- 进入容器后确认 `mongod` 是否存在并在跑
  - `command -v mongod`
  - `ps aux | grep mongod`
- 确认端口监听（有些镜像没有 `netstat`，可用 `ss`）
  - `ss -lntp | grep 27017 || true`
- 看日志
  - `tail -n 200 {mongo.containerLogDir}/{mongo.logFileName}`

常见现象 2：Mongo 数据目录异常/损坏（WiredTiger 报错）。

- 确认 Mongo 数据目录是否误放到了 workspace 同步目录下
- 确认宿主机 bind mount 的目录权限/磁盘空间
- 必要时：先备份 `{mongo.hostRoot}/{userId}/mongo/db` 后再做清理/重建

## 安全模型

- 默认 `bindIp=127.0.0.1`：只允许容器内访问
- 不对宿主机暴露 Mongo 端口（默认不做 `docker -p 27017:27017`）
- 数据隔离粒度：
  - 用户级：通过容器与用户级持久化目录隔离
  - 项目级：通过 `dbNamePrefix + appId` 的 database 隔离

## Mongo Explorer（Web，只读）

如果用户需要“可视化浏览/查询数据库”，推荐使用平台自带的 Mongo Explorer（不暴露 27017，后端通过 `docker exec + mongosh` 受控代理）。

关键约束：

- **必须先 preview**：仅允许在 `RUNNING + type=START`（preview）运行态访问数据库，避免绕过平台运行态控制/回收策略。

- 说明文档：`mongo-explorer.md`
- 简易页面入口：`/workspace-mongo.html`


