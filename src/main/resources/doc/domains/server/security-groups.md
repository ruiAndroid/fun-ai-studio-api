# 安全组/防火墙放行矩阵（现网 7 台）

本文档用于归档当前 FunAI Studio 的**安全组（Security Group）/防火墙**放行规则，目标是：

- **最小暴露**：只放行必要端口，只信任必要来源 IP
- **便于扩容**：后续新增 Runner/Runtime 节点时，在同一份文档内追加

> 约定：以下均为 **内网 IP**，公网仅在"统一入口"处放开。

---

## 1. 当前在线服务器清单（7 台）

| 序号 | 角色 | 内网 IP | 主要端口 |
|------|------|---------|----------|
| 1 | API（统一入口 / Control Plane） | `172.21.138.91` | 80/443, 8080 |
| 2 | Workspace-dev（workspace-node + Nginx） | `172.21.138.87` | 80, 7001 |
| 3 | Deploy（Deploy 控制面） | `172.21.138.100` | 7002 |
| 4 | Runner（执行面） | `172.21.138.101` | - |
| 5 | Runtime（runtime-agent + 网关） | `172.21.138.102` | 80/443, 7005 |
| 6 | Git + Harbor（Gitea + 镜像仓库） | `172.21.138.103` | 3000, 2222, 80（HTTP） |
| 7 | 运行态 Mongo | `待分配` | 27017 |

---

## 2. 端口用途速查

- **API**：`8080`（业务 API，通常由同机 Nginx/网关对外 80/443 转发）
- **监控（Prometheus 抓取）**
  - `9100`：node_exporter（宿主机指标）
  - `8080`：cAdvisor（容器指标，若部署）
- **Workspace-dev**
  - `7001`：workspace-node API（仅内网）
  - `80`：Workspace-dev Nginx（仅供 API(91) 的 Nginx 转发 `/preview/*`）
- **Deploy**
  - `7002`：Deploy 控制面（仅内网：API 调用 + 运维 + 后续 Runner/Runtime 心跳）
- **Runner**
  - （无对外端口）通过 `7002` 访问 Deploy；通过 `7005` 访问 runtime-agent
- **Runtime**
  - `7005`：runtime-agent（仅内网：Runner 访问）
  - `80/443`：Runtime 网关（对公网：用户访问 `/runtime/{appId}/...`；按你们网关/SLB 形态）
- **Git + Harbor（103）**
  - `2222`：Gitea SSH（Workspace/Runner 拉推代码）
  - `3000`：Gitea Web（仅运维/白名单）
  - `80`：Harbor（Registry + UI，仅内网开放；运维 UI 建议白名单/SSH 隧道）

---

## 3. 安全组矩阵（最终版：最小放行）

### 3.1 Deploy（172.21.138.100）

- **入站（Inbound）**
  - **TCP 7002**：允许来源 **`172.21.138.91/32`、`172.21.138.101/32`、`172.21.138.102/32`**
    - 用途：
      - API(91) → Deploy（创建/查询/列表 Job；含 `/admin/**` 运维接口；Header：`X-DEPLOY-SECRET`）
      - Runner(101) → Deploy（`/deploy/jobs/claim`、`/heartbeat`、`/report`）
      - Runtime(102) → Deploy（`/internal/runtime-nodes/heartbeat`）
  - 说明：**不要**将 `7002` 暴露公网
  - （监控）**TCP 9100**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Deploy(100) node_exporter

- **出站（Outbound）**
  - 默认放开即可（如后续要在该机进行构建/拉依赖/访问仓库，再按需收敛 `443`）

### 3.2 Workspace-dev（172.21.138.87）

- **入站（Inbound）**
  - **TCP 7001**：允许来源 **`172.21.138.91/32`**
    - 用途：API → workspace-node API（workspace 操作转发/代理）
  - **TCP 80**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Nginx → Workspace-dev(87) 上 Nginx（转发 `/preview/{appId}/...` 预览流量）
  - 说明：**不建议**把 `87:80`、`87:7001` 对公网开放
  - （监控）**TCP 9100**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Workspace-dev(87) node_exporter
  - （监控）**TCP 8080**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Workspace-dev(87) cAdvisor（容器指标）

- **出站（Outbound）**
  - **TCP 8080**：允许访问目标 **`172.21.138.91/32`**
    - 用途：workspace-node → API（节点心跳：`POST /api/fun-ai/admin/workspace-nodes/heartbeat`）
  - 其他默认放开即可（拉镜像/访问 npm 源通常需要 `443`）

### 3.3 API（172.21.138.91）

- **入站（Inbound）**
  - **TCP 80/443**：对公网开放（统一入口）
  - （建议收敛）**TCP 8080**：仅对本机/同机网关 + 必要的内网来源开放
    - 至少需要包含：**`172.21.138.87/32`**（workspace-node → API 心跳）

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：API → Deploy（内部调用）
  - **TCP 7001** → **`172.21.138.87/32`**
    - 用途：API → workspace-node API
  - **TCP 80** → **`172.21.138.87/32`**
    - 用途：API Nginx → Workspace-dev Nginx（`/preview/*` 转发）
  - （监控）**TCP 9100** → **`172.21.138.87/32,172.21.138.100/32,172.21.138.101/32,172.21.138.102/32`**
    - 用途：Prometheus 抓取各机器 node_exporter
  - （监控）**TCP 8080** → **`172.21.138.87/32,172.21.138.102/32`**
    - 用途：Prometheus 抓取 cAdvisor（容器指标）

