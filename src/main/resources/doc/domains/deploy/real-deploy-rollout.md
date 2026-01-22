# 真实部署闭环落地计划（现网 5 台：API/Workspace/Deploy/Runner/Runtime）

本文档把“从用户点击部署到公网可访问”的全链路拆成可落地步骤，适用于你当前的 5 台现网：

- API：`172.21.138.91`
- Workspace-dev：`172.21.138.87`
- Deploy：`172.21.138.100`
- Runner：`172.21.138.101`
- Runtime：`172.21.138.102`

目标：先跑通最小闭环（Deploy→Runner→Runtime），再逐步引入“源码获取/构建/回滚/多 Runtime 扩容”等复杂度。

---

## 1. 三类“存储/状态”必须先统一口径

### 1.1 Workspace（开发态存储：源码/依赖/临时文件）

- **宿主机落盘**：Workspace-dev(87) `/data/funai/workspaces`
- **容器名**：`ws-u-{userId}`（示例）
- **预览入口**：统一公网入口下的 `/ws/...`（由网关转发到 87 的 Nginx）

> 结论：Workspace 是“开发态”，不建议 Runtime/Runner 直接依赖 87 的文件系统做部署交付。

### 1.2 制品（交付物：镜像）

- **推荐唯一制品**：ACR 镜像（Runner build → push；Runtime pull → run）
- 好处：跨机器交付稳定；避免“复制目录/rsync/zip 传输”带来的不确定性

### 1.3 控制面状态（Deploy 落库到 API 的 MySQL）

Deploy 控制面的主数据落库（MySQL 在 91）：

- `fun_ai_deploy_runtime_node`（Runtime 节点注册表）
- `fun_ai_deploy_runtime_placement`（`appId -> nodeId` 粘性落点）
- `fun_ai_deploy_job`（Job 队列）
- `fun_ai_deploy_app_run`（last-known：最后一次部署/错误/落点）

> 结论：节点管理页面（`/admin/nodes-admin.html`）调用 Deploy 的 admin 接口即可；页面不直连 DB。

---

## 2. 两条“对外地址”要区分：预览 vs 运行态

### 2.1 开发预览（Workspace）

- 路径：`/ws/...`
- 链路：公网入口(通常在 91) → 87 Nginx → workspace 容器端口

### 2.2 运行态访问（Runtime）

- 路径：`/apps/{appId}/...`
- 链路（推荐）：公网入口（SLB/网关）→ Runtime 网关(102) → 用户应用容器(appId)

> 运行态访问不应回源到 Workspace；部署后用户访问只走 Runtime。

---

## 3. 核心通讯与鉴权矩阵（谁调谁、带什么）

### 3.1 API → Deploy（创建/查询/列表 Job）

- 端口：100:7002
- Header：`X-DEPLOY-SECRET`

### 3.2 Runtime-agent → Deploy（节点心跳）

- 端口：100:7002
- 接口：`POST /internal/runtime-nodes/heartbeat`
- Header：`X-RT-Node-Token`

### 3.3 Runner → Deploy（claim/heartbeat/report）

- 端口：100:7002
- Runner 侧周期轮询 claim；执行中 heartbeat；结束后 report

### 3.4 Runner → runtime-agent（部署/停止/状态）

- 端口：102:7005
- Header：`X-Runtime-Token`

---

## 4. 部署 Job 的 payload（建议先定义 v1 最小契约）

为了让 Runner 能执行部署，Deploy 的 Job payload 建议至少包含：

- `appId`（必填）
- **阶段 1（最小闭环）**：`image`（必填，镜像全名，Runner 直接部署无需构建）
- **阶段 2（引入构建）**：`repoUrl`、`commit`、`dockerfilePath`、`buildContext`、`imageRepo`

> 建议：阶段 1 先用“预构建镜像”，把链路跑通；再接入从 git 拉源码构建。

---

## 5. 分阶段落地（强烈建议按顺序）

### 5.1 阶段 1：最小闭环（不做构建，直接部署镜像）

**目标**：证明 Deploy→Runner→Runtime 的控制面/执行面/运行态闭环可用。

- **输入**：一个已存在于 ACR 的镜像（例如 hello-world/你们自定义 demo）
- **Runner 行为**：
  - claim job
  - 调 runtime-agent `/agent/apps/deploy`（携带 appId + image）
  - report SUCCEEDED/FAILED
- **验收**：
  - Deploy：`fun_ai_deploy_job` 有记录，状态能从 PENDING→RUNNING→SUCCEEDED
  - Deploy：`fun_ai_deploy_app_run` 有 last-known
  - Runtime：容器存在，网关路由 `/apps/{appId}` 可访问

### 5.2 阶段 2：引入 git 拉源码 + build + push（推荐）

**目标**：Runner 真正完成 “拉代码→构建镜像→推 ACR→部署”。

建议 Runner 统一支持：

- `repoUrl`（建议 http(s)）
- `commit`（可选，默认 main/master）
- `dockerfilePath`（默认 `Dockerfile`）
- `image`（最终 push 的完整 tag）

验收：同阶段 1，但多了镜像 tag 可追溯与回滚能力。

### 5.3 阶段 3：Workspace 产出与 git 的关系（可选）

两种路线二选一：

- **路线 A（推荐）**：Workspace 只做开发，发布必须 push 到 git（Runner 从 git 拉）
- **路线 B（补充能力）**：Workspace 导出 zip/upload 到对象存储，Runner 下载构建（复杂度更高）

---

## 6. 一步一步“怎么验收”（建议照抄）

### 6.1 Deploy 是否能看到 Runtime 节点在线

在 Deploy(100)：

- `GET /admin/runtime-nodes/list`（Header: `X-Admin-Token`）

### 6.2 创建一个“阶段 1”的 job（image 直部署）

建议在 API 侧提供 create payload（或临时直接调用 Deploy `POST /deploy/jobs`）。

### 6.3 Runner 日志观察点

- claim 到 jobId + runtimeNode(agentBaseUrl/gatewayBaseUrl)
- deploy 调用 runtime-agent 成功/失败
- report 成功/失败写回 Deploy

### 6.4 Runtime 验收点

- `runtime-agent /internal/health` OK
- 用户容器存在（podman/docker ps）
- `/apps/{appId}` 路由可访问

---

## 7. 常见坑（提前规避）

- **不要让 Runner/Runtime 依赖 Workspace 的目录**做交付：跨机复制/权限/路径差异会放大问题
- **先跑通“镜像直部署”**再做构建：把变量从 N 个降到 1 个
- **DB 权限**：91 的 MySQL 必须允许 100 访问（你已经遇到过 host not allowed）
- **网关路由**：`/apps/{appId}` 必须明确由谁承接（102 网关还是 91 统一入口再转发）


