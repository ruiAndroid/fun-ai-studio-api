# 子系统文档索引（面向 AI/新人）

本目录按“业务域/子系统”拆分文档，目标是让 AI 或新同学能快速理解：

- 每个子系统的职责边界
- 核心数据/目录结构
- 关键接口与典型调用链路
- 与其它子系统的交互点与约束

## 应用管理（App）

- [应用管理（含 open-editor 聚合入口）](./app/README.md)

## Workspace（在线开发环境）

- [Workspace 总览](./workspace/README.md)
- [Workspace：容器级（ensure/status/heartbeat）](./workspace/container.md)
- [Workspace：文件域（文件 CRUD、zip 导入导出）](./workspace/files.md)
- [Workspace：运行态（start/stop/status）](./workspace/run.md)
- [Workspace：实时通道（SSE events、WebSocket 终端）](./workspace/realtime.md)
- [Workspace：internal（nginx auth_request 查询端口）](./workspace/internal.md)


