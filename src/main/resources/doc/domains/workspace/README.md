# Workspace 总览（在线开发环境域）

Workspace 域的目标：给每个用户提供一个可持久化的“在线开发容器”，支持：

- 项目文件同步（宿主机落盘 + 容器可见）
- 容器内运行 dev server（Vite/Node）
- 实时日志/状态（SSE）
- 在线终端（WebSocket + docker exec）
- 依赖安装加速（npm 缓存/后续私有仓库）

## 多机部署提示（现网 5 台：接口会转发到容器节点执行）

在“多机部署”（现网 5 台：API / workspace-dev / Deploy / Runner / Runtime）模式下：

- **对外 URL 不变**：前端/调用方仍请求 API（入口）暴露的 `/api/fun-ai/workspace/**` 与 `/ws/{userId}/...`
- **实际执行在 workspace-dev**：API 会将 workspace 相关请求（以及 `/ws/*` 预览流量）转发到 workspace-dev（workspace-node + workspace-dev Nginx）完成
- **排障位置变化**：容器、端口池、运行日志、verdaccio 等问题优先在 workspace-dev 排查

## 核心约束（单机版）

- **每个用户一个容器**：容器名 `ws-u-{userId}`（可配置前缀）
- **每个用户同一时间仅运行一个 app**：切换 app 时会 stop 旧 run
- **预览入口是用户级**：`/ws/{userId}/`（Nginx 反代到该用户固定 hostPort）
- **宿主机持久化目录**：`{hostRoot}/{userId}/...` 挂载到容器 `containerWorkdir`（默认 `/workspace`）
  - 宿主机：`{hostRoot}/{userId}/apps/{appId}` <-> 容器：`/workspace/apps/{appId}`
  - 宿主机：`{hostRoot}/{userId}/run` <-> 容器：`/workspace/run`

## 目录/组件定位

- 主要配置：`fun.ai.studio.workspace.WorkspaceProperties`
- 核心服务：`fun.ai.studio.service.impl.FunAiWorkspaceServiceImpl`
- 控制器（按子域拆分）：
  - 容器级：`fun.ai.studio.controller.workspace.container.FunAiWorkspaceContainerController`
  - 文件域：`fun.ai.studio.controller.workspace.files.FunAiWorkspaceFileController`
  - 运行态：`fun.ai.studio.controller.workspace.run.FunAiWorkspaceRunController`
  - internal：`fun.ai.studio.controller.workspace.internal.FunAiWorkspaceInternalController`
  - SSE：`fun.ai.studio.controller.workspace.realtime.FunAiWorkspaceRealtimeController`
  - WebSocket 终端：`fun.ai.studio.workspace.realtime.WorkspaceTerminalWebSocketHandler`

## 常用增强能力

- [npm 缓存（最简方案）](./npm-cache.md)
- [容器内 Mongo（可选）](./mongo.md)
- [Mongo Explorer（Web，只读）](./mongo-explorer.md)

## 典型链路（在线编辑器进入）

```mermaid
sequenceDiagram
participant FE as "前端(Frontend)"
participant WSFiles as "文件接口(WorkspaceFilesAPI)"
participant WSRun as "运行态接口(WorkspaceRunAPI)"
participant SSE as "实时通道(WorkspaceRealtimeAPI)"

FE->>WSFiles: ensure-dir(确保目录)(userId,appId)
FE->>WSFiles: upload-zip(导入) / file CRUD(文件操作) (可选)
FE->>WSRun: build/install/preview(按钮触发)
FE->>SSE: events(订阅)(userId,appId,withLog=true)
SSE-->>FE: status/log/ping(状态/日志/心跳)
```


