# Fun AI Studio 文档总索引

## 从这里开始

- **[系统架构说明（推荐先读）](./domains/architecture/README.md)** — 从 7 台服务器整体视角理解系统

## 服务器与部署

| 文档 | 说明 |
|------|------|
| [安全组/防火墙放行矩阵](./domains/server/security-groups.md) | 7 台服务器端口与 IP 规则 |
| [标准监控方案（systemd）](./domains/server/monitoring-standard.md) | Prometheus + Alertmanager + Grafana |
| [Workspace 节点联调说明](./domains/server/workspace-node.md) | 开发节点部署与调试 |
| [Workspace 4 节点扩容](./domains/server/workspace-4nodes-rollout.md) | Workspace 横向扩展 |
| [Deploy/Runner/Runtime 扩容](./domains/server/scaling-deploy-runtime.md) | 发布能力横向扩展 |

## 发布能力（Deploy / Runner / Runtime）

| 文档 | 说明 |
|------|------|
| [Deploy 架构与互联矩阵](./domains/deploy/architecture.md) | 控制面/执行面/运行态详解 |
| [真实部署闭环落地计划](./domains/deploy/real-deploy-rollout.md) | 分阶段落地步骤 |
| [Runtime 节点磁盘水位调度](./domains/server/scaling-deploy-runtime.md#6-runtime-节点磁盘水位调度与扩容到-3-台推荐生产策略) | **新特性**：智能选址避免磁盘打满 |
| [运行态 Mongo 方案](./domains/deploy/runtime-mongo.md) | 第 7 台服务器：独立 Mongo |
| [标准流水线](./domains/deploy/git-acr-pipeline.md) | Git → Harbor(103) → Runtime |
| [Gitea 自动化](./domains/deploy/gitea-automation.md) | 仓库创建 + 授权 + SSH |
| [Harbor 运维规范](./domains/deploy/acr-auth-and-retention.md) | 登录与制品保留（文件名历史原因保留 acr- 前缀） |
| [Gitea 部署（103）](./domains/deploy/git-server-gitea.md) | Git 服务器从零搭建 |
| [Harbor 自建镜像站（103）](./domains/deploy/harbor-103.md) | 自建镜像仓库（替代 ACR） |

## 子系统文档

- [子系统文档索引](./domains/README.md) — Workspace / Deploy / App 各域详细说明

## 其他

- [目录结构建议](./structure.md)


