# Server 域：部署与扩容（单机 → 多机）

本域文档面向“服务器层面”的架构与演进，目标是支撑 FunAI Studio 从单机（Spring Boot + MySQL + Docker）平滑扩容到多机。

## 目录

- [多机扩容方案（面向 Workspace）](./scaling-workspace.md)
- [Workspace 开发服务器（大机）容器节点（workspace-node）部署与联调说明](./workspace-node.md)
- [API 服务器（小机）Nginx 示例：workspace 双机拆分（/ws 与终端 WS 转发到 Workspace 开发服务器（大机））](./small-nginx-workspace-split.conf.example)
- [最小可落地监控方案（Prometheus + Grafana）](./monitoring-minimal.md)
- [Prometheus 最小告警规则示例](./monitoring-prometheus-alerts-minimal.yml)


