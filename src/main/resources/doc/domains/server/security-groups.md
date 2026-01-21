# 安全组/防火墙放行矩阵（现网最小可用）

本文档用于归档当前 FunAI Studio 的**安全组（Security Group）/防火墙**放行规则，目标是：

- **最小暴露**：只放行必要端口，只信任必要来源 IP
- **便于扩容**：后续新增 Runner/Runtime 节点时，在同一份文档内追加

> 约定：以下均为 **内网 IP**，公网仅在“统一入口”处放开。

---

## 1. 当前在线服务器清单（3 台）

- **API（统一入口 / Control Plane）**：`172.21.138.91`
- **Workspace-dev（workspace-node + Nginx）**：`172.21.138.87`
- **Deploy（Deploy 控制面）**：`172.21.138.100`

---

## 2. 端口用途速查

- **API**：`8080`（业务 API，通常由同机 Nginx/网关对外 80/443 转发）
- **Workspace-dev**
  - `7001`：workspace-node API（仅内网）
  - `80`：Workspace-dev Nginx（仅供 API(91) 的 Nginx 转发 `/ws/*`）
- **Deploy**
  - `7002`：Deploy 控制面（仅内网：API 调用 + 运维 + 后续 Runner/Runtime 心跳）

---

## 3. 安全组矩阵（最终版：最小放行）

### 3.1 Deploy（172.21.138.100）

- **入站（Inbound）**
  - **TCP 7002**：允许来源 **`172.21.138.91/32`**
    - 用途：API → Deploy（创建/查询 Job；含 `/admin/**` 运维接口）
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
  - （建议收敛）**TCP 8080**：仅对本机/内网/同机网关开放（按你们部署形态配置）

- **出站（Outbound）**
  - **TCP 7002** → **`172.21.138.100/32`**
    - 用途：API → Deploy（内部调用）
  - **TCP 7001** → **`172.21.138.87/32`**
    - 用途：API → workspace-node API
  - **TCP 80** → **`172.21.138.87/32`**
    - 用途：API Nginx → Workspace-dev Nginx（`/ws/*` 转发）

---

## 4. 联调自检（建议）

- 在 **API(91)** 上验证 Deploy 可达：
  - `curl http://172.21.138.100:7002/internal/health`
- 在 **API(91)** 上验证 Workspace-dev 可达：
  - `curl http://172.21.138.87:7001/api/fun-ai/workspace/internal/health`（如你们有该 health；否则按现有接口）
  - `curl -I http://172.21.138.87/`（验证 Nginx 可达）

---

## 5. 后续扩展（预留：Runner/Runtime 加入时怎么加）

当你们启用：

- **Runner（执行面，101）**
  - 需要：101 → 100 `7002`（claim/heartbeat/report）
  - 需要：101 → Runtime-agent `7005`（部署/查询）
- **Runtime（运行态，102）**
  - 需要：102 → 100 `7002`（runtime-agent 心跳注册）
  - 公网：102 `80/443`（用户访问 `/apps/{appId}/...`，按网关形态决定是否对公网/SLB）


