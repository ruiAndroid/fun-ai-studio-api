# 安全组/防火墙放行矩阵（现网最小可用）

本文档用于归档当前 FunAI Studio 的**安全组（Security Group）/防火墙**放行规则，目标是：

- **最小暴露**：只放行必要端口，只信任必要来源 IP
- **便于扩容**：后续新增 Runner/Runtime 节点时，在同一份文档内追加

> 约定：以下均为 **内网 IP**，公网仅在“统一入口”处放开。

---

## 1. 当前在线服务器清单（5 台）

- **API（统一入口 / Control Plane）**：`172.21.138.91`
- **Workspace-dev（workspace-node + Nginx）**：`172.21.138.87`
- **Deploy（Deploy 控制面）**：`172.21.138.100`
- **Runner（执行面）**：`172.21.138.101`
- **Runtime（runtime-agent + 容器运行时 + 网关）**：`172.21.138.102`

---

## 2. 端口用途速查

- **API**：`8080`（业务 API，通常由同机 Nginx/网关对外 80/443 转发）
- **Workspace-dev**
  - `7001`：workspace-node API（仅内网）
  - `80`：Workspace-dev Nginx（仅供 API(91) 的 Nginx 转发 `/ws/*`）
- **Deploy**
  - `7002`：Deploy 控制面（仅内网：API 调用 + 运维 + 后续 Runner/Runtime 心跳）
- **Runner**
  - （无对外端口）通过 `7002` 访问 Deploy；通过 `7005` 访问 runtime-agent
- **Runtime**
  - `7005`：runtime-agent（仅内网：Runner 访问）
  - `80/443`：Runtime 网关（对公网：用户访问 `/apps/{appId}/...`；按你们网关/SLB 形态）

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

- **出站（Outbound）**
  - 默认放开即可（如后续要在该机进行构建/拉依赖/访问仓库，再按需收敛 `443`）

### 3.2 Workspace-dev（172.21.138.87）

- **入站（Inbound）**
  - **TCP 7001**：允许来源 **`172.21.138.91/32`**
    - 用途：API → workspace-node API（workspace 操作转发/代理）
  - **TCP 80**：允许来源 **`172.21.138.91/32`**
    - 用途：API(91) 上 Nginx → Workspace-dev(87) 上 Nginx（转发 `/ws/{userId}/...` 预览流量）
  - 说明：**不建议**把 `87:80`、`87:7001` 对公网开放

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
    - 用途：API Nginx → Workspace-dev Nginx（`/ws/*` 转发）

### 3.4 Runner（172.21.138.101）

- **入站（Inbound）**
  - 无（Runner 主动向外发起访问即可；SSH/运维端口按你们通用基线配置，不在本文档展开）

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：Runner → Deploy（claim/heartbeat/report）
  - **TCP 7005** → **`172.21.138.102/32`**
    - 用途：Runner → runtime-agent（deploy/stop/status；Header：`X-Runtime-Token`）
  - （按需）**TCP 443** → 公网/镜像仓库/Git 仓库
    - 用途：拉取依赖、推送镜像（后续接入镜像仓库时必需）

### 3.5 Runtime（172.21.138.102）

- **入站（Inbound）**
  - **TCP 7005**：允许来源 **`172.21.138.101/32`**
    - 用途：Runner → runtime-agent
  - **TCP 80/443**：对公网开放（或仅对 SLB/网关来源开放）
    - 用途：用户访问统一入口下的 `/apps/{appId}/...`

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：runtime-agent → Deploy（心跳注册）
  - （按需）**TCP 443** → 公网/镜像仓库
    - 用途：拉取镜像（如果 Runtime 节点需要自行拉镜像）
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

## 5. 后续扩展（新增 Runner/Runtime 节点时怎么加）

当你们新增更多节点时，按以下规则“照抄追加”：

- **新增 Runner（执行面）**
  - Runner 出站：到 Deploy `7002`、到每个 Runtime 的 `7005`
  - Deploy 入站：放行来自该 Runner 的 `7002`
  - 每个 Runtime 入站：放行来自该 Runner 的 `7005`
- **新增 Runtime（运行态）**
  - Runtime 出站：到 Deploy `7002`
  - Deploy 入站：放行来自该 Runtime 的 `7002`
  - Runtime 入站：对公网 `80/443`（或仅对 SLB/网关来源）+ 对 Runner 放行 `7005`


