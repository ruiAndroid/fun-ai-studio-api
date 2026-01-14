# Server 域：部署与扩容（单机 → 多机）

本域文档面向“服务器层面”的架构与演进，目标是支撑 FunAI Studio 从单机（Spring Boot + MySQL + Docker）平滑扩容到多机。

## 目录

- [多机扩容方案（面向 Workspace）](./scaling-workspace.md)
- [大机容器节点（workspace-node）部署与联调说明](./workspace-node.md)
- [小机 Nginx 示例：workspace 双机拆分（/ws 与终端 WS 转发到大机）](./small-nginx-workspace-split.conf.example)


