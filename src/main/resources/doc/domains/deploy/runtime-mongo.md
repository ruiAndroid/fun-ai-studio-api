# 运行态数据库（Mongo）方案 A：独立 Mongo（推荐）

本文档记录并固化当前结论：**线上运行态的 Mongo 应当独立于用户应用容器与 Workspace**，作为“运行态基础设施”单独部署，避免因重部署/迁移/容器重建导致数据丢失。

---

## 1. 为什么必须独立（你们现状的关键事实）

你们当前 runtime-agent 的部署策略是：

- `podman rm -f rt-app-{appId}`（删除旧容器）
- `podman run ... <image>`（启动新容器）

因此：

- 任何写在“用户应用容器文件系统”里的数据都会随着重部署丢失
- 把 Mongo 跑在用户应用容器里（或 sidecar 容器里）都不可靠

结论：**Mongo 必须独立出来，并做宿主机持久化/备份**。

---

## 2. 推荐拓扑（方案 A：独立 Mongo 服务器）

- 新增一台独立服务器（建议单独节点，避免与 API/MySQL、Runtime 网关抢资源）
- Mongo 只对内网开放（不对公网开放）

运行态链路：

- 用户应用容器（102） → 运行态 Mongo（Mongo 节点） → 数据持久化与备份

---

## 3. 多租户隔离方式（最小可用）

第一阶段推荐：**一个 mongod，多库隔离**：

- 每个 app 一个 db：`db_{appId}`
  - 例：`db_20000254`

优点：

- 部署简单、资源占用低
- 运维成本最低（先跑通闭环）

后续演进：

- 用户量大/隔离要求更高时，再考虑按用户/按项目拆实例或分片

---

## 4. 应用连接参数（平台注入）

运行态建议统一注入：

- `MONGODB_URI=mongodb://<mongoHost>:27017/db_{appId}`

如果启用账号密码：

- `MONGODB_URI=mongodb://<user>:<pass>@<mongoHost>:27017/db_{appId}?authSource=admin`

> 备注：你们 Workspace 的开发态 Mongo 可以继续存在，但它仅用于开发预览；线上运行态必须用独立 Mongo。

---

## 5. 安全组/防火墙建议

Mongo 节点入站（Inbound）：

- TCP `27017`：仅允许来源 **Runtime 节点（102）**（以及必要的运维跳板机/备份节点）
- 不对公网开放

Mongo 节点出站（Outbound）：

- 默认即可（如做严格收敛，至少保留 DNS/NTP 与备份目标的访问）

---

## 6. 数据迁移口径（从 Workspace 到运行态）

当前推荐口径（最简单）：

- Workspace 的 Mongo 数据视为**测试数据**，线上不迁移
- 线上运行态 Mongo 从空库开始：
  - 由应用启动时自动建 collection/index（很多 ODM 支持）
  - 或由 Runner 在部署前执行 `npm run db:init`（后续可加）

需要迁移时再补 `mongodump/mongorestore` 流程。

---

## 7. 备份建议（上线前就要定）

至少做到：

- 每日备份（逻辑备份或快照）
- 异机/对象存储保存（不要只放本机）
- 保留周期：至少 7 天（或按合规要求更久）

---

## 8. 下一步（等你准备开始做时）

你后续只需要确定：

- Mongo 节点 IP（内网）
- 是否需要账号密码（以及凭证管理方式）

然后我们会做两件事：

1) 在 Mongo 节点部署 mongod（systemd 或容器都可），并落盘到 `/data/funai/mongo`
2) 在 Runner/Runtime 部署流程中注入 `MONGODB_URI`（以及可选账号密码）