### 3.4 Runner（172.21.138.101）

- **入站（Inbound）**
  - 无（Runner 主动向外发起访问即可；SSH/运维端口按你们通用基线配置，不在本文档展开）
  - （监控）**TCP 9100**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Runner(101) node_exporter

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：Runner → Deploy（claim/heartbeat/report）
  - **TCP 7005** → **`172.21.138.102/32`**
    - 用途：Runner → runtime-agent（deploy/stop/status；Header：`X-Runtime-Token`）
  - **TCP 2222** → **`172.21.138.103/32`**
    - 用途：Runner → Gitea（SSH clone/pull 源码）
  - **TCP 80** → **`172.21.138.103/32`**
    - 用途：Runner → Harbor（push 镜像，HTTP）
  - （按需）**TCP 443** → 公网/镜像仓库/Git 仓库
    - 用途：拉取依赖、推送镜像（后续接入镜像仓库时必需）

### 3.5 Runtime（172.21.138.102）

- **入站（Inbound）**
  - **TCP 7005**：允许来源 **`172.21.138.101/32`**
    - 用途：Runner → runtime-agent
  - **TCP 80/443**：对公网开放（或仅对 SLB/网关来源开放）
    - 用途：用户访问统一入口下的 `/runtime/{appId}/...`
  - （监控）**TCP 9100**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Runtime(102) node_exporter
  - （监控）**TCP 8080**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Prometheus → Runtime(102) cAdvisor（容器指标；如部署）

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：runtime-agent → Deploy（心跳注册）
  - **TCP 80** → **`172.21.138.103/32`**
    - 用途：Runtime → Harbor（pull 镜像，HTTP）
  - （按需）**TCP 443** → 公网
    - 用途：拉取基础镜像/依赖（如果你们还会访问公网镜像源）

### 3.6 Git + Harbor（172.21.138.103）

- **入站（Inbound）**
  - **TCP 2222**：允许来源 **`172.21.138.87/32`、`172.21.138.101/32`**
    - 用途：
      - Workspace-dev(87) → Gitea（SSH push/pull）
      - Runner(101) → Gitea（SSH clone/pull）
  - **TCP 3000**：仅允许来源 **运维/堡垒机/内网白名单**
    - 用途：访问 Gitea Web（创建仓库/管理 deploy key）
  - **TCP 80**：允许来源 **`172.21.138.101/32`、`172.21.138.102/32`**（以及运维/堡垒机/内网白名单）
    - 用途：
      - Runner(101) → Harbor（push 镜像）
      - Runtime(102) → Harbor（pull 镜像）
      - 运维访问 Harbor UI（建议白名单/SSH 隧道）
  - 说明：**不要**将 `2222/3000/80` 暴露公网；公网只留必要的运维入口（白名单/隧道）

- **出站（Outbound）**
  - 默认放开即可（若你们要严格收敛，至少需要 53/123/443 视情况而定）

### 3.7 运行态 Mongo（第 7 台，IP 待分配）

- **入站（Inbound）**
  - **TCP 27017**：允许来源 **`172.21.138.102/32`**（以及必要的运维跳板机/备份节点）
    - 用途：Runtime(102) 上的用户应用容器 → 运行态 Mongo
  - 说明：**绝对不要**将 `27017` 暴露公网

- **出站（Outbound）**
  - 默认放开即可（如做严格收敛，至少保留 DNS/NTP 与备份目标的访问）

---

## 4. 联调自检（建议）

- 在 **API(91)** 上验证 Deploy 可达：
  - `curl http://172.21.138.100:7002/internal/health`
- 在 **API(91)** 上验证 Workspace-dev 可达：
  - `curl http://172.21.138.87:7001/api/fun-ai/workspace/internal/health`（如你们有该 health；否则按现有接口）
  - `curl -I http://172.21.138.87/`（验证 Nginx 可达）
- 在 **Runner(101)** 上验证 runtime-agent 可达：
  - `curl http://172.21.138.102:7005/internal/health`
- 在 **Deploy(100)** 上验证 Runtime 节点在线：
  - `curl -H "X-Admin-Token: <token>" http://127.0.0.1:7002/admin/runtime-nodes/list`

---

## 5. 后续扩展（新增节点时怎么加）

当你们新增更多节点时，按以下规则"照抄追加"：

- **新增 Workspace 节点**
  - Workspace 入站：放行来自 API(91) 的 `80` 与 `7001`
  - API 出站：放行到新增 Workspace 节点的 `80` 与 `7001`
  - Workspace 出站：放行到 API(91) 的 `8080`（心跳）与 103 `4873`（Verdaccio，如需）
- **新增 Runner（执行面）**
  - Runner 出站：到 Deploy `7002`、到每个 Runtime 的 `7005`
  - Deploy 入站：放行来自该 Runner 的 `7002`
  - 每个 Runtime 入站：放行来自该 Runner 的 `7005`
- **新增 Runtime（运行态）**
  - Runtime 出站：到 Deploy `7002`、到 Mongo `27017`
  - Deploy 入站：放行来自该 Runtime 的 `7002`
  - Mongo 入站：放行来自该 Runtime 的 `27017`
  - Runtime 入站：对公网 `80/443`（或仅对 SLB/网关来源）+ 对 Runner 放行 `7005`
- **新增 Mongo 副本/分片**
  - 新 Mongo 入站：放行来自所有 Runtime 的 `27017`
  - 各 Runtime 出站：放行到新 Mongo 的 `27017`


