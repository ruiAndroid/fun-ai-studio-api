# 子系统文档索引（面向 AI）

本目录按“业务域/子系统”拆分文档，目标是让 AI 能快速理解：

- 每个子系统的职责边界
- 核心数据/目录结构
- 关键接口与典型调用链路
- 与其它子系统的交互点与约束

## 应用管理（App）

- [应用管理（含 open-editor 聚合入口）](./app/README.md)

## Architecture（系统架构）

- [系统架构说明（全局架构图/关键链路/模块边界）](./architecture/README.md)

## Workspace（在线开发环境）

- [Workspace 总览](./workspace/README.md)
- [Workspace：容器级（ensure/status/heartbeat）](./workspace/container.md)
- [Workspace：文件域（文件 CRUD、zip 导入导出）](./workspace/files.md)
- [Workspace：运行态（start/stop/status）](./workspace/run.md)
- [Workspace：实时通道（SSE events、WebSocket 终端）](./workspace/realtime.md)
- [Workspace：internal（nginx auth_request 查询端口）](./workspace/internal.md)
- [Workspace：npm 缓存（Verdaccio 代理仓库）](./workspace/npm-cache.md)
- [Workspace：容器内 Mongo（可选）](./workspace/mongo.md)
- [Workspace：Mongo Explorer（Web，只读）](./workspace/mongo-explorer.md)

## Deploy（应用部署：控制面/执行面/运行态）

- [Deploy 总览（API 入口与调用链）](./deploy/README.md)
- [Deploy：整体架构与互联矩阵（推荐先读）](./deploy/architecture.md)

## Server（部署与扩容）

- [Server 总览](./server/README.md)
- [多机扩容方案（面向 Workspace）](./server/scaling-workspace.md)
- [多机扩容方案（面向 Deploy / Runner / Runtime）](./server/scaling-deploy-runtime.md)
- [安全组/防火墙放行矩阵（现网最小可用）](./server/security-groups.md)


